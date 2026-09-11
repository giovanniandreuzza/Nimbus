package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Typed cause for [DownloadError.TemporaryError].
 *
 * Every variant in this hierarchy represents a transient condition that is
 * expected to resolve on retry. Callers can exhaustively `when`-match to decide
 * how to surface or handle each case.
 *
 * @author Giovanni Andreuzza
 */
public sealed class TemporaryDownloadErrorCause(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /**
     * The remote server responded with an HTTP 5xx status code.
     *
     * @param statusCode The HTTP status code returned by the server.
     */
    public data class ServerError(val statusCode: Int) : TemporaryDownloadErrorCause(
        code = "server_error",
        message = "HTTP server error $statusCode."
    )

    /**
     * HTTP 416 — the server rejected the `Range` header (e.g. local file is longer than
     * the remote resource). The adapter will truncate the local file and restart from byte 0.
     */
    public data object RangeNotSatisfiable : TemporaryDownloadErrorCause(
        code = "range_not_satisfiable",
        message = "The server rejected the byte range (HTTP 416)."
    )

    /**
     * The downloaded file size does not match the expected total size.
     * The download will be retried.
     */
    public data object FileIntegrityMismatch : TemporaryDownloadErrorCause(
        code = "file_integrity_mismatch",
        message = "File size mismatch after download."
    )

    /**
     * The local file could not be opened for writing (e.g. it was deleted between the
     * existence check and the sink-open call). The download will be retried.
     */
    public data object FileNotAccessible : TemporaryDownloadErrorCause(
        code = "file_not_accessible",
        message = "The download file could not be opened for writing."
    )

    /**
     * A race condition prevented the file from being recreated after the HTTP 416 truncation.
     * The download will be retried.
     */
    public data object TruncateRace : TemporaryDownloadErrorCause(
        code = "truncate_race",
        message = "Could not recreate the file after HTTP 416 truncation."
    )

    /**
     * The transferred bytes did not hash to the checksum the caller supplied.
     * The download will be retried.
     *
     * Temporary, never permanent. A mismatch is a statement about this transfer — a
     * corrupted proxy response, a truncated body a correct `Content-Length` hid, a cache
     * serving something stale — not about the file at the origin. There is no checksum
     * failure that is a property of the URL rather than of the attempt.
     */
    public data object ChecksumMismatch : TemporaryDownloadErrorCause(
        code = "checksum_mismatch",
        message = "The downloaded bytes did not match the expected checksum."
    )
}

