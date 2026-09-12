package io.github.giovanniandreuzza.nimbus.core.application.services

import io.github.giovanniandreuzza.explicitarchitecture.core.application.services.IsApplicationService
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TransitionFailure
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
 * @param onDownloadSucceeded When non-null, called after a download finishes, so whatever is
 *   counting consecutive failures for that url can forget them. Without it a device that has
 *   been up for months meets its next transient failure already at the longest back-off.
 * @author Giovanni Andreuzza
 */
@IsApplicationService
internal class DownloadProgressService(
    private val downloadTaskRepository: DownloadTaskRepository,
    private val logger: NimbusLogger?,
    private val onAutoRetry: (suspend (fileUrl: String) -> Unit)?,
    private val onDownloadSucceeded: (suspend (fileUrl: String) -> Unit)? = null
) : DownloadProgressCallback {

    // Every one of these runs on the download's own coroutine, not on the coroutine of
    // whoever called pause or resume — so the transitions go through the repository, which is
    // where the lock that both sides share lives. Mutating the entity here and saving it as a
    // separate step is what let a progress tick write `Downloading` back over a `Paused` that
    // had just been set, and persist a task as downloading with no job behind it.

    override suspend fun onDownloadProgress(id: String, progress: Double) {
        downloadTaskRepository.transitionDownloadTask(
            id = DownloadId.create(id),
            // The hot path. Progress reaches the disk only when some other transition takes
            // it along.
            persist = false
        ) { task ->
            if (task.updateProgress(progress)) Unit else null
        }
    }

    override suspend fun onDownloadFailed(id: String, error: DownloadError) {
        val downloadId = DownloadId.create(id)
        // The url is fixed for the life of the task, so reading it is not a race — and it is
        // needed even when the write below fails.
        val fileUrl = downloadTaskRepository.readDownloadTask(downloadId) { it.fileUrl.value }
            .getOr { return }

        downloadTaskRepository.transitionDownloadTask(downloadId) { task -> task.fail(error) }
            .onFailure { failure ->
                if (failure is TransitionFailure.NotPersisted) {
                    logger?.log(NimbusLogEvent.PersistenceFailed(fileUrl, failure.cause))
                }
            }

        logger?.log(
            NimbusLogEvent.DownloadFailed(
                fileUrl = fileUrl,
                error = error.toNimbusError()
            )
        )
        onAutoRetry?.invoke(fileUrl)
    }

    override suspend fun onDownloadFinished(id: String, checksum: Checksum?) {
        val downloadId = DownloadId.create(id)
        val fileUrl = downloadTaskRepository.readDownloadTask(downloadId) { it.fileUrl.value }
            .getOr { return }

        downloadTaskRepository.transitionDownloadTask(downloadId) { task ->
            task.finish(checksum)
        }.onFailure { failure ->
            if (failure is TransitionFailure.NotPersisted) {
                logger?.log(NimbusLogEvent.PersistenceFailed(fileUrl, failure.cause))
            }
        }

        logger?.log(NimbusLogEvent.DownloadFinished(fileUrl = fileUrl))
        onDownloadSucceeded?.invoke(fileUrl)
    }

}