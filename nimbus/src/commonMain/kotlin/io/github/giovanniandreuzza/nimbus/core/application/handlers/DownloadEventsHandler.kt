package io.github.giovanniandreuzza.nimbus.core.application.handlers

import io.github.giovanniandreuzza.nimbus.api.DownloadCallback
import io.github.giovanniandreuzza.nimbus.api.FileCallback
import io.github.giovanniandreuzza.nimbus.core.domain.events.DownloadInfoEvents
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadInfoRepository
import io.github.giovanniandreuzza.nimbus.core.application.usecases.GetFileSizeUseCase
import io.github.giovanniandreuzza.nimbus.shared.ddd.domain.DomainEvent
import io.github.giovanniandreuzza.nimbus.shared.ddd.events.EventBus
import io.github.giovanniandreuzza.nimbus.shared.ddd.events.EventHandler
import kotlinx.coroutines.CoroutineScope

/**
 * Download info initialized event handler.
 *
 * @author Giovanni Andreuzza
 */
internal class DownloadInfoInitializedEventHandler(
    private val scope: CoroutineScope,
    private val downloadInfoRepository: DownloadInfoRepository,
    private val getFileUseCase: GetFileSizeUseCase,
    private val downloadCallback: DownloadCallback,
    private val fileCallback: FileCallback,
    private val eventBus: EventBus<DomainEvent<DownloadId>>
) : EventHandler<DownloadInfoEvents.DownloadInfoInitializedEvent> {

    private companion object {
        const val DEFAULT_BUFFER_SIZE: Long = 8 * 1024
    }

    override fun handle(event: DownloadInfoEvents.DownloadInfoInitializedEvent) {

    }

}