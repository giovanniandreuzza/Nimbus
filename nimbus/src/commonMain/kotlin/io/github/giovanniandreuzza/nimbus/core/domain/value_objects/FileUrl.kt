package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.IsValueObject
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.ValueObject

/**
 * File URL.
 *
 * @param value File URL.
 * @author Giovanni Andreuzza
 */
@IsValueObject
internal class FileUrl private constructor(value: String) : ValueObject<String>(value) {

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