package io.github.giovanniandreuzza.nimbus.core.application.services

import io.github.giovanniandreuzza.explicitarchitecture.core.application.services.IsApplicationService
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.events.DomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Download Progress Service.
 *
 * @param downloadProgressScope The download progress scope.
 * @param downloadTaskRepository The download task repository.
 * @param domainEventBus The domain event bus.
 * @author Giovanni Andreuzza
 */
@IsApplicationService
internal class DownloadProgressService(
    private val downloadProgressScope: CoroutineScope,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val domainEventBus: EventBus<DomainEvent<DownloadId>>,
) : DownloadProgressCallback {

    override suspend fun onDownloadProgress(id: String, progress: Double) {
        val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

        if (downloadTaskResult.isFailure()) {
            return
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadTask.updateProgress(progress)

        val result = downloadTaskRepository.saveDownloadProgress(
            DownloadTaskDTO.fromDomain(downloadTask)
        )

        if (result.isFailure()) {
            return
        }

        domainEventBus.publishAll(downloadTask.dequeueEvents())
    }

    override fun onDownloadFailed(id: String, error: StartDownloadErrors) {
        downloadProgressScope.launch {
            val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

            if (downloadTaskResult.isFailure()) {
                return@launch
            }

            val downloadTask = downloadTaskResult.value.toDomain()

            downloadTask.fail(error.code, error.message)

            val result = downloadTaskRepository.saveDownloadTask(
                DownloadTaskDTO.fromDomain(downloadTask)
            )

            if (result.isFailure()) {
                return@launch
            }

            domainEventBus.publishAll(downloadTask.dequeueEvents())
        }
    }

    override suspend fun onDownloadFinished(id: String) {
        val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

        if (downloadTaskResult.isFailure()) {
            return
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadTask.finish()

        val result = downloadTaskRepository.saveDownloadTask(
            DownloadTaskDTO.fromDomain(downloadTask)
        )

        if (result.isFailure()) {
            return
        }

        domainEventBus.publishAll(downloadTask.dequeueEvents())
    }

}