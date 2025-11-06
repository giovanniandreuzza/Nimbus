package io.github.giovanniandreuzza.nimbus.di

import io.github.giovanniandreuzza.explicitarchitecture.di.IsDi
import io.github.giovanniandreuzza.nimbus.core.application.services.DownloadProgressService
import io.github.giovanniandreuzza.nimbus.core.application.usecases.CancelDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.EnqueueDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetAllDownloadsUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetDownloadTaskUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetFileSizeUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.IsDownloadedUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.LoadDownloadTasksUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.ObserveDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.PauseDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.ResumeDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.StartDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.commands.CancelDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.EnqueueDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.LoadDownloadTasksCommand
import io.github.giovanniandreuzza.nimbus.core.commands.PauseDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.ResumeDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.StartDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery
import io.github.giovanniandreuzza.nimbus.core.queries.IsDownloadedQuery
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.LocalNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.frameworks.ktor.KtorClient
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.download.KtorNimbusDownloadAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.DownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.InMemoryDownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.DownloadAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.IdProviderAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.StoreDownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.presentation.DownloadController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Initialize the Download Controller.
 *
 * @param downloadScope The download scope.
 * @param ioDispatcher The IO dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param nimbusDownloadPort The nimbus download port.
 * @param nimbusStoragePort The nimbus storage repository.
 * @param downloadManagerPath The download manager path.
 * @return [DownloadController] The Download Controller.
 * @author Giovanni Andreuzza
 */
@IsDi
internal fun init(
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadPort: NimbusDownloadPort?,
    nimbusStoragePort: NimbusStoragePort?,
    downloadManagerPath: String,
    downloadBufferSize: Long,
    downloadNotifyEveryBytes: Long
): DownloadController {

    val ktorClient = KtorClient()

    val nimbusDownloadPort = nimbusDownloadPort ?: KtorNimbusDownloadAdapter(ktorClient)

    val nimbusStoragePort = nimbusStoragePort ?: LocalNimbusStorageAdapter()

    val idProviderPort: IdProviderPort = IdProviderAdapter()

    val inMemoryDownloadTaskRepository: DownloadTaskRepository = InMemoryDownloadTaskAdapter()

    val inDiskDownloadTaskRepository: DownloadTaskRepository = StoreDownloadTaskAdapter(
        downloadStorePath = downloadManagerPath,
        dispatcher = ioDispatcher,
        nimbusStoragePort = nimbusStoragePort
    )

    val downloadTaskRepository: DownloadTaskRepository = DownloadTaskAdapter(
        inMemoryDownloadTaskRepository = inMemoryDownloadTaskRepository,
        inDiskDownloadTaskRepository = inDiskDownloadTaskRepository
    )

    val downloadProgressCallback: DownloadProgressCallback = DownloadProgressService(
        downloadProgressScope = downloadScope,
        downloadTaskRepository = downloadTaskRepository
    )

    val downloadPort: DownloadPort = DownloadAdapter(
        concurrencyLimit = concurrencyLimit,
        downloadScope = downloadScope,
        downloadProgressCallback = downloadProgressCallback,
        nimbusStoragePort = nimbusStoragePort,
        nimbusDownloadPort = nimbusDownloadPort,
        bufferSize = downloadBufferSize,
        notifyEveryBytes = downloadNotifyEveryBytes
    )

    val loadDownloadTasksCommand: LoadDownloadTasksCommand = LoadDownloadTasksUseCase(
        downloadTaskRepository = downloadTaskRepository
    )

    val isDownloadedQuery: IsDownloadedQuery = IsDownloadedUseCase(
        idProviderPort = idProviderPort,
        downloadTaskRepository = downloadTaskRepository
    )

    val getDownloadTaskQuery: GetDownloadTaskQuery =
        GetDownloadTaskUseCase(
            idProviderPort = idProviderPort,
            downloadTaskRepository = downloadTaskRepository
        )

    val getAllDownloadsQuery: GetAllDownloadsQuery =
        GetAllDownloadsUseCase(
            downloadTaskRepository = downloadTaskRepository
        )

    val getFileSizeQuery: GetFileSizeQuery = GetFileSizeUseCase(
        downloadPort = downloadPort
    )

    val enqueueDownloadCommand: EnqueueDownloadCommand = EnqueueDownloadUseCase(
        idProviderPort = idProviderPort,
        getFileSizeQuery = getFileSizeQuery,
        downloadTaskRepository = downloadTaskRepository
    )

    val startDownloadCommand: StartDownloadCommand = StartDownloadUseCase(
        idProviderPort = idProviderPort,
        downloadPort = downloadPort,
        downloadTaskRepository = downloadTaskRepository
    )

    val observeDownloadQuery: ObserveDownloadQuery = ObserveDownloadUseCase(
        idProviderPort = idProviderPort,
        downloadTaskRepository = downloadTaskRepository
    )

    val pauseDownloadCommand: PauseDownloadCommand = PauseDownloadUseCase(
        idProviderPort = idProviderPort,
        downloadPort = downloadPort,
        downloadTaskRepository = downloadTaskRepository
    )

    val resumeDownloadCommand: ResumeDownloadCommand = ResumeDownloadUseCase(
        idProviderPort = idProviderPort,
        downloadPort = downloadPort,
        downloadTaskRepository = downloadTaskRepository
    )

    val cancelDownloadCommand: CancelDownloadCommand = CancelDownloadUseCase(
        idProviderPort = idProviderPort,
        downloadPort = downloadPort,
        downloadTaskRepository = downloadTaskRepository,
        nimbusStoragePort = nimbusStoragePort
    )

    val downloadController = DownloadController(
        loadDownloadTasksCommand = loadDownloadTasksCommand,
        isDownloadedQuery = isDownloadedQuery,
        getDownloadTaskQuery = getDownloadTaskQuery,
        getAllDownloadsQuery = getAllDownloadsQuery,
        getFileSizeQuery = getFileSizeQuery,
        enqueueDownloadCommand = enqueueDownloadCommand,
        startDownloadCommand = startDownloadCommand,
        observeDownloadQuery = observeDownloadQuery,
        pauseDownloadCommand = pauseDownloadCommand,
        resumeDownloadCommand = resumeDownloadCommand,
        cancelDownloadCommand = cancelDownloadCommand
    )

    return downloadController
}