package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.presentation.IsPresentation
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
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError

/**
 * This is the main interface of the Nimbus library.
 *
 * @author Giovanni Andreuzza
 */
@IsPresentation
public interface NimbusAPI {

    /**
     * Check if the download is already downloaded.
     *
     * @param isDownloadedRequest The is downloaded request.
     * @return true if the operation is successful, false otherwise.
     */
    public suspend fun isDownloaded(isDownloadedRequest: IsDownloadedRequest): Boolean

    /**
     * Get the file size.
     *
     * @param request The get file size request.
     * @return [KResult] with [GetFileSizeResponse] if the operation is successful, [GetFileSizeError] otherwise.
     */
    public suspend fun getFileSize(
        request: GetFileSizeRequest
    ): KResult<GetFileSizeResponse, GetFileSizeError>

    /**
     * Get the download state.
     *
     * @param request The download request.
     * @return [KResult] with the [DownloadTaskDTO] if the operation is successful, [DownloadTaskNotFound] otherwise.
     */
    public suspend fun getDownloadTask(
        request: GetDownloadTaskRequest
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound>

    /**
     * Get all downloads.
     *
     * @return [GetAllDownloadsResponse] with all downloads.
     */
    public suspend fun getAllDownloads(): GetAllDownloadsResponse

    /**
     * Enqueue the download.
     *
     * @param request The download request.
     * @return [KResult] with [DownloadTaskDTO] if the operation is successful, [EnqueueDownloadErrors] otherwise.
     */
    public suspend fun enqueueDownload(
        request: EnqueueDownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors>

    /**
     * Start the download.
     *
     * @param request The start download request.
     * @return [KResult] with [Unit] if the operation is successful, [StartDownloadErrors] otherwise.
     */
    public suspend fun startDownload(request: StartDownloadRequest): KResult<Unit, StartDownloadErrors>

    /**
     * Observe the download state.
     *
     * @param request The observe download request.
     * @return [KResult] with [ObserveDownloadResponse] if the operation is successful, [DownloadTaskNotFound] otherwise.
     */
    public suspend fun observeDownload(
        request: ObserveDownloadRequest
    ): KResult<ObserveDownloadResponse, DownloadTaskNotFound>

    /**
     * Pause the download.
     *
     * @param request The pause download request.
     * @return [KResult] with [Unit] if the operation is successful, [PauseDownloadErrors] otherwise.
     */
    public suspend fun pauseDownload(request: PauseDownloadRequest): KResult<Unit, PauseDownloadErrors>

    /**
     * Resume the download.
     *
     * @param request The resume download request.
     * @return [KResult] with [Unit] if the operation is successful, [ResumeDownloadErrors] otherwise.
     */
    public suspend fun resumeDownload(request: ResumeDownloadRequest): KResult<Unit, ResumeDownloadErrors>

    /**
     * Cancel the download.
     *
     * @param request The cancel download request.
     * @return [KResult] with [Unit] if the operation is successful, [DownloadTaskNotFound] otherwise.
     */
    public suspend fun cancelDownload(request: CancelDownloadRequest): KResult<Unit, DownloadTaskNotFound>
}