package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Typed cause for [GetFileSizeError.TemporaryError].
 *
 * Every variant represents a transient condition that may resolve on retry.
 * Callers can exhaustively `when`-match to decide how to surface each case.
 *
 * @author Giovanni Andreuzza
 */
public sealed class TemporaryGetFileSizeErrorCause(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /**
     * The remote server responded with an HTTP 5xx status code.
     *
     * @param statusCode The HTTP status code returned by the server.
     */
    public data class ServerError(val statusCode: Int) : TemporaryGetFileSizeErrorCause(
        code = "server_error",
        message = "HTTP server error $statusCode."
    )

    /**
     * The transport timed out while asking the remote how large the file is. See
     * [TemporaryDownloadErrorCause.TransportFailure] for why this is never permanent.
     *
     * @param cause The underlying transport error.
     */
    public data class TransportFailure(override val cause: KError) : TemporaryGetFileSizeErrorCause(
        code = "transport_failure",
        message = cause.message,
        cause = cause
    )
}

