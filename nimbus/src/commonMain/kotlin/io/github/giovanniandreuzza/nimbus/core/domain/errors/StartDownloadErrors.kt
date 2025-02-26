package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Start Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class StartDownloadErrors(
    override val code: String,
    override val message: String
) : KError(code, message) {

    /**
     * Download Task Not Found.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadTaskNotFound(
        val downloadId: String
    ) : StartDownloadErrors(
        code = "download_task_not_found",
        message = "Download Task Not Found. Download ID: $downloadId"
    )

    /**
     * Download Is Not Enqueued.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadIsNotEnqueued(
        val downloadId: String
    ) : StartDownloadErrors(
        code = "download_is_not_enqueued",
        message = "Download Is Not Enqueued. Download ID: $downloadId"
    )

    /**
     * Download Already Started.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadAlreadyStarted(
        val downloadId: String
    ) : StartDownloadErrors(
        code = "download_already_started",
        message = "Download Already Started. Download ID: $downloadId"
    )

    /**
     * Download Already Resumed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class StartDownloadFailed(
        override val cause: String
    ) : StartDownloadErrors(
        code = "download_failed",
        message = "Download Failed."
    )
}