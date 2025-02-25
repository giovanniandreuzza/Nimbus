package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.Event
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.commands.CancelDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository

/**
 * Cancel Download Use Case.
 *
 * @param eventBus The event bus.
 * @param downloadRepository The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class CancelDownloadUseCase(
    private val eventBus: EventBus<Event>,
    private val downloadRepository: DownloadRepository,
    private val downloadTaskRepository: DownloadTaskRepository
) : CancelDownloadCommand {

    override suspend fun execute(
        params: CancelDownloadRequest
    ): KResult<CancelDownloadResponse, DownloadTaskNotFound> {
        val downloadId = DownloadId.create(params.downloadId)

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isFailure()) {
            return Failure(DownloadTaskNotFound(downloadId.value))
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadTask.cancel()

        val result = downloadTaskRepository.deleteDownloadTask(downloadId.value)

        if (result.isFailure()) {
            return Failure(DownloadTaskNotFound(downloadId.value))
        }

        eventBus.publishAll(downloadTask.dequeueEvents())

        downloadRepository.stopDownload(downloadId.value)

        return Success(CancelDownloadResponse())
    }
}