package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery

/**
 * Get All Downloads Use Case.
 *
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class GetAllDownloadsUseCase(
    private val downloadTaskRepository: DownloadTaskRepository
) : GetAllDownloadsQuery {

    override suspend fun execute(): Map<String, DownloadTaskDTO> {
        return downloadTaskRepository.getAllDownloadTask()
    }
}