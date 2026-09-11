package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Create File Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
public sealed class CreateFileError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * File Already Exists Error.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data object FileAlreadyExists : CreateFileError(
        code = "file_already_exists",
        message = "The file already exists."
    )

    /**
     * I/O Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class IOError(override val cause: KError) : CreateFileError(
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
    public data class ReadPermissionDenied(override val cause: KError) : CreateFileError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

    /**
     * Write Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class WritePermissionDenied(override val cause: KError) : CreateFileError(
        code = "write_permission_denied",
        message = "Write permission denied.",
        cause = cause
    )

    /**
     * A failure the implementation could not classify.
     *
     * Every other cause in this family describes a situation the caller can reason about.
     * An implementation that does not recognise a failure reports it here rather than
     * borrowing a named cause that would misdescribe it.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    public data class UnexpectedError(override val cause: KError) : CreateFileError(
        code = "unexpected_error",
        message = "An unexpected error occurred.",
        cause = cause
    )
}