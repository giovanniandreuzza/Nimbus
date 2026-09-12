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
import io.github.giovanniandreuzza.nimbus.core.ports.ContentDigestPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.ContentDigest
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.readFullyInto
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
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
    private val retryBaseDelayMs: Long,
    /** When non-null, every transfer is digested with it. Null means no hashing at all. */
    private val digestAlgorithm: DigestAlgorithm? = null,
    /** Hashes a file that is already on disk, for the case where nothing is transferred. */
    private val contentDigestPort: ContentDigestPort
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
            return finishCompleteFileOnDisk(downloadTask, id)
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

            val transfer = downloadWithRetry(downloadTask, id, bytesAlreadyDownloaded) ?: return
            if (!verifyFileIntegrity(downloadTask, id, transfer.checksum)) return

            downloadProgressCallback.onDownloadFinished(id, transfer.checksum)
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
     * Verifies the file on disk against what the caller expects of it.
     *
     * Size first: it is free, and a truncated transfer should report the more specific
     * cause rather than the digest mismatch that follows from it.
     *
     * @param checksum what the transferred bytes hashed to, when a digest was computed.
     */
    private suspend fun verifyFileIntegrity(
        downloadTask: DownloadTaskDTO,
        id: String,
        checksum: Checksum?
    ): Boolean {
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

        val expected = downloadTask.expectedChecksum
        if (expected != null && checksum != null && expected != checksum) {
            // Temporary, never permanent. A mismatch is a statement about this transfer —
            // a corrupted proxy response, a truncated body a correct Content-Length hid, a
            // cache serving something stale — not about the file at the origin. Calling it
            // permanent would mark the download unrecoverable and leave the caller to
            // delete and refetch, which is the pattern this feature exists to end.
            notifyFailureAndCleanup(
                id,
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.ChecksumMismatch)
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

                is CreateFileError.UnexpectedError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return false
        }
        return true
    }

    private suspend fun truncateLocalFileAfter416(filePath: String, id: String): Boolean {
        // A delete that genuinely failed must not be mistaken for the race below: the
        // create that follows would report FileAlreadyExists, the download would retry as
        // if two writers had collided, and the real reason would never reach the logger.
        nimbusStoragePort.delete(filePath).onFailure { error ->
            if (error !is DeleteFileError.FileNotFound) {
                notifyFailureAndCleanup(
                    id,
                    DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
                )
                return false
            }
        }
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

                is CreateFileError.UnexpectedError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
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
    ): TransferOutcome? {
        var progressBytes = initialProgressBytes
        val totalFileSize = downloadTask.fileSize
        var retryAttempt = 0
        var digest: ContentDigest? = null

        downloadLoop@ while (currentCoroutineContext().isActive) {
            // Prime from disk at the start of every attempt, then feed the digest the bytes
            // streamed after that.
            //
            // The resume offset comes from the length of the file on disk and nothing about
            // it is held in memory, so a resume survives a process restart. A digest
            // accumulated only across a streaming session would therefore cover only what
            // that session transferred: after a restart-and-resume it would hash the tail
            // alone and produce a value that is well-formed, plausible and wrong. The
            // consumer would compare it later, conclude the file is corrupt, and delete and
            // re-download it on every pass, forever.
            //
            // One code path, no condition. On a fresh download the prime reads nothing. On
            // a resume it re-reads the prefix already fetched — a cost paid only when a
            // transfer was already interrupted, which is to say when far more has already
            // been wasted. Re-priming at the top of each attempt is also what makes the
            // rule hold across a 416 truncation and every transport retry.
            // The offset comes from the file, for the same reason the digest does, and at the
            // same moment.
            //
            // Holding it in memory instead looks equivalent and is not: the assignment that
            // records it only runs when the transfer returns, so an attempt whose body stopped
            // arriving leaves the variable at whatever it was before — while the bytes it did
            // write are on disk. The next attempt then asks to resume from a stale offset and
            // appends the same stretch twice. Reading the length back is also the only source
            // that survives the process dying mid-transfer, which is the case this all exists
            // for.
            progressBytes = resolvePartialBytesOnDisk(downloadTask, id) ?: return null

            val algorithm = digestAlgorithm
            if (algorithm != null) {
                val primed = ContentDigest(algorithm)
                if (!primeFromDisk(downloadTask.filePath, id, primed)) return null
                digest = primed
            }

            val sink = openSink(downloadTask.filePath, id) ?: return null

            // Nothing this callback does may escape into the port implementation's `try`.
            //
            // An implementor wraps the whole transfer, callback included, because that is the
            // only way to hold the response open while the body is read. It therefore cannot
            // tell a dead socket from a full disk — on every platform both are
            // `kotlinx.io.IOException` — and every implementor would have to solve that
            // separately to avoid retrying a disk that will never have room. The failure is
            // classified here instead, once, by the side that knows which call threw: a read
            // from the body is the transport, anything else is this adapter's own storage.
            var transferFailure: DownloadError? = null

            val result = nimbusDownloadPort.downloadFile(
                fileUrl = downloadTask.fileUrl,
                offset = progressBytes
            ) { source ->
                try {
                    sink.use { output ->
                        progressBytes = copySourceToSink(
                            source = source,
                            sink = output,
                            id = id,
                            totalFileSize = totalFileSize,
                            initialProgressBytes = progressBytes,
                            digest = digest
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BodyReadFailure) {
                    transferFailure = DownloadError.TemporaryError(
                        TemporaryDownloadErrorCause.TransportFailure(unexpectedKError(e.cause))
                    )
                } catch (e: Throwable) {
                    transferFailure = DownloadError.PermanentError(
                        storageFailureCause(
                            filePath = downloadTask.filePath,
                            totalFileSize = totalFileSize,
                            cause = e
                        )
                    )
                }
            }

            // What happened inside the callback wins: the implementation returned whatever it
            // made of a body that stopped early, and this adapter knows why it stopped.
            val outcome: KResult<Unit, DownloadError> =
                transferFailure?.let { Failure(it) } ?: result

            when (outcome) {
                is Success -> {
                    retryAttempt = 0
                    break@downloadLoop
                }

                is Failure -> {
                    val downloadError = outcome.error
                    when {
                        downloadError is DownloadError.TemporaryError &&
                                downloadError.errorCause is TemporaryDownloadErrorCause.RangeNotSatisfiable -> {
                            if (!truncateLocalFileAfter416(downloadTask.filePath, id)) return null
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
                                return null
                            }
                            retryAttempt += 1
                            delay(retryBaseDelayMs * retryAttempt)
                        }
                    }
                }
            }
        }

        return TransferOutcome(checksum = digest?.finish())
    }

    /**
     * The result of a transfer that completed. A type rather than a nullable [Checksum]
     * because null there would mean both "the transfer failed" and "no digest was
     * configured", which are not the same thing and lead to opposite handling.
     */
    private class TransferOutcome(val checksum: Checksum?)

    /**
     * Feeds [digest] the bytes already on disk for this download.
     *
     * @return false when the file could not be read (failure already notified).
     */
    private suspend fun primeFromDisk(
        filePath: String,
        id: String,
        digest: ContentDigest
    ): Boolean {
        val exists = nimbusStoragePort.exists(filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return false
        }
        if (!exists) return true

        val source = nimbusStoragePort.source(filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return false
        }

        return try {
            source.use { it.readFullyInto(ByteArray(bufferSize.toInt()), digest) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            notifyFailureAndCleanup(
                id,
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileNotAccessible)
            )
            false
        }
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

                is GetFileSinkError.UnexpectedError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return null
        }
    }

    /** A read from the response body failed. Raised only by [readingBody]. */
    private class BodyReadFailure(override val cause: Throwable) : Exception(cause)

    /**
     * Runs a read of the response body, marking anything it throws.
     *
     * The mark is what separates the two failures that reach the same `catch`: a body that
     * stopped arriving, which is transient and resumes from what is already on disk, and a
     * sink that could not be written, which retrying will not fix.
     */
    private inline fun <T> readingBody(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw BodyReadFailure(e)
        }

    /**
     * Classifies a write that the volume refused.
     *
     * The volume is asked how much room is left rather than the exception being read for
     * words like "space": the message is the platform's to phrase and a caller should not
     * have to grep it. A shortage is reported as one, so it can be told apart from storage
     * that is genuinely broken — the distinction the caller can act on, and the same answer
     * the headroom check gives when it catches the shortage before the transfer starts.
     *
     * Anything else, including a volume that cannot say, stays a storage error.
     */
    private fun storageFailureCause(
        filePath: String,
        totalFileSize: Long,
        cause: Throwable
    ): PermanentDownloadErrorCause {
        val available = when (val space = nimbusStoragePort.usableSpaceBytes(filePath)) {
            is Success -> space.value
            is Failure -> return PermanentDownloadErrorCause.StorageError(unexpectedKError(cause))
        }

        // What is left to write comes from the file, not from the byte counter. The counter
        // is advanced before the buffered sink flushes, so the write that fails is often the
        // flush of bytes the counter has already counted — leaving nothing outstanding by
        // its reckoning, and a shortage that could never be recognised. It is the same
        // lesson the resume offset had to learn.
        val onDisk = when (val size = nimbusStoragePort.size(filePath)) {
            is Success -> size.value
            is Failure -> return PermanentDownloadErrorCause.StorageError(unexpectedKError(cause))
        }
        val outstanding = (totalFileSize - onDisk).coerceAtLeast(1L)

        if (available < outstanding) {
            return PermanentDownloadErrorCause.InsufficientDiskSpace(
                KError(
                    code = "insufficient_disk_space",
                    message = "Writing $filePath needed $outstanding more bytes, " +
                            "the volume had $available.",
                    cause = unexpectedKError(cause)
                )
            )
        }
        return PermanentDownloadErrorCause.StorageError(unexpectedKError(cause))
    }

    private fun unexpectedKError(cause: Throwable?): KError = KError(
        code = "transfer_failed",
        message = cause?.message ?: "The transfer failed."
    )

    /**
     * @param digest fed every byte that passes, when a digest is being computed.
     */
    @OptIn(InternalIoApi::class)
    private suspend fun copySourceToSink(
        source: Source,
        sink: Sink,
        id: String,
        totalFileSize: Long,
        initialProgressBytes: Long,
        digest: ContentDigest?
    ): Long {
        var progressBytes = initialProgressBytes
        source.use { input ->
            var bytesSinceLastProgressUpdate = 0L

            // Chosen once per transfer, not per chunk. With no digest configured this is
            // the 2.2.0 path byte for byte: the read goes straight into the sink's own
            // buffer and nothing else touches the bytes. Hashing needs the bytes in a
            // ByteArray the digest can read, so it reads into one — reused for every chunk,
            // so the memory is the buffer and never the file. An earlier double-buffered
            // version of this loop accumulated the whole file in memory, which on a 2 GB
            // appliance is not a performance note but a crash.
            val chunk = digest?.let { ByteArray(bufferSize.toInt()) }

            while (readingBody { !input.exhausted() } && currentCoroutineContext().isActive) {
                val bytesRead = if (chunk == null) {
                    val target = sink.buffer
                    readingBody { input.readAtMostTo(target, bufferSize) }
                } else {
                    val read = readingBody { input.readAtMostTo(chunk, 0, chunk.size) }
                    if (read > 0) {
                        digest.update(chunk, 0, read)
                        sink.write(chunk, 0, read)
                    }
                    read.toLong()
                }
                // `exhausted()` said bytes were waiting, so a read that returns nothing is a
                // source that will not produce any: continuing here spins the loop against
                // it forever. Leaving instead ends the attempt with fewer bytes than the
                // declared length, which the size check turns into FileIntegrityMismatch —
                // temporary, and retried.
                if (bytesRead <= 0) break

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

    /**
     * Reports a file that is already on disk at the expected length as finished.
     *
     * Nothing is transferred here, and for a long time nothing was checked either: the file
     * was accepted on its length alone. That is the one case the digest exists for. A player
     * that keeps its assets between runs meets this path on every start, so a digest that
     * cannot answer here answers only where nobody was asking — and a caller's
     * [DownloadTaskDTO.expectedChecksum] was silently never consulted.
     *
     * The file is therefore hashed from disk and put through the same verification a
     * streamed transfer gets. With no algorithm configured nothing is hashed, exactly as
     * during a transfer.
     */
    private suspend fun finishCompleteFileOnDisk(
        downloadTask: DownloadTaskDTO,
        id: String
    ): KResult<Unit, DownloadError> {
        val algorithm = digestAlgorithm
        if (algorithm == null) {
            downloadProgressCallback.onDownloadFinished(id)
            return Success(Unit)
        }

        val checksum = when (val r = contentDigestPort.digestOf(downloadTask.filePath, algorithm)) {
            is Success -> r.value
            is Failure -> {
                // The bytes are there but could not be read. Temporary: the same file may
                // read cleanly on the next attempt, and calling it permanent would strand a
                // download that has already fully transferred.
                notifyFailureAndCleanup(
                    id,
                    DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileNotAccessible)
                )
                return Success(Unit)
            }
        }

        if (!verifyFileIntegrity(downloadTask, id, checksum)) return Success(Unit)

        downloadProgressCallback.onDownloadFinished(id, checksum)
        return Success(Unit)
    }

    private suspend fun notifyFailureAndCleanup(id: String, error: DownloadError) {
        // Remove the job before notifying so that an auto-retry triggered inside
        // onDownloadFailed can register a fresh job without seeing the stale one.
        removeJob(id)
        downloadProgressCallback.onDownloadFailed(id, error)
    }

    private suspend fun removeJob(id: String): Job? = jobsMutex.withLock { downloadJobs.remove(id) }
}
