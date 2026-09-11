package io.github.giovanniandreuzza.nimbus.di

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.DownloadService
import io.github.giovanniandreuzza.nimbus.core.application.services.DownloadProgressService
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.ports.ContentDigestPort
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.DownloadAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.IdProviderAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.ContentDigestAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.DownloadRepository
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Holds a mutable reference to a suspend callback, used to break the circular
 * dependency between [DownloadProgressService] and [DownloadService].
 *
 * [DownloadProgressService] is constructed before [DownloadService], so we pass
 * this ref immediately and populate [fn] once the service is ready. The lambda
 * inside [DownloadProgressService] always reads through this ref, so it sees the
 * final value at call time.
 */
internal class AutoRetryRef {
    var fn: (suspend (fileUrl: String) -> Unit)? = null
}

internal fun init(
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadPort: NimbusDownloadPort,
    nimbusStoragePort: NimbusStoragePort?,
    downloadManagerPath: String,
    downloadBufferSize: Long,
    downloadNotifyEveryBytes: Long,
    maxRetryAttempts: Int,
    retryBaseDelayMs: Long,
    minReservedDiskBytes: Long?,
    autoStart: Boolean,
    digestAlgorithm: DigestAlgorithm?,
    logger: NimbusLogger?
): DownloadService {
    val storage: NimbusStoragePort = nimbusStoragePort ?: FileSystemNimbusStorageAdapter()

    val repository: DownloadTaskRepository = DownloadRepository(
        storePath = downloadManagerPath,
        dispatcher = ioDispatcher,
        nimbusStoragePort = storage,
        logger = logger,
        storeScope = downloadScope
    )

    val autoRetryRef = AutoRetryRef()

    val progressCallback: DownloadProgressCallback = DownloadProgressService(
        downloadTaskRepository = repository,
        logger = logger,
        onAutoRetry = if (autoStart) { url -> autoRetryRef.fn?.invoke(url) } else null
    )

    val downloadPort: DownloadPort = DownloadAdapter(
        concurrencyLimit = concurrencyLimit,
        downloadScope = downloadScope,
        downloadProgressCallback = progressCallback,
        nimbusStoragePort = storage,
        nimbusDownloadPort = nimbusDownloadPort,
        bufferSize = downloadBufferSize,
        notifyEveryBytes = downloadNotifyEveryBytes,
        maxRetryAttempts = maxRetryAttempts,
        retryBaseDelayMs = retryBaseDelayMs,
        digestAlgorithm = digestAlgorithm
    )

    val idProvider: IdProviderPort = IdProviderAdapter()

    val storagePort: StoragePort = StorageAdapter(storage)

    val contentDigestPort: ContentDigestPort = ContentDigestAdapter(storage, ioDispatcher)

    val service = DownloadService(
        idProvider = idProvider,
        downloadPort = downloadPort,
        repository = repository,
        storagePort = storagePort,
        contentDigestPort = contentDigestPort,
        digestAlgorithm = digestAlgorithm,
        minReservedDiskBytes = minReservedDiskBytes,
        logger = logger,
        autoStart = autoStart,
        downloadScope = downloadScope
    )

    if (autoStart) {
        autoRetryRef.fn = { url ->
            // Launched in the background so the failing download coroutine returns from
            // onDownloadFailed immediately, releases its semaphore permit, and the retry
            // job is registered only after the original job's `finally` block has run.
            downloadScope.launch {
                service.retryFailedDownload(url).onFailure {
                    logger?.log(NimbusLogEvent.AutoRetryFailed(url, it))
                    return@launch
                }
                service.startDownload(url).onFailure {
                    logger?.log(NimbusLogEvent.AutoStartFailed(url, it))
                }
            }
        }
    }

    return service
}
