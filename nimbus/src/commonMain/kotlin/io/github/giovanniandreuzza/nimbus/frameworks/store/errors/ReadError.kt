package io.github.giovanniandreuzza.nimbus.frameworks.store.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Read Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
internal sealed class ReadError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * Store Not Found Error.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data object StoreNotFound : ReadError(
        code = "store_not_found",
        message = "Store not found."
    )

    /**
     * Read Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class ReadPermissionDenied(override val cause: KError) : ReadError(
        code = "read_permission_denied",
        message = "Read permission denied.",
        cause = cause
    )

    /**
     * Deserialization Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class DeserializationError(override val cause: KError) : ReadError(
        code = "deserialization_error",
        message = "Failed to deserialize data.",
        cause = cause
    )

    /**
     * I/O Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class IOError(override val cause: KError) : ReadError(
        code = "io_error",
        message = "An I/O error occurred.",
        cause = cause
    )
}
