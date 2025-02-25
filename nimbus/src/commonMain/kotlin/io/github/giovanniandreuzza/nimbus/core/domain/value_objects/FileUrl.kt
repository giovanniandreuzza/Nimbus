package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.domain.ValueObject

/**
 * File URL.
 *
 * @param value File URL.
 * @author Giovanni Andreuzza
 */
internal class FileUrl private constructor(val value: String) : ValueObject() {

    companion object {
        /**
         * Create a new file URL.
         *
         * @param url File URL.
         * @return [FileUrl] value object.
         */
        fun create(url: String): FileUrl {
            return FileUrl(url)
        }
    }

    override fun toString(): String {
        return "FileUrl(value='$value')"
    }
}