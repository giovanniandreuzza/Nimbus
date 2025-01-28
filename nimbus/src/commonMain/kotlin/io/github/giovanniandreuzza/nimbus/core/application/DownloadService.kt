package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadState
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadStream
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.FileRepository
import io.github.giovanniandreuzza.nimbus.utils.emit
import io.github.giovanniandreuzza.nimbus.utils.takeUntil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use

/**
 * Download Service.
 *
 * @param dispatcher The dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param fileRepository The file repository.
 * @param downloadRepository The download repository.
 * @author Giovanni Andreuzza
 */
internal class DownloadService(
    private val dispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    private val fileRepository: FileRepository,
    private val downloadRepository: DownloadRepository
) {
    private companion object {
        const val DEFAULT_BUFFER_SIZE: Long = 8 * 1024
    }

    private val semaphore = Semaphore(concurrencyLimit)

    private val downloadScope = CoroutineScope(dispatcher + SupervisorJob())

    private val downloadRequests = mutableMapOf<DownloadId, DownloadTask>()
    private val pausedDownloads = mutableMapOf<DownloadId, DownloadTask>()
    private val downloadJobs = mutableMapOf<DownloadId, Job>()
    private val downloadFlows = mutableMapOf<DownloadId, MutableSharedFlow<DownloadState>>()

    internal suspend fun getFileSize(fileUrl: String): Long {
        return downloadRepository.getFileSize(fileUrl)
    }

    internal fun isDownloading(downloadRequest: DownloadRequest): Boolean {
        val downloadId = DownloadId.create(
            fileUrl = downloadRequest.url,
            filePath = downloadRequest.path,
            fileName = downloadRequest.name
        )

        return downloadRequests.containsKey(downloadId)
    }

    internal fun downloadFile(downloadRequest: DownloadRequest): Long {
        val downloadId = DownloadId.create(
            fileUrl = downloadRequest.url,
            filePath = downloadRequest.path,
            fileName = downloadRequest.name
        )

        val downloadTask = DownloadTask(
            id = downloadId,
            url = downloadRequest.url,
            path = downloadRequest.path,
            name = downloadRequest.name
        )

        if (downloadRequests.containsKey(downloadTask.id)) {
            return downloadId.id
        }

        downloadRequests[downloadTask.id] = downloadTask

        val downloadJob = startProcessing(downloadTask)
        downloadJobs[downloadTask.id] = downloadJob

        return downloadId.id
    }

    internal fun getOngoingDownloadId(downloadRequest: DownloadRequest): Long? {
        if (!isDownloading(downloadRequest)) {
            return null
        }

        return DownloadId.create(
            fileUrl = downloadRequest.url,
            filePath = downloadRequest.path,
            fileName = downloadRequest.name
        ).id
    }

    internal fun observeDownload(downloadId: Long): Flow<DownloadState> {
        val downloadId = DownloadId.create(downloadId)
        return downloadFlows.getOrPut(downloadId) {
            MutableSharedFlow(replay = 1)
        }.takeUntil {
            it is DownloadState.Finished || it is DownloadState.Failed
        }.onCompletion {
            if (downloadRequests.containsKey(downloadId)) {
                downloadRequests.remove(downloadId)
            }

            if (downloadJobs.containsKey(downloadId)) {
                downloadJobs[downloadId]!!.cancel()
                downloadJobs.remove(downloadId)
            }

            if (downloadFlows.containsKey(downloadId)) {
                downloadFlows.remove(downloadId)
            }
        }
    }

    internal fun pauseDownload(downloadId: Long) {
        val downloadId = DownloadId.create(downloadId)

        if (!downloadRequests.containsKey(downloadId)) {
            return
        }

        if (!downloadJobs.containsKey(downloadId)) {
            return
        }

        downloadJobs[downloadId]!!.cancel()
        downloadJobs.remove(downloadId)
        pausedDownloads[downloadId] = downloadRequests[downloadId]!!
        downloadRequests.remove(downloadId)
    }

    internal fun resumeDownload(downloadId: Long) {
        val downloadId = DownloadId.create(downloadId)

        if (downloadRequests.containsKey(downloadId)) {
            return
        }

        if (downloadJobs.containsKey(downloadId)) {
            return
        }

        if (!pausedDownloads.containsKey(downloadId)) {
            return
        }

        val downloadRequest = pausedDownloads[downloadId]!!

        downloadRequests[downloadId] = downloadRequest

        val downloadJob = startProcessing(downloadRequest)
        downloadJobs[downloadId] = downloadJob
        pausedDownloads.remove(downloadId)
    }

    internal fun cancelDownload(downloadId: Long) {
        val downloadId = DownloadId.create(downloadId)

        if (pausedDownloads.containsKey(downloadId)) {
            val downloadRequest = pausedDownloads[downloadId]!!
            deleteFile(downloadRequest.path)
            pausedDownloads.remove(downloadId)
        }

        if (downloadRequests.containsKey(downloadId)) {
            val downloadRequest = downloadRequests[downloadId]!!
            deleteFile(downloadRequest.path)
            downloadRequests.remove(downloadId)
        }

        if (downloadJobs.containsKey(downloadId)) {
            downloadJobs[downloadId]!!.cancel()
            downloadJobs.remove(downloadId)
        }

        if (downloadFlows.containsKey(downloadId)) {
            downloadFlows.remove(downloadId)
        }
    }

    private fun startProcessing(downloadTask: DownloadTask): Job {
        return downloadScope.launch {
            withContext(dispatcher) {
                try {
                    downloadFlows.emit(
                        downloadTask.id,
                        DownloadState.Idle
                    )

                    semaphore.withPermit {
                        ensureActive()

                        val fileUrl = downloadTask.url
                        val filePath = downloadTask.path

                        val downloadStream = getDownloadStream(fileUrl, filePath)

                        if (downloadStream == null) {
                            downloadFlows.emit(
                                downloadTask.id,
                                DownloadState.Finished
                            )
                            return@withPermit
                        }

                        ensureActive()

                        val sink = fileRepository.getSink(filePath)

                        val source = downloadStream.source
                        val contentLength = downloadStream.contentLength
                        var progressBytes = downloadStream.downloadedBytes

                        downloadFlows.emit(
                            downloadTask.id,
                            DownloadState.Downloading(
                                downloadProgress(
                                    downloadStream.downloadedBytes,
                                    contentLength
                                )
                            )
                        )

                        sink.buffer().use { output ->
                            source.buffer().use { input ->
                                ensureActive()

                                val bufferSize = DEFAULT_BUFFER_SIZE
                                while (!input.exhausted()) {
                                    ensureActive()

                                    val bytesRead = input.read(output.buffer, bufferSize)
                                    if (bytesRead > 0) {
                                        progressBytes += bytesRead
                                        downloadFlows.emit(
                                            downloadTask.id,
                                            DownloadState.Downloading(
                                                downloadProgress(
                                                    progressBytes,
                                                    contentLength
                                                )
                                            )
                                        )
                                        output.emit()
                                    }
                                }
                            }
                        }

                        downloadFlows.emit(
                            downloadTask.id,
                            DownloadState.Finished
                        )
                    }
                } catch (_: CancellationException) {
                    // Do nothing
                } catch (e: Exception) {
                    downloadFlows.emit(
                        downloadTask.id,
                        DownloadState.Failed(e)
                    )
                }
            }
        }
    }

    private fun deleteFile(filePath: String) {
        fileRepository.deleteFile(filePath)
    }

    private suspend fun getDownloadStream(fileUrl: String, filePath: String): DownloadStream? {
        val fileSize = getFileSize(fileUrl)

        if (!isFileDownloaded(filePath)) {
            return downloadRepository.downloadFile(fileUrl, 0)
        }

        val bytesAlreadyDownloadedAmount = getBytesAlreadyDownloadedAmount(filePath)

        if (bytesAlreadyDownloadedAmount == fileSize) {
            return null
        }

        return downloadRepository.downloadFile(fileUrl, bytesAlreadyDownloadedAmount)
    }

    private fun downloadProgress(downloadedBytes: Long, fileSize: Long): Double {
        return ((downloadedBytes * 100.0) / fileSize)
    }

    private fun isFileDownloaded(filePath: String): Boolean {
        return fileRepository.isDownloaded(filePath)
    }

    private fun getBytesAlreadyDownloadedAmount(filePath: String): Long {
        if (!isFileDownloaded(filePath)) {
            return 0
        }

        return fileRepository.getFileSize(filePath)
    }
}