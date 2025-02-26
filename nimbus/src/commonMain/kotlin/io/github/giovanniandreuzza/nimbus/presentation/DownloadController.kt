package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Empty
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.api.NimbusAPI
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetAllDownloadsResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetFileSizeResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadResponse
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadResponse
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
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.nimbus.core.commands.LoadDownloadTasksCommand
import io.github.giovanniandreuzza.nimbus.core.queries.GetAllDownloadsQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetDownloadTaskQuery
import io.github.giovanniandreuzza.nimbus.core.queries.GetFileSizeQuery
import io.github.giovanniandreuzza.nimbus.core.queries.ObserveDownloadQuery

/**
 * Download Controller.
 *
 * @param loadDownloadTasksCommand The load download tasks command.
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

    suspend fun loadDownloadTasks(): KResult<Empty, FailedToLoadDownloadTasks> {
        return loadDownloadTasksCommand.execute(Empty())
    }

    override suspend fun getFileSize(
        request: GetFileSizeRequest
    ): KResult<GetFileSizeResponse, GetFileSizeFailed> {
        return getFileSizeQuery.execute(request)
    }

    override suspend fun getDownloadTask(
        request: DownloadRequest
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound> {
        return getDownloadTaskQuery.execute(request)
    }

    override suspend fun getAllDownloads(): KResult<GetAllDownloadsResponse, Nothing> {
        return getAllDownloadsQuery.execute(Empty())
    }

    override suspend fun enqueueDownload(
        request: DownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors> {
        return enqueueDownloadCommand.execute(request)
    }

    override suspend fun startDownload(
        request: StartDownloadRequest
    ): KResult<StartDownloadResponse, StartDownloadErrors> {
        return startDownloadCommand.execute(request)
    }

    override suspend fun observeDownload(
        request: ObserveDownloadRequest
    ): KResult<ObserveDownloadResponse, DownloadTaskNotFound> {
        return observeDownloadQuery.execute(request)
    }

    override suspend fun pauseDownload(
        request: PauseDownloadRequest
    ): KResult<PauseDownloadResponse, PauseDownloadErrors> {
        return pauseDownloadCommand.execute(request)
    }

    override suspend fun resumeDownload(
        request: ResumeDownloadRequest
    ): KResult<ResumeDownloadResponse, ResumeDownloadErrors> {
        return resumeDownloadCommand.execute(request)
    }

    override suspend fun cancelDownload(
        request: CancelDownloadRequest
    ): KResult<CancelDownloadResponse, DownloadTaskNotFound> {
        return cancelDownloadCommand.execute(request)
    }
}