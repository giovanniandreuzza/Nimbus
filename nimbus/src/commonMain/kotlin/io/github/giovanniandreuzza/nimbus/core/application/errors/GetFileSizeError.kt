package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Get File Size Error.
 *
 * @param cause cause.
 * @author Giovanni Andreuzza
 */
public sealed class GetFileSizeError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code,
    message,
    cause
) {

    public data object ResourceNotFound : GetFileSizeError(
        code = "RESOURCE_NOT_FOUND",
        message = "The requested resource was not found."
    )

    public data class TemporaryError(
        override val cause: KError? = null
    ) : GetFileSizeError(
        code = "TEMPORARY_ERROR",
        message = "A temporary error occurred while getting file size.",
        cause = cause
    )

    public data class PermanentError(
        override val cause: KError? = null
    ) : GetFileSizeError(
        code = "PERMANENT_ERROR",
        message = "A permanent error occurred while getting file size.",
        cause = cause
    )

    public data class UnexpectedError(
        override val cause: KError? = null
    ) : GetFileSizeError(
        code = "UNEXPECTED_ERROR",
        message = "An unexpected error occurred.",
        cause = cause
    )
}