package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Nimbus Error.
 *
 * Single unified error type for all NimbusAPI operations.
 *
 * Only two variants are exposed:
 * - [TemporaryError] — a transient failure; the operation may be retried.
 *   The exact condition is described by the typed [TemporaryNimbusErrorCause].
 * - [PermanentError] — a non-recoverable failure; retrying will not help.
 *   The exact condition is described by the typed [PermanentNimbusErrorCause].
 *
 * Both variants carry their cause as a typed sealed class so callers can
 * exhaustively `when`-match on every possible inner error.
 *
 * @author Giovanni Andreuzza
 */
public sealed class NimbusError(
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
        val errorCause: TemporaryNimbusErrorCause
    ) : NimbusError(
        code = "temporary_error",
        message = "A temporary error occurred.",
        cause = errorCause
    )

    /**
     * A permanent error that will not resolve on retry.
     *
     * @param errorCause The specific typed cause — exhaustively matchable via `when`.
     */
    public data class PermanentError(
        val errorCause: PermanentNimbusErrorCause
    ) : NimbusError(
        code = "permanent_error",
        message = "A permanent error occurred.",
        cause = errorCause
    )
}
