package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound

/**
 * Download Task Repository.
 *
 * @author Giovanni Andreuzza
 */
internal interface DownloadTaskRepository {

    suspend fun getDownloadTask(id: String): KResult<DownloadTaskDTO, DownloadTaskNotFound>

    suspend fun getAllDownloadTask(): Map<String, DownloadTaskDTO>

    suspend fun saveDownloadTask(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadTaskNotFound>

    suspend fun deleteDownloadTask(id: String): KResult<Boolean, DownloadTaskNotFound>

}