package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState

/**
 * Nimbus Error.
 *
 * Single unified error type for all NimbusAPI operations.
 *
 * @author Giovanni Andreuzza
 */
public sealed class NimbusError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(code, message, cause) {

    /** The supplied file path contains a path-traversal component (`..`). */
    public data object InvalidPath : NimbusError(
        code = "invalid_path",
        message = "File path must not contain path-traversal components."
    )

    /** The supplied URL is not a supported network URL (`http`/`https`). */
    public data object InvalidUrl : NimbusError(
        code = "invalid_url",
        message = "File URL must use http or https."
    )

    /** The supplied file name is invalid or unsafe for filesystem usage. */
    public data object InvalidFileName : NimbusError(
        code = "invalid_file_name",
        message = "File name is invalid."
    )

    /** The remote file metadata contains an invalid file size. */
    public data class InvalidFileSize(
        override val cause: KError? = null
    ) : NimbusError(
        code = "invalid_file_size",
        message = "Remote file size is invalid.",
        cause = cause
    )

    /** The requested download task was not found. */
    public data object DownloadNotFound : NimbusError(
        code = "download_not_found",
        message = "Download not found."
    )

    /**
     * Another download task already uses the same destination [filePath].
     *
     * @param filePath The conflicting path.
     */
    public data class FilePathInUse(
        val filePath: String
    ) : NimbusError(
        code = "file_path_in_use",
        message = "Another download already uses path: $filePath."
    )

    /**
     * Not enough free space on the volume for the remaining download bytes plus [minReservedDiskBytes].
     */
    public data class InsufficientDiskSpace(
        val path: String,
        val requiredBytes: Long,
        val availableBytes: Long
    ) : NimbusError(
        code = "insufficient_disk_space",
        message = "Insufficient disk space at path (required $requiredBytes bytes, available $availableBytes)."
    )

    /**
     * The operation is not allowed in the current state.
     *
     * @param currentState The current download state.
     */
    public data class InvalidState(
        val currentState: DownloadState
    ) : NimbusError(
        code = "invalid_state",
        message = "Operation not allowed in state: $currentState."
    )

    /**
     * Nimbus failed to initialize.
     *
     * @param cause The underlying cause.
     */
    public data class InitializationFailed(
        override val cause: KError
    ) : NimbusError(
        code = "initialization_failed",
        message = "Failed to initialize Nimbus.",
        cause = cause
    )

    /** The requested remote resource was not found (HTTP 404). */
    public data object ResourceNotFound : NimbusError(
        code = "resource_not_found",
        message = "Resource not found."
    )

    /**
     * A transient error that may resolve on retry.
     *
     * @param cause The underlying cause.
     */
    public data class TemporaryError(
        override val cause: KError? = null
    ) : NimbusError(
        code = "temporary_error",
        message = "A temporary error occurred.",
        cause = cause
    )

    /**
     * A permanent error that will not resolve on retry.
     *
     * @param cause The underlying cause.
     */
    public data class PermanentError(
        override val cause: KError? = null
    ) : NimbusError(
        code = "permanent_error",
        message = "A permanent error occurred.",
        cause = cause
    )

    /**
     * An unexpected error.
     *
     * @param cause The underlying cause.
     */
    public data class UnexpectedError(
        override val cause: KError? = null
    ) : NimbusError(
        code = "unexpected_error",
        message = "An unexpected error occurred.",
        cause = cause
    )
}
