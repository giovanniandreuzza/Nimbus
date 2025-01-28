package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.nimbus.core.application.DownloadService
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.FileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * Download Controller.
 *
 * @param dispatcher The dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param downloadRepository The download repository.
 * @param fileRepository The file repository.
 * @author Giovanni Andreuzza
 */
internal class DownloadController(
    dispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    downloadRepository: DownloadRepository,
    fileRepository: FileRepository
) : NimbusAPI {

    private val downloadService: DownloadService = DownloadService(
        dispatcher,
        concurrencyLimit,
        fileRepository,
        downloadRepository
    )

    override suspend fun getFileSize(url: String): Long {
        return downloadService.getFileSize(url)
    }

    override fun isDownloading(downloadRequest: DownloadRequest): Boolean {
        return downloadService.isDownloading(downloadRequest)
    }

    override suspend fun downloadFile(downloadRequest: DownloadRequest): Long {
        return downloadService.downloadFile(downloadRequest)
    }

    override suspend fun getOngoingDownloadId(downloadRequest: DownloadRequest): Long? {
        return downloadService.getOngoingDownloadId(downloadRequest)
    }

    override fun observeDownload(downloadId: Long): Flow<DownloadState> {
        return downloadService.observeDownload(downloadId)
    }

    override fun pauseDownload(downloadId: Long) {
        downloadService.pauseDownload(downloadId)
    }

    override fun resumeDownload(downloadId: Long) {
        downloadService.resumeDownload(downloadId)
    }

    override fun cancelDownload(downloadId: Long) {
        downloadService.cancelDownload(downloadId)
    }
}