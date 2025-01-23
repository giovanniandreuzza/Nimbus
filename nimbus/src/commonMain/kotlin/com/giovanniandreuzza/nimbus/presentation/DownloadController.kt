package com.giovanniandreuzza.nimbus.presentation

import com.giovanniandreuzza.nimbus.core.application.DownloadService
import com.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import com.giovanniandreuzza.nimbus.core.application.dtos.DownloadState
import com.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import com.giovanniandreuzza.nimbus.core.ports.FileRepository
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

    override suspend fun downloadFile(downloadRequest: DownloadRequest): Long {
        return downloadService.downloadFile(downloadRequest)
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