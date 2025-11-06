package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.commands.ResumeDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort

/**
 * Resume Download Use Case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadPort The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class ResumeDownloadUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadPort: DownloadPort,
    private val downloadTaskRepository: DownloadTaskRepository,
) : ResumeDownloadCommand {

    override suspend fun invoke(request: ResumeDownloadRequest): KResult<Unit, ResumeDownloadErrors> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)

        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return Failure(ResumeDownloadErrors.DownloadTaskNotFound(id))
        }

        downloadTask.resume().onFailure {
            return Failure(it)
        }

        downloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            return Failure(ResumeDownloadErrors.ResumeDownloadFailed(it))
        }

        downloadPort.startDownload(
            downloadTask = DownloadTaskDTO.fromDomain(downloadTask),
        ).onFailure {
            val error = with(it) {
                when (this) {
                    DownloadError.ResourceNotFound -> ResumeDownloadErrors.ResourceNotFound
                    is DownloadError.PermanentError -> ResumeDownloadErrors.PermanentError(cause)
                    is DownloadError.TemporaryError -> ResumeDownloadErrors.TemporaryError(cause)
                    is DownloadError.UnexpectedError -> ResumeDownloadErrors.UnexpectedError(cause)
                }
            }
            return Failure(error)
        }

        return Success(Unit)
    }
}