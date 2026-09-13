package io.github.giovanniandreuzza.nimbus.di

import io.github.giovanniandreuzza.nimbus.core.application.DownloadService
import io.github.giovanniandreuzza.nimbus.core.application.services.DownloadProgressService
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.ports.ClockPort
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
import io.github.giovanniandreuzza.nimbus.infrastructure.time.SystemClock
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Holds the [AutoRetryScheduler] that cannot exist yet.
 *
 * [DownloadProgressService] is constructed before [DownloadService], and the scheduler needs
 * the service. The progress service is therefore handed callbacks that read through this
 * reference, so they see the scheduler once there is one.
 */
internal class AutoRetryRef {
    var scheduler: AutoRetryScheduler? = null
}

internal fun init(
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadPort: NimbusDownloadPort,
    nimbusStoragePort: NimbusStoragePort?,
    downloadManagerPath: String,
    downloadRoot: String?,
    downloadBufferSize: Long,
    downloadNotifyEveryBytes: Long,
    transportRetry: RetryPolicy,
    autoRetry: RetryPolicy,
    stallTimeoutMs: Long?,
    minReservedDiskBytes: Long?,
    autoStart: Boolean,
    ownsDownloadScope: Boolean,
    digestAlgorithm: DigestAlgorithm?,
    logger: NimbusLogger?
): DownloadService {
    val storage: NimbusStoragePort = nimbusStoragePort ?: FileSystemNimbusStorageAdapter()

    val clock: ClockPort = SystemClock

    val repository: DownloadTaskRepository = DownloadRepository(
        storePath = downloadManagerPath,
        dispatcher = ioDispatcher,
        nimbusStoragePort = storage,
        clock = clock,
        logger = logger,
        storeScope = downloadScope
    )

    val autoRetryRef = AutoRetryRef()

    val progressCallback: DownloadProgressCallback = DownloadProgressService(
        downloadTaskRepository = repository,
        clock = clock,
        logger = logger,
        onAutoRetry = if (autoStart) { url -> autoRetryRef.scheduler?.schedule(url) } else null,
        onDownloadSucceeded = if (autoStart) { url -> autoRetryRef.scheduler?.forget(url) } else null
    )

    val contentDigestPort: ContentDigestPort = ContentDigestAdapter(storage, ioDispatcher)

    val downloadPort: DownloadPort = DownloadAdapter(
        concurrencyLimit = concurrencyLimit,
        downloadScope = downloadScope,
        downloadProgressCallback = progressCallback,
        nimbusStoragePort = storage,
        nimbusDownloadPort = nimbusDownloadPort,
        bufferSize = downloadBufferSize,
        notifyEveryBytes = downloadNotifyEveryBytes,
        transportRetry = transportRetry,
        stallTimeoutMs = stallTimeoutMs,
        digestAlgorithm = digestAlgorithm,
        contentDigestPort = contentDigestPort,
        logger = logger
    )

    val idProvider: IdProviderPort = IdProviderAdapter()

    val storagePort: StoragePort = StorageAdapter(storage)


    val service = DownloadService(
        idProvider = idProvider,
        downloadPort = downloadPort,
        repository = repository,
        storagePort = storagePort,
        contentDigestPort = contentDigestPort,
        clock = clock,
        downloadRoot = downloadRoot,
        digestAlgorithm = digestAlgorithm,
        minReservedDiskBytes = minReservedDiskBytes,
        logger = logger,
        autoStart = autoStart,
        ownsDownloadScope = ownsDownloadScope,
        downloadScope = downloadScope
    )

    if (autoStart) {
        autoRetryRef.scheduler = AutoRetryScheduler(
            scope = downloadScope,
            policy = autoRetry,
            logger = logger,
            retryFailedDownload = service::retryFailedDownload,
            startDownload = service::startDownload
        )
    }

    return service
}
