package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlin.collections.set

/**
 * In Memory Download Task Adapter.
 *
 * @author Giovanni Andreuzza
 */
internal class InMemoryDownloadTaskAdapter : DownloadTaskRepository {

    private val downloadTaskMap = mutableMapOf<DownloadId, DownloadTask>()
    private val downloadStates = mutableMapOf<DownloadId, MutableStateFlow<DownloadState>>()

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        return Success(Unit)
    }

    override suspend fun getDownloadTask(
        id: DownloadId
    ): KResult<DownloadTask, DownloadTaskNotFound> {
        return downloadTaskMap[id]?.let {
            Success(it)
        } ?: Failure(DownloadTaskNotFound)
    }

    override suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask> {
        return downloadTaskMap.mapValues { it.value }
    }

    override fun observeDownloadTask(
        id: DownloadId
    ): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        return downloadStates[id]?.let { downloadFlowState ->
            Success(downloadFlowState.asStateFlow())
        } ?: Failure(DownloadTaskNotFound)
    }

    override suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound> {
        downloadTaskMap[downloadTask.entityId.id] = downloadTask
        downloadStates[downloadTask.entityId.id]?.update { downloadTask.state } ?: let {
            downloadStates[downloadTask.entityId.id] = MutableStateFlow(downloadTask.state)
        }
        return Success(Unit)
    }

    override suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound> {
        downloadTaskMap[downloadTask.entityId.id] = downloadTask
        downloadStates[downloadTask.entityId.id]?.update { downloadTask.state }
        return Success(Unit)
    }

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, DownloadTaskNotFound> {
        downloadTaskMap.remove(id)
        downloadStates.remove(id)
        return Success(Unit)
    }
}