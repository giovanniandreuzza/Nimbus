package io.github.giovanniandreuzza.nimbus.frameworks.store.errors

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.errors.IsFrameworkError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Init Store Error.
 *
 * @param code Error code.
 * @param message Error message.
 * @param cause Cause.
 * @author Giovanni Andreuzza
 */
@IsFrameworkError
internal sealed class InitStoreError(
    override val code: String,
    override val message: String,
    override val cause: KError? = null
) : KError(
    code = code,
    message = message,
    cause = cause
) {

    /**
     * I/O Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class IOError(override val cause: KError) : InitStoreError(
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
    data class ReadPermissionDenied(override val cause: KError) : InitStoreError(
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
    data class WritePermissionDenied(override val cause: KError) : InitStoreError(
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
    data class SerializationError(override val cause: KError) : InitStoreError(
        code = "serialization_error",
        message = "Failed to serialize data.",
        cause = cause
    )

    /**
     * Deserialization Error.
     *
     * @param cause Cause.
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class DeserializationError(override val cause: KError) : InitStoreError(
        code = "deserialization_error",
        message = "Failed to deserialize data.",
        cause = cause
    )

    /**
     * Store Not Found Error.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data object StoreNotFound : InitStoreError(
        code = "store_not_found",
        message = "Store not found."
    )

    /**
     * Store Failed.
     *
     * @author Giovanni Andreuzza
     */
    @IsFrameworkError
    data class StoreFailed(override val cause: KError) : InitStoreError(
        code = "store_failed",
        message = "Store failed.",
        cause = cause
    )
}