package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.application.dtos.IsDownloadedRequest
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.queries.IsDownloadedQuery

/**
 * Is downloaded Use Case.
 *
 * @param idProviderPort The id provider port.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class IsDownloadedUseCase(
    private val idProviderPort: IdProviderPort,
    private val downloadTaskRepository: DownloadTaskRepository
) : IsDownloadedQuery {

    override suspend fun invoke(request: IsDownloadedRequest): Boolean {
        val id = idProviderPort.generateUniqueId(request.fileUrl)
        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return false
        }
        return downloadTask == DownloadState.Finished
    }
}