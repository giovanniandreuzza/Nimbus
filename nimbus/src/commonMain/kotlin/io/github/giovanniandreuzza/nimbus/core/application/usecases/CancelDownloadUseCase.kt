package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.commands.CancelDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort

/**
 * Cancel Download Use Case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadPort The download repository.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class CancelDownloadUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadPort: DownloadPort,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val nimbusStoragePort: NimbusStoragePort
) : CancelDownloadCommand {

    override suspend fun invoke(
        request: CancelDownloadRequest
    ): KResult<Unit, DownloadTaskNotFound> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)

        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id))
            .getOr { return Failure(it) }

        downloadTask.cancel()

        downloadTaskRepository.deleteDownloadTask(DownloadId.create(id)).onFailure {
            return Failure(it)
        }

        downloadPort.stopDownload(id)

        nimbusStoragePort.delete(downloadTask.filePath.value)

        return Success(Unit)
    }
}