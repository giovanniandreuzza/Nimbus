package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Download Error.
 *
 * @param code the error code
 * @param message the error message
 * @param cause the error cause
 * @author Giovanni Andreuzza
 */
public sealed class DownloadError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    public data object ResourceNotFound : DownloadError(
        code = "RESOURCE_NOT_FOUND",
        message = "The requested resource was not found."
    )

    public data class TemporaryError(
        override val cause: KError? = null
    ) : DownloadError(
        code = "TEMPORARY_ERROR",
        message = "A temporary error occurred while getting file size.",
        cause = cause
    )

    public data class PermanentError(
        override val cause: KError? = null
    ) : DownloadError(
        code = "PERMANENT_ERROR",
        message = "A permanent error occurred while getting file size.",
        cause = cause
    )

    public data class UnexpectedError(
        override val cause: KError? = null
    ) : DownloadError(
        code = "UNEXPECTED_ERROR",
        message = "An unexpected error occurred while downloading file.",
        cause = cause
    )
}