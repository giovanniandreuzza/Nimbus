package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.Event
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.commands.ResumeDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository

/**
 * Resume Download Use Case.
 *
 * @param eventBus The event bus.
 * @param downloadRepository The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class ResumeDownloadUseCase(
    private val eventBus: EventBus<Event>,
    private val downloadRepository: DownloadRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
) : ResumeDownloadCommand {

    override suspend fun execute(
        params: ResumeDownloadRequest
    ): KResult<ResumeDownloadResponse, ResumeDownloadErrors> {
        val downloadId = DownloadId.create(params.downloadId)

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isFailure()) {
            return Failure(ResumeDownloadErrors.DownloadTaskNotFound(downloadId.value))
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        val isResumed = downloadTask.resume()

        if (isResumed.isFailure()) {
            return Failure(isResumed.error)
        }

        val result = downloadTaskRepository.saveDownloadTask(
            DownloadTaskDTO.fromDomain(downloadTask)
        )

        if (result.isFailure()) {
            return Failure(ResumeDownloadErrors.ResumeDownloadFailed(result.error.message))
        }

        eventBus.publishAll(downloadTask.dequeueEvents())

        downloadRepository.startDownload(
            downloadTask = DownloadTaskDTO.fromDomain(downloadTask),
        )

        return Success(ResumeDownloadResponse())
    }
}