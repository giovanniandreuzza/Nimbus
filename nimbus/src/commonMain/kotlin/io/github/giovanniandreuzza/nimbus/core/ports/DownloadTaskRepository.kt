package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * Download Task Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadTaskRepository {

    suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks>

    suspend fun getDownloadTask(id: String): KResult<DownloadTaskDTO, DownloadTaskNotFound>

    suspend fun getAllDownloadTask(): Map<String, DownloadTaskDTO>

    fun observeDownloadState(id: String): KResult<Flow<DownloadState>, DownloadTaskNotFound>

    suspend fun saveDownloadTask(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadTaskNotFound>

    fun saveDownloadProgress(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadTaskNotFound>

    suspend fun deleteDownloadTask(id: String): KResult<Boolean, DownloadTaskNotFound>

}