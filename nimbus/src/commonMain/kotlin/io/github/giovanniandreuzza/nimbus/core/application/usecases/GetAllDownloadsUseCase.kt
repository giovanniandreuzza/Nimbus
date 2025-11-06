package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetAllDownloadsResponse
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery

/**
 * Get All Downloads Use Case.
 *
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsUseCase
internal class GetAllDownloadsUseCase(
    private val downloadTaskRepository: DownloadTaskRepository
) : GetAllDownloadsQuery {

    override suspend fun invoke(request: Unit): GetAllDownloadsResponse {
        return GetAllDownloadsResponse(DownloadTaskDTO.fromDomains(downloadTaskRepository.getAllDownloadTask()))
    }

}