package io.github.giovanniandreuzza.nimbus.shared.utils

/**
 * KError.
 *
 * @param code    error code.
 * @param message error message.
 * @param cause   error cause.
 * @author Giovanni Andreuzza
 */
public open class KError(
    public open val code: String,
    public open val message: String,
    public open val cause: String? = null
) {

    override fun toString(): String {
        return "BaseError(code='$code', message='$message', cause=$cause)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is KError) return false

        if (code != other.code) return false
        if (message != other.message) return false
        if (cause != other.cause) return false

        return true
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (cause?.hashCode() ?: 0)
        return result
    }
}