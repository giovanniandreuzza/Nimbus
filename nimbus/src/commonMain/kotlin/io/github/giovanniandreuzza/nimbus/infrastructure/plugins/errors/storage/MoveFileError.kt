package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Move File Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public sealed class MoveFileError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    @IsFrameworkError
    public data object FileNotFound : MoveFileError(
        code = "file_not_found",
        message = "The specified file was not found."
    )

    @IsFrameworkError
    public data object MoveFailed : MoveFileError(
        code = "move_failed",
        message = "The file could not be moved."
    )

    @IsFrameworkError
    public data class IOError(override val cause: KError) : MoveFileError(
        code = "io_error",
        message = "An I/O error occurred.",
        cause = cause
    )

    @IsFrameworkError
    public data class ReadPermissionDenied(override val cause: KError) : MoveFileError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

    @IsFrameworkError
    public data class WritePermissionDenied(override val cause: KError) : MoveFileError(
        code = "write_permission_denied",
        message = "Write permission denied.",
        cause = cause
    )
}
