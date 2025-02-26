package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import kotlinx.coroutines.flow.Flow

/**
 * Download Task Adapter.
 *
 * @param inMemoryDownloadTaskRepository The in memory download task repository.
 * @param inDiskDownloadTaskRepository The in disk download task repository.
 * @author Giovanni Andreuzza
 */
@IsAdapter
internal class DownloadTaskAdapter(
    private val inMemoryDownloadTaskRepository: DownloadTaskRepository,
    private val inDiskDownloadTaskRepository: DownloadTaskRepository
) : DownloadTaskRepository {

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        val result = inDiskDownloadTaskRepository.loadDownloadTasks()

        if (result.isFailure()) {
            return result
        }

        val downloadTasks = inDiskDownloadTaskRepository.getAllDownloadTask()

        downloadTasks.forEach { (_, downloadTask) ->
            inMemoryDownloadTaskRepository.saveDownloadTask(downloadTask)
        }

        return Success(Unit)
    }

    override suspend fun getDownloadTask(id: String): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        val inMemoryDownloadTaskResult = inMemoryDownloadTaskRepository.getDownloadTask(id)

        if (inMemoryDownloadTaskResult.isSuccess()) {
            return inMemoryDownloadTaskResult
        }

        val inDiskDownloadTaskResult = inDiskDownloadTaskRepository.getDownloadTask(id)

        if (inDiskDownloadTaskResult.isFailure()) {
            return inDiskDownloadTaskResult
        }

        val downloadTask = inDiskDownloadTaskResult.value

        inMemoryDownloadTaskRepository.saveDownloadTask(downloadTask)

        return Success(downloadTask)
    }

    override suspend fun getAllDownloadTask(): Map<String, DownloadTaskDTO> {
        val inDiskDownloadTasks = inDiskDownloadTaskRepository.getAllDownloadTask()

        inDiskDownloadTasks.forEach { (_, downloadTask) ->
            inMemoryDownloadTaskRepository.saveDownloadTask(downloadTask)
        }

        return inDiskDownloadTasks
    }

    override fun observeDownloadState(
        id: String
    ): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        return inMemoryDownloadTaskRepository.observeDownloadState(id)
    }

    override suspend fun saveDownloadTask(
        downloadTask: DownloadTaskDTO
    ): KResult<Unit, DownloadTaskNotFound> {
        val result = inDiskDownloadTaskRepository.saveDownloadTask(downloadTask)

        if (result.isFailure()) {
            return result
        }

        inMemoryDownloadTaskRepository.saveDownloadTask(downloadTask)

        return Success(Unit)
    }

    override fun saveDownloadProgress(
        downloadTask: DownloadTaskDTO
    ): KResult<Unit, DownloadTaskNotFound> {
        return inMemoryDownloadTaskRepository.saveDownloadProgress(downloadTask)
    }

    override suspend fun deleteDownloadTask(id: String): KResult<Boolean, DownloadTaskNotFound> {
        val inDiskDownloadTaskResult = inDiskDownloadTaskRepository.deleteDownloadTask(id)

        if (inDiskDownloadTaskResult.isFailure()) {
            return inDiskDownloadTaskResult
        }

        inMemoryDownloadTaskRepository.deleteDownloadTask(id)

        return Success(true)
    }

}