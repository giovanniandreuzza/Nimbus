package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.NimbusDownloadManager
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.NimbusFileManager
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import okio.buffer
import okio.use

/**
 * Okio Download Adapter.
 *
 * @param concurrencyLimit Concurrency Limit
 * @param downloadScope Coroutine Scope
 * @param downloadProgressCallback Download Callback
 * @param fileManager Nimbus File Repository
 * @param downloadManager Nimbus Download Repository
 * @author Giovanni Andreuzza
 */
@IsAdapter
internal class OkioDownloadAdapter(
    concurrencyLimit: Int,
    private val downloadScope: CoroutineScope,
    private val downloadProgressCallback: DownloadProgressCallback,
    private val fileManager: NimbusFileManager,
    private val downloadManager: NimbusDownloadManager,
    private val bufferSize: Long,
    private val notifyEveryBytes: Long
) : DownloadRepository {

    private val semaphore = Semaphore(concurrencyLimit)
    private val downloadJobs = mutableMapOf<String, Job>()

    override suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeFailed> {
        val result = downloadManager.getFileSize(fileUrl)

        if (result.isFailure()) {
            return Failure(GetFileSizeFailed(result.error.toString()))
        }

        return Success(result.value)
    }

    override fun startDownload(downloadTask: DownloadTaskDTO): Boolean {
        val downloadId = downloadTask.id

        if (downloadJobs.containsKey(downloadId)) {
            return false
        }

        val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
            downloadProgressCallback.onDownloadFailed(
                id = downloadId,
                error = StartDownloadErrors.StartDownloadFailed(
                    throwable.message ?: "Unknown error"
                )
            )
            downloadJobs.remove(downloadId)
        }

        downloadJobs[downloadId] = downloadScope.launch(coroutineHandler) {
            semaphore.withPermit {
                val bytesAlreadyDownloaded = getBytesAlreadyDownloadedAmount(
                    filePath = downloadTask.filePath
                )

                if (bytesAlreadyDownloaded == downloadTask.fileSize) {
                    downloadProgressCallback.onDownloadFinished(downloadId)
                    return@launch
                }

                val sinkResult = fileManager.getFileSink(
                    filePath = downloadTask.filePath,
                    hasToAppend = true
                )

                if (sinkResult.isFailure()) {
                    downloadProgressCallback.onDownloadFailed(
                        id = downloadId,
                        error = StartDownloadErrors.StartDownloadFailed(
                            sinkResult.error.message
                        )
                    )
                    return@launch
                }

                val sourceResult = downloadManager.downloadFile(
                    fileUrl = downloadTask.fileUrl,
                    offset = bytesAlreadyDownloaded
                )

                if (sourceResult.isFailure()) {
                    downloadProgressCallback.onDownloadFailed(
                        id = downloadId,
                        error = sourceResult.error
                    )
                    return@launch
                }

                val source = sourceResult.value
                val contentLength = downloadTask.fileSize
                var progressBytes = bytesAlreadyDownloaded

                sinkResult.value.buffer().use { output ->
                    source.buffer().use { input ->
                        var counter = 0L

                        while (!input.exhausted() && isActive) {
                            val bytesRead = input.read(output.buffer, bufferSize)

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

                downloadProgressCallback.onDownloadFinished(downloadId)
                downloadJobs.remove(downloadId)
            }
        }
        return true
    }

    override fun stopDownload(downloadId: String) {
        downloadJobs[downloadId]?.cancel()
        downloadJobs.remove(downloadId)
    }

    /* Private Methods */

    private fun getBytesAlreadyDownloadedAmount(filePath: String): Long {
        return when (val fileSizeResult = fileManager.getFileSize(filePath)) {
            is Failure -> 0
            is Success -> fileSizeResult.value
        }
    }
}