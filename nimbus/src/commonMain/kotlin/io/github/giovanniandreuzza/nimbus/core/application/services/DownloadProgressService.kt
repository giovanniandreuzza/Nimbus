package io.github.giovanniandreuzza.nimbus.core.application.services

import io.github.giovanniandreuzza.explicitarchitecture.core.application.services.IsApplicationService
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Download Progress Service.
 *
 * @param downloadProgressScope The download progress scope.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
@IsApplicationService
internal class DownloadProgressService(
    private val downloadProgressScope: CoroutineScope,
    private val downloadTaskRepository: DownloadTaskRepository
) : DownloadProgressCallback {

    override suspend fun onDownloadProgress(id: String, progress: Double) {
        val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

        if (downloadTaskResult.isFailure()) {
            return
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadTask.updateProgress(progress)

        downloadTaskRepository.saveDownloadProgress(
            DownloadTaskDTO.fromDomain(downloadTask)
        )
    }

    override fun onDownloadFailed(id: String, error: StartDownloadErrors) {
        downloadProgressScope.launch {
            val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

            if (downloadTaskResult.isFailure()) {
                return@launch
            }

            val downloadTask = downloadTaskResult.value.toDomain()

            downloadTask.fail(error.code, error.message)

            downloadTaskRepository.saveDownloadTask(
                DownloadTaskDTO.fromDomain(downloadTask)
            )
        }
    }

    override suspend fun onDownloadFinished(id: String) {
        val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

        if (downloadTaskResult.isFailure()) {
            return
        }

        val downloadTask = downloadTaskResult.value.toDomain()

        downloadTask.finish()

        downloadTaskRepository.saveDownloadTask(
            DownloadTaskDTO.fromDomain(downloadTask)
        )
    }

}