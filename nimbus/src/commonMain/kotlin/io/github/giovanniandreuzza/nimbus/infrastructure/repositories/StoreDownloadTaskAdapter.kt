package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.frameworks.store.StoreManager
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.StoreError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toDomain
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toDomains
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
internal class StoreDownloadTaskAdapter(
    downloadStorePath: String,
    dispatcher: CoroutineDispatcher,
    nimbusStoragePort: NimbusStoragePort
) : StoreManager<DownloadStore>(
    filePath = downloadStorePath,
    nimbusStoragePort = nimbusStoragePort,
    serializer = DownloadStore.serializer(),
    dispatcher = dispatcher
), DownloadTaskRepository {

    suspend fun storeDownloadTask(downloadTask: DownloadTaskStore): KResult<Unit, StoreError> {
        return data?.let {
            it.downloads[downloadTask.id] = downloadTask
            store(it.copy())
        } ?: Failure(StoreError.StoreNotFound)
    }

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        init(DownloadStore()).onFailure { return Failure(FailedToLoadDownloadTasks(it)) }
        return Success(Unit)
    }

    override suspend fun getDownloadTask(id: DownloadId): KResult<DownloadTask, DownloadTaskNotFound> {
        return data?.let {
            val downloadTask = it.downloads[id.value]
            if (downloadTask == null) {
                Failure(DownloadTaskNotFound)
            } else {
                Success(downloadTask.toDomain())
            }
        } ?: Failure(DownloadTaskNotFound)
    }

    override suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask> {
        return data?.downloads?.toDomains() ?: emptyMap()
    }

    override fun observeDownloadTask(id: DownloadId): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        throw NotImplementedError()
    }

    override suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound> {
        return data?.let {
            val downloadTaskStore = downloadTask.toStore()
            it.downloads[downloadTaskStore.id] = downloadTaskStore
            val result = store(it.copy())
            if (result.isFailure()) {
                Failure(DownloadTaskNotFound)
            } else {
                Success(Unit)
            }
        } ?: Failure(DownloadTaskNotFound)
    }

    override suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound> {
        throw NotImplementedError()
    }

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, DownloadTaskNotFound> {
        return data?.let {
            val downloadTask = it.downloads[id.value]
            if (downloadTask == null) {
                Success(Unit)
            } else {
                it.downloads.remove(id.value)
                store(it.copy()).onFailure {
                    return Failure(DownloadTaskNotFound)
                }
                Success(Unit)
            }
        } ?: Failure(DownloadTaskNotFound)
    }
}