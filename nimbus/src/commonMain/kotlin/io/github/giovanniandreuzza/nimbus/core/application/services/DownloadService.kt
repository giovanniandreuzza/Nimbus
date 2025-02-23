package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadInfoDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadInfoDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetFileSizeUseCase
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadInfo
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadInfoRepository
import io.github.giovanniandreuzza.nimbus.core.states.DownloadState
import io.github.giovanniandreuzza.nimbus.shared.ddd.domain.DomainEvent
import io.github.giovanniandreuzza.nimbus.shared.ddd.events.EventBus
import io.github.giovanniandreuzza.nimbus.shared.utils.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Download Service.
 *
 * @param concurrencyLimit The concurrency limit.
 * @author Giovanni Andreuzza
 */
internal class DownloadService(
    concurrencyLimit: Int,
    private val scope: CoroutineScope,
    private val eventBus: EventBus<DomainEvent<DownloadId>>,
    private val getFileSizeUseCase: GetFileSizeUseCase,
    private val downloadInfoRepository: DownloadInfoRepository,
) {
    private val semaphore = Semaphore(concurrencyLimit)
    private val downloadJobs = mutableMapOf<DownloadId, Job>()
    private val downloadFlows = mutableMapOf<DownloadId, MutableSharedFlow<DownloadState>>()

    internal suspend fun enqueueDownload(downloadRequest: DownloadRequest) {
        val downloadId = DownloadId.create(
            fileUrl = downloadRequest.fileUrl,
            filePath = downloadRequest.filePath,
            fileName = downloadRequest.fileName
        )

        val isDownloadInfoEnqueued =
            downloadInfoRepository.getDownloadInfo(downloadId.value) != null

        if (isDownloadInfoEnqueued) {
            return
        }

        val fileSize = getFileSizeUseCase.execute(downloadRequest.fileUrl)

        if (fileSize is Either.Failure) {
            return
        }
        fileSize as Either.Success

        val downloadInfo = DownloadInfo.create(
            fileUrl = downloadRequest.fileUrl,
            filePath = downloadRequest.filePath,
            fileName = downloadRequest.fileName,
            fileSize = fileSize.value
        )

        downloadInfoRepository.saveDownloadInfo(DownloadInfoDTO.fromDomain(downloadInfo))

        downloadFlows[downloadId] = MutableSharedFlow()
        downloadFlows[downloadId]!!.tryEmit(downloadInfo.state)

        eventBus.publishAll(downloadInfo.events)
    }

    internal fun startDownload(downloadId: String) {
        val downloadId = DownloadId.create(downloadId)

        val downloadInfo = downloadInfoRepository.getDownloadInfo(downloadId.value)?.toDomain()

        if (downloadInfo == null) {
            return
        }

        if (downloadInfo.state !is DownloadState.Idle) {
            return
        }

        if (downloadJobs.containsKey(downloadId)) {
            return
        }

        if (!downloadFlows.containsKey(downloadId)) {
            return
        }

        executeDownload(downloadInfo)
    }

    internal fun pauseDownload(downloadId: String) {
        val downloadId = DownloadId.create(downloadId)

        val downloadInfo = downloadInfoRepository.getDownloadInfo(downloadId.value)?.toDomain()

        if (downloadInfo == null) {
            return
        }

        if (downloadInfo.state !is DownloadState.Downloading) {
            return
        }

        if (!downloadJobs.containsKey(downloadId)) {
            return
        }

        if (!downloadFlows.containsKey(downloadId)) {
            return
        }

        downloadJobs[downloadId]!!.cancel()
        downloadJobs.remove(downloadId)

        downloadInfo.setPaused()

        downloadInfoRepository.saveDownloadInfo(DownloadInfoDTO.fromDomain(downloadInfo))

        downloadFlows[downloadId]!!.tryEmit(downloadInfo.state)

        eventBus.publishAll(downloadInfo.events)
    }

    internal fun resumeDownload(downloadId: String) {
        val downloadId = DownloadId.create(downloadId)

        val downloadInfo = downloadInfoRepository.getDownloadInfo(downloadId.value)?.toDomain()

        if (downloadInfo == null) {
            return
        }

        if (downloadInfo.state !is DownloadState.Paused) {
            return
        }

        if (downloadJobs.containsKey(downloadId)) {
            return
        }

        if (!downloadFlows.containsKey(downloadId)) {
            return
        }

        executeDownload(downloadInfo)
    }

    internal fun cancelDownload(downloadId: String) {
        val downloadId = DownloadId.create(downloadId)

        val downloadInfo = downloadInfoRepository.getDownloadInfo(downloadId.value)?.toDomain()

        if (downloadInfo == null) {
            return
        }

        if (!downloadFlows.containsKey(downloadId)) {
            return
        }

        downloadInfoRepository.deleteDownloadInfo(downloadId.value)

        downloadJobs[downloadId]?.cancel()
        downloadJobs.remove(downloadId)
        
        downloadFlows.remove(downloadId)
    }

    /* Private Methods */

    private fun executeDownload(downloadInfo: DownloadInfo) {
        val downloadId = downloadInfo.entityId.id

        downloadJobs[downloadId] = scope.launch {
            semaphore.withPermit {
                downloadInfoRepository.startDownload(
                    downloadInfo = DownloadInfoDTO.fromDomain(downloadInfo),
                    onProgress = { progress ->
                        downloadInfo.setDownloading(progress)
                        downloadFlows[downloadId]!!.tryEmit(downloadInfo.state)

                        eventBus.publishAll(downloadInfo.events)
                        downloadInfo.clearEvents()
                    }
                )

                downloadInfo.setDownloaded()
                downloadFlows[downloadId]!!.tryEmit(downloadInfo.state)

                eventBus.publishAll(downloadInfo.events)
            }
        }
    }

}