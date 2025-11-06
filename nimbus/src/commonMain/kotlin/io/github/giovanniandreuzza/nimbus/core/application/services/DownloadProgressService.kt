package io.github.giovanniandreuzza.nimbus.core.application.services

import io.github.giovanniandreuzza.explicitarchitecture.core.application.services.IsApplicationService
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onSuccess
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
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
        val downloadId = DownloadId.create(id)
        val downloadTask = downloadTaskRepository.getDownloadTask(downloadId).getOr {
            return
        }
        downloadTask.updateProgress(progress).onSuccess {
            downloadTaskRepository.updateDownloadProgress(downloadTask)
        }
    }

    override fun onDownloadFailed(id: String, error: DownloadError) {
        downloadProgressScope.launch {
            val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
                return@launch
            }
            downloadTask.fail(error)
            downloadTaskRepository.saveDownloadTask(downloadTask)
        }
    }

    override suspend fun onDownloadFinished(id: String) {
        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return
        }
        downloadTask.finish()
        downloadTaskRepository.saveDownloadTask(downloadTask)
    }

}