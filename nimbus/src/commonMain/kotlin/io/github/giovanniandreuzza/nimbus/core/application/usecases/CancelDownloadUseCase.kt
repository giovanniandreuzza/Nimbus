package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.events.DomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.commands.CancelDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository

/**
 * Cancel Download Use Case.
 *
 * @param domainEventBus The event bus.
 * @param downloadRepository The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class CancelDownloadUseCase(
    private val domainEventBus: EventBus<DomainEvent<DownloadId>>,
    private val downloadRepository: DownloadRepository,
    private val downloadTaskRepository: DownloadTaskRepository
) : CancelDownloadCommand {

    override suspend fun execute(
        request: CancelDownloadRequest
    ): KResult<CancelDownloadResponse, DownloadTaskNotFound> {
        val downloadId = DownloadId.create(request.downloadId)

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isFailure()) {
            return downloadTaskResult
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadTask.cancel()

        val result = downloadTaskRepository.deleteDownloadTask(downloadId.value)

        if (result.isFailure()) {
            return result
        }

        domainEventBus.publishAll(downloadTask.dequeueEvents())

        downloadRepository.stopDownload(downloadId.value)

        return Success(CancelDownloadResponse())
    }
}