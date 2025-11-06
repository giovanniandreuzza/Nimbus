package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Resume Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Error cause.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class ResumeDownloadErrors(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message) {

    /**
     * Download Task Not Found.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadTaskNotFound(
        val downloadId: String
    ) : ResumeDownloadErrors(
        code = "download_task_not_found",
        message = "Download Task Not Found. Download ID: $downloadId"
    )

    /**
     * Download Is Not Paused.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadIsNotPaused(
        val downloadId: String
    ) : ResumeDownloadErrors(
        code = "download_is_not_paused",
        message = "Download Is Not Paused. Download ID: $downloadId"
    )

    /**
     * Download Already Resumed.
     *
     * @param downloadId The download ID.
     */
    @IsDomainError
    public data class DownloadAlreadyResumed(
        val downloadId: String
    ) : ResumeDownloadErrors(
        code = "download_already_resumed",
        message = "Download Already Resumed. Download ID: $downloadId"
    )

    /**
     * Resume Download Failed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class ResumeDownloadFailed(
        override val cause: KError
    ) : ResumeDownloadErrors(
        code = "pause_download_failed",
        message = "Pause Download Failed.",
        cause = cause
    )

    /**
     * Resource Not Found.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data object ResourceNotFound : ResumeDownloadErrors(
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
    ) : ResumeDownloadErrors(
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
    ) : ResumeDownloadErrors(
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
    ) : ResumeDownloadErrors(
        code = "unexpected_error",
        message = "An unexpected error occurred.",
        cause = cause
    )
}