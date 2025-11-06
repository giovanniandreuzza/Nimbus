package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.IsAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onSuccess
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
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
        inDiskDownloadTaskRepository.loadDownloadTasks().onFailure {
            return Failure(it)
        }
        val downloadTasks = inDiskDownloadTaskRepository.getAllDownloadTask()
        downloadTasks.forEach { (_, downloadTask) ->
            inMemoryDownloadTaskRepository.saveDownloadTask(downloadTask)
        }
        return Success(Unit)
    }

    override suspend fun getDownloadTask(id: DownloadId): KResult<DownloadTask, DownloadTaskNotFound> {
        inMemoryDownloadTaskRepository.getDownloadTask(id).onSuccess {
            return Success(it)
        }
        val inDiskDownloadTask = inDiskDownloadTaskRepository.getDownloadTask(id).getOr {
            return Failure(it)
        }
        inMemoryDownloadTaskRepository.saveDownloadTask(inDiskDownloadTask).onFailure {
            return Failure(it)
        }
        return Success(inDiskDownloadTask)
    }

    override suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask> {
        return inMemoryDownloadTaskRepository.getAllDownloadTask()
    }

    override fun observeDownloadTask(
        id: DownloadId
    ): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        return inMemoryDownloadTaskRepository.observeDownloadTask(id)
    }

    override suspend fun saveDownloadTask(
        downloadTask: DownloadTask
    ): KResult<Unit, DownloadTaskNotFound> {
        inDiskDownloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            return Failure(it)
        }
        return inMemoryDownloadTaskRepository.saveDownloadTask(downloadTask)
    }

    override suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound> {
        return inMemoryDownloadTaskRepository.updateDownloadProgress(downloadTask)
    }

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, DownloadTaskNotFound> {
        inDiskDownloadTaskRepository.deleteDownloadTask(id).onFailure {
            return Failure(it)
        }
        return inMemoryDownloadTaskRepository.deleteDownloadTask(id)
    }

}