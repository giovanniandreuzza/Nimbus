package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Start Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Error cause.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class StartDownloadErrors(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

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
     * Download Is Paused.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadIsPaused(
        val downloadId: String
    ) : StartDownloadErrors(
        code = "download_is_paused",
        message = "Download Is Paused. Download ID: $downloadId"
    )

    /**
     * Download Already Failed.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadAlreadyFailed(
        val downloadId: String
    ) : StartDownloadErrors(
        code = "download_already_failed",
        message = "Download Already Failed. Download ID: $downloadId"
    )

    /**
     * Download Already Finished.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadAlreadyFinished(
        val downloadId: String
    ) : StartDownloadErrors(
        code = "download_already_finished",
        message = "Download Already Finished. Download ID: $downloadId"
    )

    /**
     * Download Already Resumed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class StartDownloadFailed(
        override val cause: KError
    ) : StartDownloadErrors(
        code = "download_failed",
        message = "Download Failed.",
        cause = cause
    )

    /**
     * Resource Not Found.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data object ResourceNotFound : StartDownloadErrors(
        code = "resource_not_found",
        message = "Resource Not Found."
    )

    /**
     * Temporary Error.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class TemporaryError(
        override val cause: KError? = null
    ) : StartDownloadErrors(
        code = "temporary_error",
        message = "Temporary Error.",
        cause = cause
    )

    /**
     * Permanent Error.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class PermanentError(
        override val cause: KError? = null
    ) : StartDownloadErrors(
        code = "permanent_error",
        message = "Permanent Error.",
        cause = cause
    )

    /**
     * Unexpected Error.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class UnexpectedError(
        override val cause: KError? = null
    ) : StartDownloadErrors(
        code = "unexpected_error",
        message = "An unexpected error occurred while getting file size.",
        cause = cause
    )
}