package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Typed cause for [GetFileSizeError.PermanentError].
 *
 * Every variant represents a non-recoverable condition. Callers can exhaustively
 * `when`-match to decide how to surface or handle each case.
 *
 * @author Giovanni Andreuzza
 */
public sealed class PermanentGetFileSizeErrorCause(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /** HTTP 404 — the requested resource does not exist on the server. */
    public data object ResourceNotFound : PermanentGetFileSizeErrorCause(
        code = "resource_not_found",
        message = "The requested resource was not found."
    )

    /**
     * HTTP 4xx client error (excluding 404).
     *
     * @param statusCode The HTTP status code returned by the server.
     */
    public data class ClientError(val statusCode: Int) : PermanentGetFileSizeErrorCause(
        code = "client_error",
        message = "HTTP client error $statusCode."
    )

    /**
     * The server did not provide a usable `Content-Length` in either the `HEAD`
     * response or the `Range: bytes=0-0` probe, so the total file size cannot be
     * determined.
     */
    public data object FileSizeUnavailable : PermanentGetFileSizeErrorCause(
        code = "file_size_unavailable",
        message = "Could not determine the remote file size."
    )

    /**
     * An unhandled exception occurred while retrieving the file size.
     *
     * @param cause The underlying cause, if available.
     */
    public data class UnexpectedError(
        override val cause: KError? = null
    ) : PermanentGetFileSizeErrorCause(
        code = "unexpected_error",
        message = "An unexpected error occurred while retrieving the file size.",
        cause = cause
    )
}

