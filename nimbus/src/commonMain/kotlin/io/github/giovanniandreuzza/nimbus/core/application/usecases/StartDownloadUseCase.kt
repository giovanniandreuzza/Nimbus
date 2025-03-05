package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.commands.StartDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository

/**
 * Start Download Use Case.
 *
 * @param downloadRepository The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class StartDownloadUseCase(
    private val downloadRepository: DownloadRepository,
    private val downloadTaskRepository: DownloadTaskRepository
) : StartDownloadCommand {

    override suspend fun execute(
        request: StartDownloadRequest
    ): KResult<StartDownloadResponse, StartDownloadErrors> {
        val downloadId = DownloadId.create(request.downloadId)

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isFailure()) {
            return Failure(StartDownloadErrors.DownloadTaskNotFound(downloadId.value))
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        val isStarted = downloadTask.start()

        if (isStarted.isFailure()) {
            return Failure(isStarted.error)
        }

        val result = downloadTaskRepository.saveDownloadTask(
            DownloadTaskDTO.fromDomain(downloadTask)
        )

        if (result.isFailure()) {
            return Failure(StartDownloadErrors.StartDownloadFailed(result.error.message))
        }

        downloadRepository.startDownload(
            downloadTask = DownloadTaskDTO.fromDomain(downloadTask),
        )

        return Success(StartDownloadResponse())
    }

}