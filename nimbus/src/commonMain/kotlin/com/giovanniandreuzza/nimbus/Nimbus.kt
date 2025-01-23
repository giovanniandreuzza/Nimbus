package com.giovanniandreuzza.nimbus

import com.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import com.giovanniandreuzza.nimbus.core.ports.FileRepository
import com.giovanniandreuzza.nimbus.presentation.DownloadController
import com.giovanniandreuzza.nimbus.presentation.NimbusAPI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.concurrent.Volatile

/**
 * Nimbus.
 *
 * @param dispatcher The dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param downloadRepository The download repository.
 * @param fileRepository The file repository.
 * @author Giovanni Andreuzza
 */
public class Nimbus private constructor(
    dispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    downloadRepository: DownloadRepository,
    fileRepository: FileRepository
) {

    private val nimbusAPI: NimbusAPI = DownloadController(
        dispatcher = dispatcher,
        concurrencyLimit = concurrencyLimit,
        downloadRepository = downloadRepository,
        fileRepository = fileRepository
    )

    public companion object {
        @Volatile
        private var instance: Nimbus? = null

        public class Builder {
            private var dispatcher: CoroutineDispatcher = Dispatchers.IO
            private var concurrencyLimit: Int = 1
            private var downloadRepository: DownloadRepository? = null
            private var fileRepository: FileRepository? = null

            public fun withDispatcher(dispatcher: CoroutineDispatcher): Builder =
                apply { this.dispatcher = dispatcher }

            public fun withConcurrencyLimit(concurrencyLimit: Int): Builder =
                apply { this.concurrencyLimit = concurrencyLimit }

            public fun withDownloadRepository(downloadRepository: DownloadRepository): Builder =
                apply { this.downloadRepository = downloadRepository }

            public fun withFileRepository(fileRepository: FileRepository): Builder =
                apply { this.fileRepository = fileRepository }

            public fun build(): NimbusAPI {
                if (downloadRepository == null || fileRepository == null) {
                    throw IllegalStateException("DownloadRepository and FileRepository must be provided")
                }

                if (instance == null) {
                    instance = Nimbus(
                        dispatcher = dispatcher,
                        concurrencyLimit = concurrencyLimit,
                        downloadRepository = downloadRepository!!,
                        fileRepository = fileRepository!!
                    )
                }

                return instance!!.nimbusAPI
            }
        }
    }
}