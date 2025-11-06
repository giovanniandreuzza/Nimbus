package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Delete File Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public sealed class DeleteFileError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * File Not Found Error.
     */
    @IsFrameworkError
    public data object FileNotFound : DeleteFileError(
        code = "file_not_found",
        message = "The specified file was not found."
    )

    /**
     * Delete Failed Error.
     */
    @IsFrameworkError
    public data object DeleteFailed : DeleteFileError(
        code = "delete_failed",
        message = "The file could not be deleted."
    )

    /**
     * I/O Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class IOError(override val cause: KError) : DeleteFileError(
        code = "io_error",
        message = "An I/O error occurred.",
        cause = cause
    )

    /**
     * Read Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class ReadPermissionDenied(override val cause: KError) : DeleteFileError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

    /**
     * Delete Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class DeletePermissionDenied(override val cause: KError) : DeleteFileError(
        code = "delete_permission_denied",
        message = "Delete permission denied.",
        cause = cause
    )
}