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
     * Whether trying again could work.
     *
     * The same distinction the two variants make, as a boolean, because most callers only
     * need that much: report and move on, or schedule another attempt. With `autoStart` the
     * library is already scheduling one.
     */
    public val isRetryable: Boolean
        get() = this is TemporaryError

    /**
     * The most specific code for what went wrong — `insufficient_disk_space`,
     * `resource_not_found`, `path_outside_download_root`.
     *
     * Distinct from [code], which every `KError` carries and which here says only
     * `temporary_error` or `permanent_error`: the variant, not the reason.
     *
     * The typed causes nest: a download failure arrives as `PermanentError` →
     * `DownloadFailed` → the download's own cause, and matching all three exhaustively is a
     * `when` inside a `when` inside a `when`. Callers who want that precision have it; this is
     * for the ones who want to log a stable string or key a message off it, and it unwraps the
     * two causes that exist only to carry another.
     *
     * A cause's own `cause` is not followed: for `StorageError` that is the platform's I/O
     * detail, and `io_error` is not what a caller branches on.
     */
    public val causeCode: String
        get() = when (this) {
            is TemporaryError -> when (val cause = errorCause) {
                is TemporaryNimbusErrorCause.DownloadFailed -> cause.errorCause.code
                is TemporaryNimbusErrorCause.GetFileSizeFailed -> cause.errorCause.code
            }

            is PermanentError -> when (val cause = errorCause) {
                is PermanentNimbusErrorCause.DownloadFailed -> cause.errorCause.code
                is PermanentNimbusErrorCause.GetFileSizeFailed -> cause.errorCause.code
                else -> cause.code
            }
        }

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
