package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use

/**
 * Okio Download Adapter.
 *
 * @param concurrencyLimit Concurrency Limit
 * @param downloadScope Coroutine Scope
 * @param ioDispatcher Coroutine IO Dispatcher
 * @param downloadCallback Download Callback
 * @param nimbusFileRepository Nimbus File Repository
 * @param nimbusDownloadRepository Nimbus Download Repository
 * @author Giovanni Andreuzza
 */
internal class OkioDownloadAdapter(
    concurrencyLimit: Int,
    private val downloadScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val downloadCallback: DownloadCallback,
    private val nimbusFileRepository: NimbusFileRepository,
    private val nimbusDownloadRepository: NimbusDownloadRepository
) : DownloadRepository {

    private companion object {
        const val DEFAULT_BUFFER_SIZE: Long = 8 * 1024
    }

    private val semaphore = Semaphore(concurrencyLimit)
    private val downloadJobs = mutableMapOf<String, Job>()

    override fun startDownload(downloadTask: DownloadTaskDTO): Boolean {
        val downloadId = downloadTask.id

        if (downloadJobs.containsKey(downloadId)) {
            return false
        }

        val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
            downloadCallback.onDownloadFailed(
                id = downloadId,
                error = StartDownloadErrors.StartDownloadFailed(
                    throwable.message ?: "Unknown error"
                )
            )
        }

        downloadJobs[downloadId] = downloadScope.launch(coroutineHandler) {
            semaphore.withPermit {
                val bytesAlreadyDownloaded = getBytesAlreadyDownloadedAmount(
                    filePath = downloadTask.filePath
                )

                if (bytesAlreadyDownloaded == downloadTask.fileSize) {
                    downloadCallback.onDownloadFinished(downloadId)
                    return@launch
                }

                val sinkResult = nimbusFileRepository.getFileSink(
                    filePath = downloadTask.filePath,
                    hasToAppend = true
                )

                if (sinkResult.isFailure()) {
                    downloadCallback.onDownloadFailed(
                        id = downloadId,
                        error = StartDownloadErrors.StartDownloadFailed(
                            sinkResult.error.message
                        )
                    )
                    return@launch
                }

                val downloadStreamResult = nimbusDownloadRepository.downloadFile(
                    fileUrl = downloadTask.fileUrl,
                    offset = bytesAlreadyDownloaded
                )

                if (downloadStreamResult.isFailure()) {
                    downloadCallback.onDownloadFailed(
                        id = downloadId,
                        error = downloadStreamResult.error
                    )
                    return@launch
                }

                val source = downloadStreamResult.value.source
                val contentLength = downloadStreamResult.value.contentLength
                var progressBytes = downloadStreamResult.value.downloadedBytes

                withContext(ioDispatcher) {
                    sinkResult.value.buffer().use { output ->
                        source.buffer().use { input ->
                            while (!input.exhausted()) {
                                ensureActive()

                                val bytesRead = input.read(output.buffer, DEFAULT_BUFFER_SIZE)

                                if (bytesRead > 0) {
                                    progressBytes += bytesRead

                                    val downloadProgress = getDownloadProgress(
                                        downloadedBytes = progressBytes,
                                        fileSize = contentLength
                                    )

                                    downloadCallback.onDownloadProgress(
                                        id = downloadId,
                                        progress = downloadProgress
                                    )

                                    output.emit()
                                }
                            }
                        }
                    }
                }

                downloadCallback.onDownloadFinished(downloadId)
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
        return when (val fileSizeResult = nimbusFileRepository.getFileSize(filePath)) {
            is Failure -> 0
            is Success -> fileSizeResult.value
        }
    }
}