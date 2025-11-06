package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.fold
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlinx.io.InternalIoApi
import kotlinx.io.buffered
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download Adapter.
 *
 * @param concurrencyLimit Concurrency Limit
 * @param downloadScope Coroutine Scope
 * @param downloadProgressCallback Download Callback
 * @param nimbusStoragePort Nimbus Storage Port
 * @param nimbusDownloadPort Nimbus Download Port
 * @param bufferSize Buffer Size
 * @param notifyEveryBytes Notify Every Bytes
 * @author Giovanni Andreuzza
 */
@IsAdapter
internal class DownloadAdapter(
    concurrencyLimit: Int,
    private val downloadScope: CoroutineScope,
    private val downloadProgressCallback: DownloadProgressCallback,
    private val nimbusStoragePort: NimbusStoragePort,
    private val nimbusDownloadPort: NimbusDownloadPort,
    private val bufferSize: Long,
    private val notifyEveryBytes: Long
) : DownloadPort {

    private val semaphore = Semaphore(concurrencyLimit)
    private val downloadJobs = mutableMapOf<String, Job>()

    override suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeError> {
        return nimbusDownloadPort.getFileSize(fileUrl)
    }

    @OptIn(InternalIoApi::class)
    override suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError> {
        val downloadId = downloadTask.id

        if (downloadJobs.containsKey(downloadId)) {
            return Success(Unit)
        }

        val bytesAlreadyDownloaded = getBytesAlreadyDownloadedAmount(
            filePath = downloadTask.filePath
        )

        if (bytesAlreadyDownloaded == downloadTask.fileSize) {
            downloadProgressCallback.onDownloadFinished(downloadId)
            return Success(Unit)
        }

        nimbusStoragePort.exists(downloadTask.filePath).fold(
            onSuccess = { exists ->
                if (!exists) {
                    nimbusStoragePort.create(downloadTask.filePath).onFailure {
                        with(it) {
                            val error = when (this) {
                                CreateFileError.FileAlreadyExists -> throw IllegalStateException()
                                is CreateFileError.IOError -> DownloadError.PermanentError(cause)
                                is CreateFileError.ReadPermissionDenied -> DownloadError.PermanentError(
                                    cause
                                )

                                is CreateFileError.WritePermissionDenied -> DownloadError.PermanentError(
                                    cause
                                )
                            }
                            return Failure(error)
                        }
                    }
                }
            },
            onFailure = {
                return Failure(DownloadError.PermanentError(it))
            }
        )

        val sink = nimbusStoragePort.sink(
            path = downloadTask.filePath,
            hasToAppend = true
        ).getOr {
            with(it) {
                val error = when (this) {
                    GetFileSinkError.FileNotFound -> DownloadError.TemporaryError(cause)
                    is GetFileSinkError.ReadPermissionDenied -> DownloadError.PermanentError(cause)
                    is GetFileSinkError.WritePermissionDenied -> DownloadError.PermanentError(cause)
                }
                downloadProgressCallback.onDownloadFailed(
                    id = downloadId,
                    error = error
                )
                return Failure(error)
            }
        }

        val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
            val error = KError(
                code = "download_unexpected_error",
                message = throwable.message ?: "An unexpected error occurred during download.",
            )
            downloadProgressCallback.onDownloadFailed(
                id = downloadId,
                error = DownloadError.UnexpectedError(error)
            )
            downloadJobs.remove(downloadId)
        }

        downloadJobs[downloadId] = downloadScope.launch(coroutineHandler) {
            semaphore.withPermit {
                try {
                    val contentLength = downloadTask.fileSize
                    var progressBytes = bytesAlreadyDownloaded

                    nimbusDownloadPort.downloadFile(
                        fileUrl = downloadTask.fileUrl,
                        offset = bytesAlreadyDownloaded
                    ) { source ->
                        sink.buffered().use { output ->
                            source.buffered().use { input ->
                                var counter = 0L

                                while (!input.exhausted() && isActive) {
                                    val bytesRead = input.readAtMostTo(output.buffer, bufferSize)

                                    if (bytesRead > 0) {
                                        progressBytes += bytesRead
                                        counter += bytesRead

                                        output.emit()

                                        if (counter >= notifyEveryBytes) {
                                            val downloadProgress = getDownloadProgress(
                                                downloadedBytes = progressBytes,
                                                fileSize = contentLength
                                            )

                                            downloadProgressCallback.onDownloadProgress(
                                                id = downloadId,
                                                progress = downloadProgress
                                            )

                                            yield()

                                            counter = 0
                                        }
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        downloadProgressCallback.onDownloadFailed(
                            id = downloadId,
                            error = it
                        )
                        downloadJobs.remove(downloadId)
                        return@withPermit
                    }

                    downloadProgressCallback.onDownloadFinished(downloadId)
                    downloadJobs.remove(downloadId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = KError(
                        code = "download_unexpected_error",
                        message = e.message ?: "An unexpected error occurred during download.",
                    )
                    downloadProgressCallback.onDownloadFailed(
                        id = downloadId,
                        error = DownloadError.UnexpectedError(error)
                    )
                    downloadJobs.remove(downloadId)
                }
            }
        }
        return Success(Unit)
    }

    override fun stopDownload(downloadId: String) {
        downloadJobs[downloadId]?.cancel()
        downloadJobs.remove(downloadId)
    }

    /* Private Methods */

    private fun getBytesAlreadyDownloadedAmount(filePath: String): Long {
        return when (val fileSizeResult = nimbusStoragePort.size(filePath)) {
            is Failure -> 0
            is Success -> fileSizeResult.value
        }
    }
}