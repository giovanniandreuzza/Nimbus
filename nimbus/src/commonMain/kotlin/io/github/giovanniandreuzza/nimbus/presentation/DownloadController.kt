package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetDownloadTaskRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.EnqueueDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetAllDownloadsResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.IsDownloadedRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.commands.CancelDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.EnqueueDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.PauseDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.ResumeDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.commands.StartDownloadCommand
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.commands.LoadDownloadTasksCommand
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery
import io.github.giovanniandreuzza.nimbus.core.queries.IsDownloadedQuery
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery

/**
 * Download Controller.
 *
 * @param loadDownloadTasksCommand The load download tasks command.
 * @param isDownloadedQuery The is downloaded query.
 * @param getDownloadTaskQuery The get download state query.
 * @param getAllDownloadsQuery The get all downloads query.
 * @param getFileSizeQuery The get file size query.
 * @param enqueueDownloadCommand The enqueue download command.
 * @param startDownloadCommand The start download command.
 * @param observeDownloadQuery The observe download query.
 * @param pauseDownloadCommand The pause download command.
 * @param resumeDownloadCommand The resume download command.
 * @param cancelDownloadCommand The cancel download command.
 * @author Giovanni Andreuzza
 */
internal class DownloadController(
    private val loadDownloadTasksCommand: LoadDownloadTasksCommand,
    private val isDownloadedQuery: IsDownloadedQuery,
    private val getDownloadTaskQuery: GetDownloadTaskQuery,
    private val getAllDownloadsQuery: GetAllDownloadsQuery,
    private val getFileSizeQuery: GetFileSizeQuery,
    private val enqueueDownloadCommand: EnqueueDownloadCommand,
    private val startDownloadCommand: StartDownloadCommand,
    private val observeDownloadQuery: ObserveDownloadQuery,
    private val pauseDownloadCommand: PauseDownloadCommand,
    private val resumeDownloadCommand: ResumeDownloadCommand,
    private val cancelDownloadCommand: CancelDownloadCommand
) : NimbusAPI {

    suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        return loadDownloadTasksCommand(Unit)
    }

    override suspend fun isDownloaded(isDownloadedRequest: IsDownloadedRequest): Boolean {
        return isDownloadedQuery(isDownloadedRequest)
    }

    override suspend fun getFileSize(
        request: GetFileSizeRequest
    ): KResult<GetFileSizeResponse, GetFileSizeError> {
        return getFileSizeQuery(request)
    }

    override suspend fun getDownloadTask(
        request: GetDownloadTaskRequest
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        return getDownloadTaskQuery(request)
    }

    override suspend fun getAllDownloads(): GetAllDownloadsResponse {
        return getAllDownloadsQuery(Unit)
    }

    override suspend fun enqueueDownload(
        request: EnqueueDownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors> {
        return enqueueDownloadCommand(request)
    }

    override suspend fun startDownload(
        request: StartDownloadRequest
    ): KResult<Unit, StartDownloadErrors> {
        return startDownloadCommand(request)
    }

    override suspend fun observeDownload(
        request: ObserveDownloadRequest
    ): KResult<ObserveDownloadResponse, DownloadTaskNotFound> {
        return observeDownloadQuery(request)
    }

    override suspend fun pauseDownload(
        request: PauseDownloadRequest
    ): KResult<Unit, PauseDownloadErrors> {
        return pauseDownloadCommand(request)
    }

    override suspend fun resumeDownload(
        request: ResumeDownloadRequest
    ): KResult<Unit, ResumeDownloadErrors> {
        return resumeDownloadCommand(request)
    }

    override suspend fun cancelDownload(
        request: CancelDownloadRequest
    ): KResult<Unit, DownloadTaskNotFound> {
        return cancelDownloadCommand(request)
    }
}