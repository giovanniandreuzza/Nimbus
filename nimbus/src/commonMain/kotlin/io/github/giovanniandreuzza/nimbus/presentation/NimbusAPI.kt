package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.presentation.IsPresentation
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
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
import io.github.giovanniandreuzza.nimbus.core.domain.errors.EnqueueDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.PauseDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.ResumeDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeFailed

/**
 * This is the main interface of the Nimbus library.
 *
 * @author Giovanni Andreuzza
 */
@IsPresentation
public interface NimbusAPI {

    /**
     * Get the file size.
     *
     * @param request The get file size request.
     * @return [KResult] with [GetFileSizeResponse] if the operation is successful, [GetFileSizeFailed] otherwise.
     */
    public suspend fun getFileSize(
        request: GetFileSizeRequest
    ): KResult<GetFileSizeResponse, GetFileSizeFailed>

    /**
     * Get the download state.
     *
     * @param request The download request.
     * @return [KResult] with the [DownloadTaskDTO] if the operation is successful, [DownloadTaskNotFound] otherwise.
     */
    public suspend fun getDownloadTask(
        request: DownloadRequest
    ): KResult<DownloadTaskDTO, DownloadTaskNotFound>

    /**
     * Get all downloads.
     *
     * @return [KResult] with [GetAllDownloadsResponse] if the operation is successful, [Nothing] otherwise.
     */
    public suspend fun getAllDownloads(): KResult<GetAllDownloadsResponse, Nothing>

    /**
     * Enqueue the download.
     *
     * @param request The download request.
     * @return [KResult] with [DownloadTaskDTO] if the operation is successful, [EnqueueDownloadErrors] otherwise.
     */
    public suspend fun enqueueDownload(
        request: DownloadRequest
    ): KResult<DownloadTaskDTO, EnqueueDownloadErrors>

    /**
     * Start the download.
     *
     * @param request The start download request.
     * @return [KResult] with [StartDownloadResponse] if the operation is successful, [StartDownloadErrors] otherwise.
     */
    public suspend fun startDownload(
        request: StartDownloadRequest
    ): KResult<StartDownloadResponse, StartDownloadErrors>

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
     * @return [KResult] with [PauseDownloadResponse] if the operation is successful, [PauseDownloadErrors] otherwise.
     */
    public suspend fun pauseDownload(
        request: PauseDownloadRequest
    ): KResult<PauseDownloadResponse, PauseDownloadErrors>

    /**
     * Resume the download.
     *
     * @param request The resume download request.
     * @return [KResult] with [ResumeDownloadResponse] if the operation is successful, [ResumeDownloadErrors] otherwise.
     */
    public suspend fun resumeDownload(
        request: ResumeDownloadRequest
    ): KResult<ResumeDownloadResponse, ResumeDownloadErrors>

    /**
     * Cancel the download.
     *
     * @param request The cancel download request.
     * @return [KResult] with [CancelDownloadResponse] if the operation is successful, [DownloadTaskNotFound] otherwise.
     */
    public suspend fun cancelDownload(
        request: CancelDownloadRequest
    ): KResult<CancelDownloadResponse, DownloadTaskNotFound>
}