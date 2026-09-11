package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentGetFileSizeErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState

/**
 * Typed cause for [NimbusError.PermanentError].
 *
 * Every variant represents a non-recoverable condition. Callers can exhaustively
 * `when`-match to decide how to surface or handle each case.
 *
 * @author Giovanni Andreuzza
 */
public sealed class PermanentNimbusErrorCause(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /** The supplied file path contains a path-traversal component (`..`). */
    public data object InvalidPath : PermanentNimbusErrorCause(
        code = "invalid_path",
        message = "File path must not contain path-traversal components."
    )

    /** The supplied URL is not a supported network URL (`http`/`https`). */
    public data object InvalidUrl : PermanentNimbusErrorCause(
        code = "invalid_url",
        message = "File URL must use http or https."
    )

    /** The supplied file name is invalid or unsafe for filesystem usage. */
    public data object InvalidFileName : PermanentNimbusErrorCause(
        code = "invalid_file_name",
        message = "File name is invalid."
    )

    /**
     * The remote file metadata contains an invalid file size.
     *
     * @param cause The underlying cause, if available.
     */
    public data class InvalidFileSize(
        override val cause: KError? = null
    ) : PermanentNimbusErrorCause(
        code = "invalid_file_size",
        message = "Remote file size is invalid.",
        cause = cause
    )

    /**
     * A content digest was asked for, but none is configured.
     *
     * Its own cause rather than the catch-all: this is foreseeable, it is entirely the
     * caller's to fix — call
     * [Nimbus.Builder.withContentDigest][io.github.giovanniandreuzza.nimbus.Nimbus.Builder.withContentDigest]
     * — and a caller branching on it should not have to read a string out of an unexpected
     * failure to tell it apart from one.
     */
    public data object ContentDigestDisabled : PermanentNimbusErrorCause(
        code = "content_digest_disabled",
        message = "No digest algorithm is configured. " +
                "Call Nimbus.Builder().withContentDigest(...) to enable it."
    )

    /** The requested download task was not found. */
    public data object DownloadNotFound : PermanentNimbusErrorCause(
        code = "download_not_found",
        message = "Download not found."
    )

    /**
     * Another download task already uses the same destination path.
     *
     * @param filePath The conflicting path.
     */
    public data class FilePathInUse(
        val filePath: String
    ) : PermanentNimbusErrorCause(
        code = "file_path_in_use",
        message = "Another download already uses path: $filePath."
    )

    /**
     * Not enough free space on the volume for the remaining download bytes.
     */
    public data class InsufficientDiskSpace(
        val path: String,
        val requiredBytes: Long,
        val availableBytes: Long
    ) : PermanentNimbusErrorCause(
        code = "insufficient_disk_space",
        message = "Insufficient disk space (required $requiredBytes bytes, available $availableBytes)."
    )

    /**
     * The operation is not allowed in the current download state.
     *
     * @param currentState The current download state.
     */
    public data class InvalidState(
        val currentState: DownloadState
    ) : PermanentNimbusErrorCause(
        code = "invalid_state",
        message = "Operation not allowed in state: $currentState."
    )

    /**
     * Nimbus failed to initialize (e.g. could not load persisted tasks from disk).
     *
     * @param cause The underlying cause.
     */
    public data class InitializationFailed(
        override val cause: KError
    ) : PermanentNimbusErrorCause(
        code = "initialization_failed",
        message = "Failed to initialize Nimbus.",
        cause = cause
    )

    /** The requested remote resource was not found (HTTP 404). */
    public data object ResourceNotFound : PermanentNimbusErrorCause(
        code = "resource_not_found",
        message = "Resource not found."
    )

    /**
     * A download attempt failed permanently.
     *
     * @param errorCause The typed download-level cause — exhaustively matchable via `when`.
     */
    public data class DownloadFailed(
        val errorCause: PermanentDownloadErrorCause
    ) : PermanentNimbusErrorCause(
        code = "download_failed",
        message = "A permanent download error occurred.",
        cause = errorCause
    )

    /**
     * Retrieving the remote file size failed permanently.
     *
     * @param errorCause The typed file-size-level cause — exhaustively matchable via `when`.
     */
    public data class GetFileSizeFailed(
        val errorCause: PermanentGetFileSizeErrorCause
    ) : PermanentNimbusErrorCause(
        code = "get_file_size_failed",
        message = "A permanent error occurred while retrieving the file size.",
        cause = errorCause
    )

    /**
     * A local storage error (I/O failure, permission denied, repository write) prevented
     * the operation from completing.
     *
     * @param cause The underlying storage error.
     */
    public data class StorageError(
        override val cause: KError
    ) : PermanentNimbusErrorCause(
        code = "storage_error",
        message = "A local storage error prevented the operation.",
        cause = cause
    )

    /**
     * An unhandled exception occurred.
     *
     * @param cause The underlying cause, if available.
     */
    public data class UnexpectedError(
        override val cause: KError? = null
    ) : PermanentNimbusErrorCause(
        code = "unexpected_error",
        message = "An unexpected error occurred.",
        cause = cause
    )
}

