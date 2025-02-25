package io.github.giovanniandreuzza.nimbus.di

import io.github.giovanniandreuzza.explicitarchitecture.infrastructure.EventBusAdapter
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.Event
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.nimbus.api.NimbusAPI
import io.github.giovanniandreuzza.nimbus.api.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.api.NimbusFileRepository
import io.github.giovanniandreuzza.nimbus.core.application.handlers.DownloadEventsHandler
import io.github.giovanniandreuzza.nimbus.core.application.services.DownloadService
import io.github.giovanniandreuzza.nimbus.core.application.usecases.CancelDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.EnqueueDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetAllDownloadsUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetDownloadTaskUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetFileSizeUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.PauseDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.ResumeDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.application.usecases.StartDownloadUseCase
import io.github.giovanniandreuzza.nimbus.core.commands.CancelDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.EnqueueDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.PauseDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.ResumeDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.StartDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.events.DownloadTaskEvents
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery
import io.github.giovanniandreuzza.nimbus.infrastructure.DownloadTaskAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.OkioDownloadAdapter
import io.github.giovanniandreuzza.nimbus.presentation.DownloadController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Initialize the Nimbus API.
 *
 * @param eventBusScope The event bus scope.
 * @param eventBusOnError The event bus on error.
 * @param downloadScope The download scope.
 * @param ioDispatcher The IO dispatcher.
 * @param concurrencyLimit The concurrency limit.
 * @param nimbusDownloadRepository The nimbus download repository.
 * @param nimbusFileRepository The nimbus file repository.
 * @param downloadManagerPath The download manager path.
 * @return [NimbusAPI] The Nimbus API.
 * @author Giovanni Andreuzza
 */
internal fun init(
    eventBusScope: CoroutineScope,
    eventBusOnError: (Throwable) -> Unit,
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadRepository: NimbusDownloadRepository,
    nimbusFileRepository: NimbusFileRepository,
    downloadManagerPath: String
): NimbusAPI {
    val eventBus: EventBus<Event> = EventBusAdapter(
        eventBusScope = eventBusScope
    )
    eventBus.registerHandler(
        eventType = DownloadTaskEvents::class,
        handler = DownloadEventsHandler()
    )
    eventBus.start(onError = eventBusOnError)

    val downloadTaskRepository: DownloadTaskRepository = DownloadTaskAdapter(
        ioDispatcher = ioDispatcher,
        downloadManagerPath = downloadManagerPath,
        nimbusFileRepository = nimbusFileRepository
    )

    val downloadService = DownloadService(
        eventBus = eventBus,
        downloadScope = downloadScope,
        downloadTaskRepository = downloadTaskRepository
    )

    val downloadRepository: DownloadRepository = OkioDownloadAdapter(
        concurrencyLimit = concurrencyLimit,
        downloadScope = downloadScope,
        ioDispatcher = ioDispatcher,
        downloadCallback = downloadService as DownloadCallback,
        nimbusFileRepository = nimbusFileRepository,
        nimbusDownloadRepository = nimbusDownloadRepository
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
        nimbusDownloadRepository = nimbusDownloadRepository
    )

    val enqueueDownloadCommand: EnqueueDownloadCommand = EnqueueDownloadUseCase(
        eventBus = eventBus,
        getFileSizeQuery = getFileSizeQuery,
        downloadTaskRepository = downloadTaskRepository
    )

    val startDownloadCommand: StartDownloadCommand = StartDownloadUseCase(
        eventBus = eventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val pauseDownloadCommand: PauseDownloadCommand = PauseDownloadUseCase(
        eventBus = eventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val resumeDownloadCommand: ResumeDownloadCommand = ResumeDownloadUseCase(
        eventBus = eventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val cancelDownloadCommand: CancelDownloadCommand = CancelDownloadUseCase(
        eventBus = eventBus,
        downloadRepository = downloadRepository,
        downloadTaskRepository = downloadTaskRepository
    )

    val nimbusAPI = DownloadController(
        getDownloadTaskQuery = getDownloadTaskQuery,
        getAllDownloadsQuery = getAllDownloadsQuery,
        getFileSizeQuery = getFileSizeQuery,
        enqueueDownloadCommand = enqueueDownloadCommand,
        startDownloadCommand = startDownloadCommand,
        observeDownloadQuery = downloadService as ObserveDownloadQuery,
        pauseDownloadCommand = pauseDownloadCommand,
        resumeDownloadCommand = resumeDownloadCommand,
        cancelDownloadCommand = cancelDownloadCommand
    )

    return nimbusAPI
}