package io.github.giovanniandreuzza.nimbus.di

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.events.DomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.di.IsDi
import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.adapters.EventBusAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.core.application.services.DownloadProgressService
import io.github.giovanniandreuzza.nimbus.core.application.usecases.CancelDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.EnqueueDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetAllDownloadsUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetDownloadTaskUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetFileSizeUseCase
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
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.NimbusDownloadManager
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.NimbusFileManager
import io.github.giovanniandreuzza.nimbus.infrastructure.DownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.InDiskDownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.InMemoryDownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.OkioDownloadAdapter
import io.github.giovanniandreuzza.nimbus.presentation.DownloadController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Initialize the Download Controller.
 *
 * @param domainEventBusScope The domain event bus scope.
 * @param domainEventBusOnError The domain event bus on error.
 * @param downloadScope The download scope.
 * @param ioDispatcher The IO dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param nimbusDownloadRepository The nimbus download repository.
 * @param nimbusFileRepository The nimbus file repository.
 * @param downloadManagerPath The download manager path.
 * @return [DownloadController] The Download Controller.
 * @author Giovanni Andreuzza
 */
@IsDi
internal fun init(
    domainEventBusScope: CoroutineScope,
    domainEventBusOnError: (Throwable) -> Unit,
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadRepository: NimbusDownloadRepository,
    nimbusFileRepository: NimbusFileRepository,
    downloadManagerPath: String
): DownloadController {
    val domainEventBus: EventBus<DomainEvent<DownloadId>> = EventBusAdapter(
        eventBusScope = domainEventBusScope
    )
    domainEventBus.start(onError = domainEventBusOnError)

    val nimbusFileManager = NimbusFileManager(
        nimbusFileRepository = nimbusFileRepository
    )

    val nimbusDownloadManager = NimbusDownloadManager(
        nimbusDownloadRepository = nimbusDownloadRepository
    )

    val inMemoryDownloadTaskRepository: DownloadTaskRepository = InMemoryDownloadTaskAdapter()

    val inDiskDownloadTaskRepository: DownloadTaskRepository = InDiskDownloadTaskAdapter(
        ioDispatcher = ioDispatcher,
        downloadManagerPath = downloadManagerPath,
        fileManager = nimbusFileManager
    )

    val downloadTaskRepository: DownloadTaskRepository = DownloadTaskAdapter(
        inMemoryDownloadTaskRepository = inMemoryDownloadTaskRepository,
        inDiskDownloadTaskRepository = inDiskDownloadTaskRepository
    )

    val downloadProgressCallback: DownloadProgressCallback = DownloadProgressService(
        downloadProgressScope = downloadScope,
        downloadTaskRepository = downloadTaskRepository,
        domainEventBus = domainEventBus
    )

    val downloadRepository: DownloadRepository = OkioDownloadAdapter(
        concurrencyLimit = concurrencyLimit,
        downloadScope = downloadScope,
        ioDispatcher = ioDispatcher,
        downloadProgressCallback = downloadProgressCallback,
        fileManager = nimbusFileManager,
        downloadManager = nimbusDownloadManager
    )

    val loadDownloadTasksCommand: LoadDownloadTasksCommand = LoadDownloadTasksUseCase(
        downloadTaskRepository = downloadTaskRepository
    )

    val getDownloadTaskQuery: GetDownloadTaskQuery =
        GetDownloadTaskUseCase(
            downloadTaskRepository = downloadTaskRepository
        )

    val getAllDownloadsQuery: GetAllDownloadsQuery =
        GetAllDownloadsUseCase(
            downloadTaskRepository = downloadTaskRepository
        )

    val getFileSizeQuery: GetFileSizeQuery = GetFileSizeUseCase(
        downloadRepository = downloadRepository
    )

    val enqueueDownloadCommand: EnqueueDownloadCommand = EnqueueDownloadUseCase(
        domainEventBus = domainEventBus,
        getFileSizeQuery = getFileSizeQuery,
        downloadTaskRepository = downloadTaskRepository
    )

    val startDownloadCommand: StartDownloadCommand = StartDownloadUseCase(
        domainEventBus = domainEventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val observeDownloadQuery: ObserveDownloadQuery = ObserveDownloadUseCase(
        downloadTaskRepository = downloadTaskRepository
    )

    val pauseDownloadCommand: PauseDownloadCommand = PauseDownloadUseCase(
        domainEventBus = domainEventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val resumeDownloadCommand: ResumeDownloadCommand = ResumeDownloadUseCase(
        domainEventBus = domainEventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val cancelDownloadCommand: CancelDownloadCommand = CancelDownloadUseCase(
        domainEventBus = domainEventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val downloadController = DownloadController(
        loadDownloadTasksCommand = loadDownloadTasksCommand,
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