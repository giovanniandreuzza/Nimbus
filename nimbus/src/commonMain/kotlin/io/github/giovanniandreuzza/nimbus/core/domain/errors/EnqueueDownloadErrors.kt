package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest

/**
 * Enqueue Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class EnqueueDownloadErrors(
    override val code: String,
    override val message: String
) : KError(code, message) {

    /**
     * Download Already Enqueued.
     *
     * @param downloadRequest The download request.
     */
    @IsDomainError
    public data class DownloadAlreadyEnqueued(
        val downloadRequest: DownloadRequest
    ) : EnqueueDownloadErrors(
        code = "download_already_enqueued",
        message = "Download Already Enqueued. Download Request: $downloadRequest"
    )

    /**
     * Download Already Started.
     *
     * @param downloadRequest The download request.
     */
    @IsDomainError
    public data class DownloadAlreadyStarted(
        val downloadRequest: DownloadRequest
    ) : EnqueueDownloadErrors(
        code = "download_already_started",
        message = "Download Already Started. Download Request: $downloadRequest"
    )

    /**
     * Download Is Paused.
     *
     * @param downloadRequest The download request.
     */
    @IsDomainError
    public data class DownloadIsPaused(
        val downloadRequest: DownloadRequest
    ) : EnqueueDownloadErrors(
        code = "download_is_paused",
        message = "Download Is Paused. Download Request: $downloadRequest"
    )

    /**
     * Download Failed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class DownloadFailed(
        override val cause: String
    ) : EnqueueDownloadErrors(
        code = "download_failed",
        message = "Download Failed."
    )

    /**
     * Download Already Completed.
     *
     * @param downloadRequest The download request.
     */
    @IsDomainError
    public data class DownloadAlreadyCompleted(
        val downloadRequest: DownloadRequest
    ) : EnqueueDownloadErrors(
        code = "download_already_completed",
        message = "Download Already Completed. Download Request: $downloadRequest"
    )

    /**
     * Get File Size Failed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class GetFileSizeFailed(override val cause: String) : EnqueueDownloadErrors(
        code = "get_file_size_failed",
        message = "Get File Size Failed."
    )
}