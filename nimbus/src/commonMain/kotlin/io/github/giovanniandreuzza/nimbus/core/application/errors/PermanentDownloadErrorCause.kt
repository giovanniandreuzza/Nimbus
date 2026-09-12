package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Typed cause for [DownloadError.PermanentError].
 *
 * Every variant in this hierarchy represents a non-recoverable condition: retrying
 * will not resolve the error without external intervention. Callers can exhaustively
 * `when`-match to decide how to surface or handle each case.
 *
 * @author Giovanni Andreuzza
 */
public sealed class PermanentDownloadErrorCause(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {
    /** HTTP 404 — the requested resource does not exist on the server. */
    public data object ResourceNotFound : PermanentDownloadErrorCause(
        code = "resource_not_found",
        message = "The requested resource was not found."
    )

    /**
     * HTTP 4xx client error (excluding 404).
     *
     * @param statusCode The HTTP status code returned by the server.
     */
    public data class ClientError(val statusCode: Int) : PermanentDownloadErrorCause(
        code = "client_error",
        message = "HTTP client error $statusCode."
    )

    /**
     * The server ignored the Range header or responded inconsistently to a resume request,
     * which would corrupt the partially downloaded file if the body were appended.
     *
     * @param reason A human-readable description of the specific inconsistency detected.
     */
    public data class InconsistentRangeResponse(val reason: String) : PermanentDownloadErrorCause(
        code = "inconsistent_range_response",
        message = "Server ignored Range header: $reason."
    )

    /**
     * The local partial file on disk is larger than the expected total download size,
     * indicating corruption.
     */
    public data object LocalFileOversized : PermanentDownloadErrorCause(
        code = "local_file_oversized",
        message = "Local file is larger than the expected download size."
    )

    /**
     * The volume ran out of room while the bytes were being written.
     *
     * Separate from [StorageError] because it is the one storage failure a caller can do
     * something about, and because the headroom check that runs before a transfer starts
     * already reports a shortage as a shortage — a device that fills up part-way deserves
     * the same answer as one that was already full, not a generic failure that sends
     * whoever reads the log looking at permissions.
     *
     * How much was needed and how much was free live in [cause] rather than in fields of
     * their own, for the same reason [StorageError] carries its detail there: the persisted
     * store has somewhere to put a nested error and nowhere to put three new columns. After
     * a restart the numbers are history anyway — what a caller still needs is the reason.
     *
     * @param cause What was being written, how much was left, and what the volume said was free.
     */
    public data class InsufficientDiskSpace(
        override val cause: KError
    ) : PermanentDownloadErrorCause(
        code = "insufficient_disk_space",
        message = "The volume ran out of space.",
        cause = cause
    )

    /**
     * A local storage error (I/O failure, permission denied) prevented the download
     * from proceeding.
     *
     * @param cause The underlying storage error.
     */
    public data class StorageError(override val cause: KError) : PermanentDownloadErrorCause(
        code = "storage_error",
        message = "A local storage error prevented the download.",
        cause = cause
    )

    /**
     * An unhandled exception occurred during the download.
     *
     * @param cause The underlying cause, if available.
     */
    public data class UnexpectedError(override val cause: KError? = null) :
        PermanentDownloadErrorCause(
            code = "unexpected_error",
            message = "An unexpected error occurred.",
            cause = cause
        )
}
