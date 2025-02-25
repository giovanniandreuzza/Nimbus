package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.nimbus.api.NimbusAPI
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.di.init
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.Volatile

/**
 * Nimbus.
 *
 * @param eventBudScope The event bus scope.
 * @param eventBusOnError The event bus on error.
 * @param downloadScope The download scope.
 * @param ioDispatcher The IO dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param nimbusDownloadRepository The nimbus download repository.
 * @param nimbusFileRepository The nimbus file repository.
 * @author Giovanni Andreuzza
 */
public class Nimbus private constructor(
    eventBudScope: CoroutineScope,
    eventBusOnError: (Throwable) -> Unit,
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadRepository: NimbusDownloadRepository,
    nimbusFileRepository: NimbusFileRepository,
    downloadManagerPath: String
) {

    private val nimbusAPI: NimbusAPI = init(
        eventBusScope = eventBudScope,
        eventBusOnError = eventBusOnError,
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
            private var eventBusScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            private var eventBusOnError: (Throwable) -> Unit = {}
            private var downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            private var ioDispatcher = Dispatchers.IO
            private var concurrencyLimit = 1
            private var nimbusDownloadRepository: NimbusDownloadRepository? = null
            private var nimbusFileRepository: NimbusFileRepository? = null
            private var downloadManagerPath: String? = null

            public fun withEventBusScope(eventBusScope: CoroutineScope): Builder =
                apply { this.eventBusScope = eventBusScope }

            public fun withEventBusOnError(eventBusOnError: (Throwable) -> Unit): Builder =
                apply { this.eventBusOnError = eventBusOnError }

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

            public fun build(): NimbusAPI {
                if (nimbusDownloadRepository == null || nimbusFileRepository == null || downloadManagerPath == null) {
                    throw IllegalStateException("nimbusDownloadRepository, nimbusFileRepository and downloadManagerPath must be provided")
                }

                if (instance == null) {
                    instance = Nimbus(
                        eventBudScope = eventBusScope,
                        eventBusOnError = eventBusOnError,
                        downloadScope = downloadScope,
                        ioDispatcher = ioDispatcher,
                        concurrencyLimit = concurrencyLimit,
                        nimbusDownloadRepository = nimbusDownloadRepository!!,
                        nimbusFileRepository = nimbusFileRepository!!,
                        downloadManagerPath = downloadManagerPath!!
                    )
                }

                return instance!!.nimbusAPI
            }
        }
    }
}