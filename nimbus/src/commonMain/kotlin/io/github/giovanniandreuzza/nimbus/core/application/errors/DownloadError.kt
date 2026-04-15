package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Download Error.
 *
 * Only two variants are exposed:
 * - [TemporaryError] — a transient failure; the download may be retried.
 *   The exact condition is described by the typed [TemporaryDownloadErrorCause].
 * - [PermanentError] — a non-recoverable failure; retrying will not help.
 *   The exact condition is described by the typed [PermanentDownloadErrorCause].
 *
 * Both variants carry their cause as a typed sealed class so callers can
 * exhaustively `when`-match on every possible inner error.
 *
 * @param code the error code
 * @param message the error message
 * @param cause the error cause (typed as [TemporaryDownloadErrorCause] or [PermanentDownloadErrorCause])
 * @author Giovanni Andreuzza
 */
public sealed class DownloadError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /**
     * A transient error that may resolve on retry.
     *
     * @param errorCause The specific typed cause — exhaustively matchable via `when`.
     */
    public data class TemporaryError(
        val errorCause: TemporaryDownloadErrorCause
    ) : DownloadError(
        code = "TEMPORARY_ERROR",
        message = "A temporary error occurred while downloading.",
        cause = errorCause
    )

    /**
     * A permanent error that will not resolve on retry.
     *
     * @param errorCause The specific typed cause — exhaustively matchable via `when`.
     */
    public data class PermanentError(
        val errorCause: PermanentDownloadErrorCause
    ) : DownloadError(
        code = "PERMANENT_ERROR",
        message = "A permanent error occurred while downloading.",
        cause = errorCause
    )
}