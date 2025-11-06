package io.github.giovanniandreuzza.nimbus.core.application.usecases

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.commands.LoadDownloadTasksCommand
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository

/**
 * Load Download Tasks Use Case.
 *
 * @param downloadTaskRepository the download task repository
 * @author Giovanni Andreuzza
 */
internal class LoadDownloadTasksUseCase(
    private val downloadTaskRepository: DownloadTaskRepository
) : LoadDownloadTasksCommand {

    override suspend fun invoke(request: Unit): KResult<Unit, FailedToLoadDownloadTasks> {
        return downloadTaskRepository.loadDownloadTasks()
    }
}