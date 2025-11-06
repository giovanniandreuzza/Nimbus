package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.commands.StartDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort

/**
 * Start Download Use Case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadPort The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class StartDownloadUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadPort: DownloadPort,
    private val downloadTaskRepository: DownloadTaskRepository
) : StartDownloadCommand {

    override suspend fun invoke(request: StartDownloadRequest): KResult<Unit, StartDownloadErrors> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)

        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return Failure(StartDownloadErrors.DownloadTaskNotFound(id))
        }

        downloadTask.start().onFailure {
            return Failure(it)
        }

        downloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            return Failure(StartDownloadErrors.PermanentError(it))
        }

        downloadPort.startDownload(
            downloadTask = DownloadTaskDTO.fromDomain(downloadTask),
        ).onFailure {
            val error = with(it) {
                when (this) {
                    DownloadError.ResourceNotFound -> StartDownloadErrors.ResourceNotFound
                    is DownloadError.PermanentError -> StartDownloadErrors.PermanentError(cause)
                    is DownloadError.TemporaryError -> StartDownloadErrors.TemporaryError(cause)
                    is DownloadError.UnexpectedError -> StartDownloadErrors.UnexpectedError(cause)
                }
            }
            return Failure(error)
        }

        return Success(Unit)
    }
}