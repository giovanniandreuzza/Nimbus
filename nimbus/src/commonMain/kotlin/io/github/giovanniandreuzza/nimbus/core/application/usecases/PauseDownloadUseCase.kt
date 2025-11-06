package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.commands.PauseDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort

/**
 * Pause download use case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadPort The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class PauseDownloadUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadPort: DownloadPort,
    private val downloadTaskRepository: DownloadTaskRepository
) : PauseDownloadCommand {

    override suspend fun invoke(request: PauseDownloadRequest): KResult<Unit, PauseDownloadErrors> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)

        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return Failure(PauseDownloadErrors.DownloadTaskNotFound(id))
        }

        downloadTask.pause().onFailure {
            return Failure(it)
        }

        downloadPort.stopDownload(id)

        downloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            return Failure(PauseDownloadErrors.PauseDownloadFailed(it))
        }

        return Success(Unit)
    }
}