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

    /**
     * HTTP 416 — requested Range is not satisfiable (e.g. local file longer than resource).
     * The library may truncate the local file and restart from offset 0 once.
     */
    public data object RangeNotSatisfiable : DownloadError(
        code = "RANGE_NOT_SATISFIABLE",
        message = "The server rejected the byte range (HTTP 416)."
    )

    /**
     * Server returned a full response (e.g. 200) while a resume offset was requested,
     * which would corrupt the file if appended. Request rejected.
     */
    public data class InconsistentRangeResponse(
        override val cause: KError? = null
    ) : DownloadError(
        code = "INCONSISTENT_RANGE_RESPONSE",
        message = "Server ignored Range and returned a full-body response during resume.",
        cause = cause
    )

    /**
     * Local partial file exists but its length could not be read (storage error).
     */
    public data class LocalFileStateUnreadable(
        override val cause: KError? = null
    ) : DownloadError(
        code = "LOCAL_FILE_STATE_UNREADABLE",
        message = "Could not read local file size for resume.",
        cause = cause
    )

    /**
     * Local file on disk is larger than the expected download size.
     */
    public data class LocalFileOversized(
        override val cause: KError? = null
    ) : DownloadError(
        code = "LOCAL_FILE_OVERSIZED",
        message = "Local file is larger than the expected download size.",
        cause = cause
    )
}