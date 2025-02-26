package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.api.NimbusAPI
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.di.init
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Nimbus.
 *
 * @param domainEventBusScope The domain event bus scope.
 * @param domainEventBusOnError The domain event bus on error.
 * @param downloadScope The download scope.
 * @param ioDispatcher The IO dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param nimbusDownloadRepository The nimbus download repository.
 * @param nimbusFileRepository The nimbus file repository.
 * @author Giovanni Andreuzza
 */
public class Nimbus private constructor(
    domainEventBusScope: CoroutineScope,
    domainEventBusOnError: (Throwable) -> Unit,
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadRepository: NimbusDownloadRepository,
    nimbusFileRepository: NimbusFileRepository,
    downloadManagerPath: String
) {
    private val mutex = Mutex()
    private var isInitialized: Boolean = false

    private val downloadController = init(
        domainEventBusScope = domainEventBusScope,
        domainEventBusOnError = domainEventBusOnError,
        downloadScope = downloadScope,
        ioDispatcher = ioDispatcher,
        concurrencyLimit = concurrencyLimit,
        nimbusDownloadRepository = nimbusDownloadRepository,
        nimbusFileRepository = nimbusFileRepository,
        downloadManagerPath = downloadManagerPath
    )

    public companion object {
        @Volatile
        private var instance: Nimbus? = null

        public class Builder {
            private var domainEventBusScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            private var domainEventBusOnError: (Throwable) -> Unit = {}
            private var downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            private var ioDispatcher = Dispatchers.IO
            private var concurrencyLimit = 1
            private var nimbusDownloadRepository: NimbusDownloadRepository? = null
            private var nimbusFileRepository: NimbusFileRepository? = null
            private var downloadManagerPath: String? = null

            public fun withDomainEventBusScope(domainEventBusScope: CoroutineScope): Builder =
                apply { this.domainEventBusScope = domainEventBusScope }

            public fun withDomainEventBusOnError(domainEventBusOnError: (Throwable) -> Unit): Builder =
                apply { this.domainEventBusOnError = domainEventBusOnError }

            public fun withDownloadScope(downloadScope: CoroutineScope): Builder =
                apply { this.downloadScope = downloadScope }

            public fun withIODispatcher(ioDispatcher: CoroutineDispatcher): Builder =
                apply { this.ioDispatcher = ioDispatcher }

            public fun withConcurrencyLimit(concurrencyLimit: Int): Builder =
                apply { this.concurrencyLimit = concurrencyLimit }

            public fun withNimbusDownloadRepository(nimbusDownloadRepository: NimbusDownloadRepository): Builder =
                apply { this.nimbusDownloadRepository = nimbusDownloadRepository }

            public fun withNimbusFileRepository(nimbusFileRepository: NimbusFileRepository): Builder =
                apply { this.nimbusFileRepository = nimbusFileRepository }

            public fun withDownloadManagerPath(downloadManagerPath: String): Builder =
                apply { this.downloadManagerPath = downloadManagerPath }

            public fun build(): Nimbus {
                if (nimbusDownloadRepository == null || nimbusFileRepository == null || downloadManagerPath == null) {
                    throw IllegalStateException("nimbusDownloadRepository, nimbusFileRepository and downloadManagerPath must be provided")
                }

                if (instance == null) {
                    instance = Nimbus(
                        domainEventBusScope = domainEventBusScope,
                        domainEventBusOnError = domainEventBusOnError,
                        downloadScope = downloadScope,
                        ioDispatcher = ioDispatcher,
                        concurrencyLimit = concurrencyLimit,
                        nimbusDownloadRepository = nimbusDownloadRepository!!,
                        nimbusFileRepository = nimbusFileRepository!!,
                        downloadManagerPath = downloadManagerPath!!
                    )
                }

                return instance!!
            }
        }
    }

    public suspend fun init(): KResult<NimbusAPI, FailedToLoadDownloadTasks> {
        return mutex.withLock {
            if (isInitialized) {
                return@withLock Success(downloadController)
            }

            val result = downloadController.loadDownloadTasks()

            if (result.isFailure()) {
                return@withLock result
            }

            isInitialized = true

            Success(downloadController)
        }
    }
}