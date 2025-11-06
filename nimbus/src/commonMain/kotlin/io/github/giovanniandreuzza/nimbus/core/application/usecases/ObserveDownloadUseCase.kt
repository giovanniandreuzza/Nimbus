package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery
import io.github.giovanniandreuzza.nimbus.shared.utils.takeUntil

/**
 * Observe Download Use Case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class ObserveDownloadUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadTaskRepository: DownloadTaskRepository
) : ObserveDownloadQuery {

    override suspend fun invoke(request: ObserveDownloadRequest): KResult<ObserveDownloadResponse, DownloadTaskNotFound> {
        val id = idProviderPort.generateUniqueId(request.fileUrl)
        val downloadState =
            downloadTaskRepository.observeDownloadTask(DownloadId.create(id)).getOr {
                return Failure(it)
            }.takeUntil {
                it is DownloadState.Finished || it is DownloadState.Failed
            }
        return Success(ObserveDownloadResponse(downloadState))
    }
}