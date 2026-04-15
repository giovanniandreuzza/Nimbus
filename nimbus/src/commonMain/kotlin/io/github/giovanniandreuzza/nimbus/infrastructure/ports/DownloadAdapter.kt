package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlinx.io.InternalIoApi
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download Adapter.
 *
 * Drives HTTP downloads concurrently up to [concurrencyLimit] active downloads
 * at a time. Each download runs in a child coroutine of [downloadScope] and
 * reports progress via [downloadProgressCallback].
 *
 * Job registration uses [CoroutineStart.LAZY] under [jobsMutex] to avoid races
 * where a second [startDownload] observes no job before the first registers.
 *
 * @author Giovanni Andreuzza
 */
internal class DownloadAdapter(
    concurrencyLimit: Int,
    private val downloadScope: CoroutineScope,
    private val downloadProgressCallback: DownloadProgressCallback,
    private val nimbusStoragePort: NimbusStoragePort,
    private val nimbusDownloadPort: NimbusDownloadPort,
    private val bufferSize: Long,
    private val notifyEveryBytes: Long,
    private val maxRetryAttempts: Int,
    private val retryBaseDelayMs: Long
) : DownloadPort {

    private val semaphore = Semaphore(concurrencyLimit)
    private val jobsMutex = Mutex()
    private val downloadJobs = mutableMapOf<String, Job>()

    override suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeError> {
        return nimbusDownloadPort.getFileSize(fileUrl)
    }

    @OptIn(InternalIoApi::class)
    override suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError> {
        val id = downloadTask.id

        val quickSize: Long? = when (val r = nimbusStoragePort.size(downloadTask.filePath)) {
            is Success -> r.value
            is Failure -> null
        }
        if (quickSize != null && quickSize == downloadTask.fileSize) {
            downloadProgressCallback.onDownloadFinished(id)
            return Success(Unit)
        }

        val job = downloadScope.launch(
            context = createExceptionHandler(id),
            start = CoroutineStart.LAZY
        ) {
            try {
                semaphore.withPermit {
                    runDownloadJob(downloadTask, id)
                }
            } finally {
                removeJob(id)
            }
        }

        jobsMutex.withLock {
            if (downloadJobs.containsKey(id)) {
                job.cancel()
                return Success(Unit)
            }
            downloadJobs[id] = job
        }
        job.start()
        return Success(Unit)
    }

    override suspend fun stopDownload(downloadId: String) {
        val job = removeJob(downloadId)
        job?.cancelAndJoin()
    }

    private suspend fun runDownloadJob(
        downloadTask: DownloadTaskDTO,
        id: String
    ) {
        try {
            val bytesAlreadyDownloaded = resolvePartialBytesOnDisk(downloadTask, id) ?: return
            if (!ensureDownloadTargetReady(downloadTask.filePath, id)) return
            if (!downloadWithRetry(downloadTask, id, bytesAlreadyDownloaded)) return
            if (!verifyFileIntegrity(downloadTask, id)) return

            downloadProgressCallback.onDownloadFinished(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = unexpectedDownloadError(e)
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.UnexpectedError(error))
            )
        }
    }

    /**
     * Resolves how many bytes are already on disk for resume.
     *
     * @return `null` if the situation is unreadable or invalid (failure already notified).
     */
    private suspend fun resolvePartialBytesOnDisk(
        downloadTask: DownloadTaskDTO,
        id: String
    ): Long? {
        val exists = nimbusStoragePort.exists(downloadTask.filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return null
        }
        if (!exists) return 0L

        val size = nimbusStoragePort.size(downloadTask.filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return null
        }
        return when {
            size > downloadTask.fileSize -> {
                notifyFailureAndCleanup(
                    id,
                    DownloadError.PermanentError(PermanentDownloadErrorCause.LocalFileOversized)
                )
                null
            }

            else -> size
        }
    }

    /**
     * Verifies that the file on disk matches the expected size after download.
     */
    private suspend fun verifyFileIntegrity(downloadTask: DownloadTaskDTO, id: String): Boolean {
        val actualSize = when (val r = nimbusStoragePort.size(downloadTask.filePath)) {
            is Success -> r.value
            is Failure -> 0L
        }
        if (actualSize != downloadTask.fileSize) {
            notifyFailureAndCleanup(
                id,
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileIntegrityMismatch)
            )
            return false
        }
        return true
    }

    private suspend fun ensureDownloadTargetReady(filePath: String, id: String): Boolean {
        val exists = nimbusStoragePort.exists(filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return false
        }
        if (exists) return true

        nimbusStoragePort.create(filePath).onFailure { error ->
            val mappedError = when (error) {
                CreateFileError.FileAlreadyExists -> return true
                is CreateFileError.IOError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(
                        error
                    )
                )

                is CreateFileError.ReadPermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is CreateFileError.WritePermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return false
        }
        return true
    }

    private suspend fun truncateLocalFileAfter416(filePath: String, id: String): Boolean {
        nimbusStoragePort.delete(filePath)
        nimbusStoragePort.create(filePath).onFailure { error ->
            val mappedError = when (error) {
                is CreateFileError.IOError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(
                        error
                    )
                )

                is CreateFileError.ReadPermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is CreateFileError.WritePermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                CreateFileError.FileAlreadyExists -> DownloadError.TemporaryError(
                    TemporaryDownloadErrorCause.TruncateRace
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return false
        }
        return true
    }

    /**
     * Executes the HTTP download with transport-level retry for transient errors.
     *
     * ## Two-layer retry strategy
     *
     * **Layer 1 — transport retry (this function):** handles transient HTTP/network failures
     * ([DownloadError.TemporaryError]) with exponential back-off up to [maxRetryAttempts].
     * The task stays `Downloading` throughout; no state change is visible to callers.
     * HTTP 416 (Range Not Satisfiable) is handled inline by truncating the local file
     * and restarting from byte 0.
     *
     * **Layer 2 — library-level auto-retry ([io.github.giovanniandreuzza.nimbus.di]):**
     * fires only when `autoStart = true` and a download reaches [DownloadError] after all
     * transport retries are exhausted. It transitions the task through
     * `Failed → Enqueued → Downloading`, re-fetching the remote file size before re-queuing.
     * This layer is designed for long-lived processes (e.g. kiosk apps) where a file must
     * eventually be downloaded despite repeated server-side failures.
     *
     * The two layers are complementary: Layer 1 is fast (milliseconds of back-off, stays in
     * memory), while Layer 2 is a full round-trip that resets all state and can recover from
     * errors that are permanent within a single session (e.g. a server restart changing the
     * file size).
     */
    @OptIn(InternalIoApi::class)
    private suspend fun downloadWithRetry(
        downloadTask: DownloadTaskDTO,
        id: String,
        initialProgressBytes: Long
    ): Boolean {
        var progressBytes = initialProgressBytes
        val totalFileSize = downloadTask.fileSize
        var retryAttempt = 0

        downloadLoop@ while (currentCoroutineContext().isActive) {
            val sink = openSink(downloadTask.filePath, id) ?: return false

            val result = nimbusDownloadPort.downloadFile(
                fileUrl = downloadTask.fileUrl,
                offset = progressBytes
            ) { source ->
                sink.use { output ->
                    progressBytes = copySourceToSink(
                        source = source,
                        sink = output,
                        id = id,
                        totalFileSize = totalFileSize,
                        initialProgressBytes = progressBytes
                    )
                }
            }

            when (result) {
                is Success -> {
                    retryAttempt = 0
                    break@downloadLoop
                }

                is Failure -> {
                    val downloadError = result.error
                    when {
                        downloadError is DownloadError.TemporaryError &&
                                downloadError.errorCause is TemporaryDownloadErrorCause.RangeNotSatisfiable -> {
                            if (!truncateLocalFileAfter416(downloadTask.filePath, id)) return false
                            progressBytes = 0L
                            retryAttempt = 0
                        }

                        else -> {
                            val shouldRetry = shouldRetry(
                                error = downloadError,
                                retryAttempt = retryAttempt,
                                isStillActive = currentCoroutineContext().isActive
                            )
                            if (!shouldRetry) {
                                notifyFailureAndCleanup(id, downloadError)
                                return false
                            }
                            retryAttempt += 1
                            delay(retryBaseDelayMs * retryAttempt)
                        }
                    }
                }
            }
        }

        return true
    }

    private suspend fun openSink(filePath: String, id: String): Sink? {
        return nimbusStoragePort.sink(path = filePath, hasToAppend = true).getOr { error ->
            val mappedError = when (error) {
                GetFileSinkError.FileNotFound -> DownloadError.TemporaryError(
                    TemporaryDownloadErrorCause.FileNotAccessible
                )

                is GetFileSinkError.ReadPermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is GetFileSinkError.WritePermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return null
        }
    }

    @OptIn(InternalIoApi::class)
    private suspend fun copySourceToSink(
        source: Source,
        sink: Sink,
        id: String,
        totalFileSize: Long,
        initialProgressBytes: Long
    ): Long {
        var progressBytes = initialProgressBytes
        source.use { input ->
            var bytesSinceLastProgressUpdate = 0L
            while (!input.exhausted() && currentCoroutineContext().isActive) {
                val bytesRead = input.readAtMostTo(sink.buffer, bufferSize)
                if (bytesRead <= 0) continue

                progressBytes += bytesRead
                bytesSinceLastProgressUpdate += bytesRead
                sink.emit()

                if (bytesSinceLastProgressUpdate >= notifyEveryBytes) {
                    val progress = getDownloadProgress(progressBytes, totalFileSize)
                    downloadProgressCallback.onDownloadProgress(id, progress)
                    yield()
                    bytesSinceLastProgressUpdate = 0L
                }
            }
        }
        return progressBytes
    }

    private fun shouldRetry(
        error: DownloadError,
        retryAttempt: Int,
        isStillActive: Boolean
    ): Boolean {
        return error is DownloadError.TemporaryError &&
                retryAttempt < maxRetryAttempts &&
                isStillActive
    }

    private fun createExceptionHandler(id: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            val error = unexpectedDownloadError(throwable)
            downloadScope.launch {
                notifyFailureAndCleanup(
                    id,
                    DownloadError.PermanentError(PermanentDownloadErrorCause.UnexpectedError(error))
                )
            }
        }
    }

    private fun unexpectedDownloadError(throwable: Throwable): KError {
        return KError(
            code = "download_unexpected_error",
            message = throwable.message ?: "An unexpected error occurred during download."
        )
    }

    private suspend fun notifyFailureAndCleanup(id: String, error: DownloadError) {
        // Remove the job before notifying so that an auto-retry triggered inside
        // onDownloadFailed can register a fresh job without seeing the stale one.
        removeJob(id)
        downloadProgressCallback.onDownloadFailed(id, error)
    }

    private suspend fun removeJob(id: String): Job? = jobsMutex.withLock { downloadJobs.remove(id) }
}
