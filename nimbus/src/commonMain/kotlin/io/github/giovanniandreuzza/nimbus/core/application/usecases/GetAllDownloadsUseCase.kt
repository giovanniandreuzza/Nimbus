package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Empty
import io.github.giovanniandreuzza.explicitarchitecture.core.application.usecases.IsUseCase
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
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

    override suspend fun execute(request: Empty): KResult<GetAllDownloadsResponse, Nothing> {
        return Success(GetAllDownloadsResponse(downloadTaskRepository.getAllDownloadTask()))
    }

}