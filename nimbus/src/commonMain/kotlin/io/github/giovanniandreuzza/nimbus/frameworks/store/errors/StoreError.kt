package io.github.giovanniandreuzza.nimbus.frameworks.store.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Store Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
internal sealed class StoreError(
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
    data object StoreNotFound : StoreError(
        code = "store_not_found",
        message = "Store not found."
    )

    /**
     * Store Failed.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class StoreFailed(override val cause: KError) : StoreError(
        code = "store_failed",
        message = "Store failed.",
        cause = cause
    )

    /**
     * Read Permission Denied Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class ReadPermissionDenied(override val cause: KError) : StoreError(
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
    data class WritePermissionDenied(override val cause: KError) : StoreError(
        code = "write_permission_denied",
        message = "Write permission denied.",
        cause = cause
    )

    /**
     * Serialization Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class SerializationError(override val cause: KError) : StoreError(
        code = "serialization_error",
        message = "Failed to serialize data.",
        cause = cause
    )

    /**
     * I/O Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class IOError(override val cause: KError) : StoreError(
        code = "io_error",
        message = "An I/O error occurred.",
        cause = cause
    )
}
