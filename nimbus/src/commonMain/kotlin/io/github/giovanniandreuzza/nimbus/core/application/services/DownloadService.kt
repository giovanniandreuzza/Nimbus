package io.github.giovanniandreuzza.nimbus.core.application.services

import io.github.giovanniandreuzza.explicitarchitecture.application.Application
import io.github.giovanniandreuzza.explicitarchitecture.shared.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.Event
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventBus
import io.github.giovanniandreuzza.explicitarchitecture.shared.events.EventHandler
import io.github.giovanniandreuzza.explicitarchitecture.shared.isFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.Companion.toDomain
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.events.DownloadTaskEvents
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadCallback
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery
import io.github.giovanniandreuzza.nimbus.shared.utils.takeUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Download Service.
 *
 * @param eventBus The event bus.
 * @param downloadScope The download scope.
 * @param downloadTaskRepository The download task repository.
 * @author Giovanni Andreuzza
 */
internal class DownloadService(
    private val eventBus: EventBus<Event>,
    private val downloadScope: CoroutineScope,
    private val downloadTaskRepository: DownloadTaskRepository,
) : ObserveDownloadQuery, DownloadCallback, Application {

    private val downloadFlows = mutableMapOf<DownloadId, MutableStateFlow<DownloadState>>()

    init {
        val eventHandler = object : EventHandler<DownloadTaskEvents> {
            override fun handle(event: DownloadTaskEvents) {
                val downloadId = event.aggregateId.id

                when (event) {
                    is DownloadTaskEvents.DownloadEnqueuedEvent -> {
                        downloadFlows.getOrPut(downloadId) {
                            MutableStateFlow(DownloadState.Enqueued)
                        }.update {
                            DownloadState.Enqueued
                        }
                    }

                    is DownloadTaskEvents.DownloadStartedEvent -> {
                        downloadFlows.getOrPut(downloadId) {
                            MutableStateFlow(DownloadState.Downloading(progress = 0.0))
                        }.update {
                            DownloadState.Downloading(progress = 0.0)
                        }
                    }

                    is DownloadTaskEvents.DownloadPausedEvent -> {
                        downloadFlows.getOrPut(downloadId) {
                            MutableStateFlow(DownloadState.Paused(progress = event.progress))
                        }.update {
                            DownloadState.Paused(progress = event.progress)
                        }
                    }

                    is DownloadTaskEvents.DownloadResumedEvent -> {
                        downloadFlows.getOrPut(downloadId) {
                            MutableStateFlow(DownloadState.Downloading(progress = event.progress))
                        }.update {
                            DownloadState.Downloading(progress = event.progress)
                        }
                    }

                    is DownloadTaskEvents.DownloadFailedEvent -> {
                        downloadFlows.getOrPut(downloadId) {
                            MutableStateFlow(
                                DownloadState.Failed(
                                    errorCode = event.errorCode,
                                    errorMessage = event.errorMessage
                                )
                            )
                        }.update {
                            DownloadState.Failed(
                                errorCode = event.errorCode,
                                errorMessage = event.errorMessage
                            )
                        }
                    }

                    is DownloadTaskEvents.DownloadFinishedEvent -> {
                        downloadFlows.getOrPut(downloadId) {
                            MutableStateFlow(DownloadState.Finished)
                        }.update {
                            DownloadState.Finished
                        }
                    }

                    is DownloadTaskEvents.DownloadCanceledEvent -> {
                        downloadFlows.remove(downloadId)
                    }
                }
            }
        }

        eventBus.registerHandler(DownloadTaskEvents::class, eventHandler)
    }

    override suspend fun execute(
        params: ObserveDownloadRequest
    ): KResult<ObserveDownloadResponse, DownloadTaskNotFound> {
        val downloadId = DownloadId.create(params.downloadId)

        val downloadTaskResult = downloadTaskRepository.getDownloadTask(downloadId.value)

        if (downloadTaskResult.isFailure()) {
            return Failure(DownloadTaskNotFound(downloadId.value))
        }

        val downloadFlow = downloadFlows[downloadId]?.takeUntil {
            !downloadFlows.containsKey(downloadId) || it is DownloadState.Finished || it is DownloadState.Failed
        } ?: return Failure(DownloadTaskNotFound(downloadId.value))

        val observeDownloadResponse = ObserveDownloadResponse(downloadFlow)

        return Success(observeDownloadResponse)
    }

    override suspend fun onDownloadProgress(id: String, progress: Double) {
        val downloadId = DownloadId.create(id)
        downloadFlows[downloadId]?.update {
            DownloadState.Downloading(progress)
        }
    }

    override fun onDownloadFailed(
        id: String,
        error: StartDownloadErrors
    ) {
        downloadScope.launch {
            val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

            if (downloadTaskResult.isFailure()) {
                return@launch
            }

            val downloadTask = downloadTaskResult.value.toDomain()

            downloadTask.fail(error.code, error.message)

            downloadTaskRepository.saveDownloadTask(DownloadTaskDTO.fromDomain(downloadTask))

            eventBus.publishAll(downloadTask.dequeueEvents())
        }
    }

    override fun onDownloadFinished(id: String) {
        downloadScope.launch {
            val downloadTaskResult = downloadTaskRepository.getDownloadTask(id)

            if (downloadTaskResult.isFailure()) {
                return@launch
            }

            val downloadTask = downloadTaskResult.value.toDomain()

            downloadTask.finish()

            downloadTaskRepository.saveDownloadTask(DownloadTaskDTO.fromDomain(downloadTask))

            eventBus.publishAll(downloadTask.dequeueEvents())
        }
    }

}