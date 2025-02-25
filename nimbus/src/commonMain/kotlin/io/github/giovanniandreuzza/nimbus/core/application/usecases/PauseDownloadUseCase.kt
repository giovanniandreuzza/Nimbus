package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.Event
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.commands.PauseDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository

/**
 * Pause download use case.
 *
 * @param eventBus The event bus.
 * @param downloadRepository The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class PauseDownloadUseCase(
    private val eventBus: EventBus<Event>,
    private val downloadRepository: DownloadRepository,
    private val downloadTaskRepository: DownloadTaskRepository
) : PauseDownloadCommand {

    override suspend fun execute(
        params: PauseDownloadRequest
    ): KResult<PauseDownloadResponse, PauseDownloadErrors> {
        val downloadId = DownloadId.create(params.downloadId)

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isFailure()) {
            return Failure(PauseDownloadErrors.DownloadTaskNotFound(downloadId.value))
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadRepository.stopDownload(downloadId.value)

        val isPaused = downloadTask.pause()

        if (isPaused.isFailure()) {
            return Failure(isPaused.error)
        }

        val result = downloadTaskRepository.saveDownloadTask(
            DownloadTaskDTO.fromDomain(downloadTask)
        )

        if (result.isFailure()) {
            return Failure(PauseDownloadErrors.PauseDownloadFailed(result.error.message))
        }

        eventBus.publishAll(downloadTask.dequeueEvents())

        return Success(PauseDownloadResponse())
    }
}