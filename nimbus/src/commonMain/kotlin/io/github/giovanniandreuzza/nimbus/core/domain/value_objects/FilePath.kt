package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.IsValueObject
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.ValueObject

/**
 * File Path.
 *
 * @param value File path.
 * @author Giovanni Andreuzza
 */
@IsValueObject
internal class FilePath private constructor(val value: String) : ValueObject() {

    companion object {
        /**
         * Create a new file path.
         *
         * @param path File path.
         * @return [FilePath] value object.
         */
        fun create(path: String): FilePath {
            return FilePath(path)
        }
    }

    override fun toString(): String {
        return "FilePath(value='$value')"
    }
}