package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Get File Size Error.
 *
 * Only two variants are exposed:
 * - [TemporaryError] — a transient failure; the request may be retried.
 *   The exact condition is described by the typed [TemporaryGetFileSizeErrorCause].
 * - [PermanentError] — a non-recoverable failure; retrying will not help.
 *   The exact condition is described by the typed [PermanentGetFileSizeErrorCause].
 *
 * @author Giovanni Andreuzza
 */
public sealed class GetFileSizeError(
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
        val errorCause: TemporaryGetFileSizeErrorCause
    ) : GetFileSizeError(
        code = "TEMPORARY_ERROR",
        message = "A temporary error occurred while getting file size.",
        cause = errorCause
    )

    /**
     * A permanent error that will not resolve on retry.
     *
     * @param errorCause The specific typed cause — exhaustively matchable via `when`.
     */
    public data class PermanentError(
        val errorCause: PermanentGetFileSizeErrorCause
    ) : GetFileSizeError(
        code = "PERMANENT_ERROR",
        message = "A permanent error occurred while getting file size.",
        cause = errorCause
    )
}