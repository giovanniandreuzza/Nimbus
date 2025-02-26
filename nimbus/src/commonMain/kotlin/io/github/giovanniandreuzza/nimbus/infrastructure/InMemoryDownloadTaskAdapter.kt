package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In Memory Download Task Adapter.
 *
 * @author Giovanni Andreuzza
 */
internal class InMemoryDownloadTaskAdapter : DownloadTaskRepository {

    private val downloadTasks = mutableMapOf<String, MutableStateFlow<DownloadTaskDTO>>()

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        return Success(Unit)
    }

    override suspend fun getDownloadTask(
        id: String
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        return downloadTasks[id]?.value?.let {
            Success(it)
        } ?: Failure(DownloadTaskNotFound())
    }

    override suspend fun getAllDownloadTask(): Map<String, DownloadTaskDTO> {
        return downloadTasks.mapValues { it.value.value }
    }

    override fun observeDownloadState(
        id: String
    ): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        return downloadTasks[id]?.let { downloadFlow ->
            Success(downloadFlow.map { it.state })
        } ?: Failure(DownloadTaskNotFound())
    }

    override suspend fun saveDownloadTask(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadTaskNotFound> {
        downloadTasks[downloadTask.id]?.let {
            it.update { downloadTask }
        } ?: run {
            downloadTasks[downloadTask.id] = MutableStateFlow(downloadTask)
        }
        return Success(Unit)
    }

    override fun saveDownloadProgress(
        downloadTask: DownloadTaskDTO
    ): KResult<Unit, DownloadTaskNotFound> {
        downloadTasks[downloadTask.id]?.let {
            it.update { downloadTask }
            return Success(Unit)
        } ?: return Failure(DownloadTaskNotFound())
    }

    override suspend fun deleteDownloadTask(id: String): KResult<Boolean, DownloadTaskNotFound> {
        return downloadTasks.remove(id)?.let {
            Success(true)
        } ?: Failure(DownloadTaskNotFound())
    }

}