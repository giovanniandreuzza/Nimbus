package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetDownloadTaskRequest
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery

/**
 * Get download task use case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class GetDownloadTaskUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadTaskRepository: DownloadTaskRepository
) : GetDownloadTaskQuery {

    override suspend fun invoke(request: GetDownloadTaskRequest): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)
        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return Failure(it)
        }
        return Success(DownloadTaskDTO.fromDomain(downloadTask))
    }

}