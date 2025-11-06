package io.github.giovanniandreuzza.nimbus.core.domain.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.errors.IsDomainError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Enqueue Download Errors.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Error cause.
 * @author Giovanni Andreuzza
 */
@IsDomainError
public sealed class EnqueueDownloadErrors(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /**
     * Download Already Enqueued.
     */
    @IsDomainError
    public data object DownloadAlreadyEnqueued : EnqueueDownloadErrors(
        code = "download_already_enqueued",
        message = "Download Already Enqueued."
    )

    /**
     * Download Already Started.
     */
    @IsDomainError
    public data object DownloadAlreadyStarted : EnqueueDownloadErrors(
        code = "download_already_started",
        message = "Download Already Started."
    )

    /**
     * Download Is Paused.
     */
    @IsDomainError
    public data object DownloadIsPaused : EnqueueDownloadErrors(
        code = "download_is_paused",
        message = "Download Is Paused."
    )

    /**
     * Download Failed.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class DownloadFailed(
        override val cause: KError
    ) : EnqueueDownloadErrors(
        code = "download_failed",
        message = "Download Failed.",
        cause = cause
    )

    /**
     * Download Already Completed.
     */
    @IsDomainError
    public data object DownloadAlreadyCompleted : EnqueueDownloadErrors(
        code = "download_already_completed",
        message = "Download Already Completed."
    )

    /**
     * Connection Error.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data class ConnectionError(
        override val cause: KError? = null
    ) : EnqueueDownloadErrors(
        code = "connection_error",
        message = "Connection Error.",
        cause = cause
    )

    /**
     * Resource Not Found.
     *
     * @param cause The cause.
     */
    @IsDomainError
    public data object ResourceNotFound : EnqueueDownloadErrors(
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
    ) : EnqueueDownloadErrors(
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
    ) : EnqueueDownloadErrors(
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
    ) : EnqueueDownloadErrors(
        code = "unexpected_error",
        message = "An unexpected error occurred.",
        cause = cause
    )
}