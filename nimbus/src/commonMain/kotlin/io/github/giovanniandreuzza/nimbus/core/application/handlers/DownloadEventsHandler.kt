package io.github.giovanniandreuzza.nimbus.core.application.handlers

import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventHandler
import io.github.giovanniandreuzza.nimbus.core.domain.events.DownloadTaskEvents

/**
 * Download events handler.
 *
 * @author Giovanni Andreuzza
 */
internal class DownloadEventsHandler : EventHandler<DownloadTaskEvents> {
    override fun handle(event: DownloadTaskEvents) {
        when (event) {
            is DownloadTaskEvents.DownloadEnqueuedEvent -> println("Download enqueued - $event")
            is DownloadTaskEvents.DownloadStartedEvent -> println("Download started - $event")
            is DownloadTaskEvents.DownloadPausedEvent -> println("Download paused - $event")
            is DownloadTaskEvents.DownloadResumedEvent -> println("Download resumed - $event")
            is DownloadTaskEvents.DownloadFailedEvent -> println("Download failed - $event")
            is DownloadTaskEvents.DownloadCanceledEvent -> println("Download canceled - $event")
            is DownloadTaskEvents.DownloadFinishedEvent -> println("Download finished - $event")
        }
    }
}