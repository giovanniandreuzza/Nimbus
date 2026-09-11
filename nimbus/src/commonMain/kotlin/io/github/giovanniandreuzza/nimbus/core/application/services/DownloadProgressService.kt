package io.github.giovanniandreuzza.nimbus.core.application.services

import io.github.giovanniandreuzza.explicitarchitecture.core.application.services.IsApplicationService
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.toNimbusError
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger

/**
 * Download Progress Service.
 *
 * @param downloadTaskRepository The download task repository.
 * @param logger Optional structured logger.
 * @param onAutoRetry When non-null, called after a download transitions to Failed so the library
 *   can automatically retry. Only set when autoStart is enabled.
 * @author Giovanni Andreuzza
 */
@IsApplicationService
internal class DownloadProgressService(
    private val downloadTaskRepository: DownloadTaskRepository,
    private val logger: NimbusLogger?,
    private val onAutoRetry: (suspend (fileUrl: String) -> Unit)?
) : DownloadProgressCallback {

    override suspend fun onDownloadProgress(id: String, progress: Double) {
        val downloadId = DownloadId.create(id)
        val downloadTask = downloadTaskRepository.getDownloadTask(downloadId).getOr {
            return
        }
        if (downloadTask.updateProgress(progress)) {
            downloadTaskRepository.updateDownloadProgress(downloadTask)
        }
    }

    override suspend fun onDownloadFailed(id: String, error: DownloadError) {
        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return
        }
        downloadTask.fail(error)
        downloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            logger?.log(NimbusLogEvent.PersistenceFailed(downloadTask.fileUrl.value, it))
        }
        logger?.log(
            NimbusLogEvent.DownloadFailed(
                fileUrl = downloadTask.fileUrl.value,
                error = error.toNimbusError()
            )
        )
        onAutoRetry?.invoke(downloadTask.fileUrl.value)
    }

    override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
        val downloadTask = downloadTaskRepository.getDownloadTask(DownloadId.create(id)).getOr {
            return
        }
        downloadTask.finish(checksum)
        downloadTaskRepository.saveDownloadTask(downloadTask).onFailure {
            logger?.log(NimbusLogEvent.PersistenceFailed(downloadTask.fileUrl.value, it))
        }
        logger?.log(NimbusLogEvent.DownloadFinished(fileUrl = downloadTask.fileUrl.value))
    }

}