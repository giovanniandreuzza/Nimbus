package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import kotlinx.coroutines.flow.Flow

/**
 * Download Task Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadTaskRepository {

    suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks>

    suspend fun getDownloadTask(id: DownloadId): KResult<DownloadTask, DownloadTaskNotFound>

    suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask>

    fun observeDownloadTask(id: DownloadId): KResult<Flow<DownloadState>, DownloadTaskNotFound>

    suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound>

    suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, DownloadTaskNotFound>

    suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, DownloadTaskNotFound>

}