package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.IsValueObject
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.ValueObject

/**
 * File size.
 *
 * @param value File size.
 * @author Giovanni Andreuzza
 */
@IsValueObject
internal class FileSize private constructor(value: Long) : ValueObject<Long>(value) {

    companion object {
        /**
         * Create a new file size.
         *
         * @param size File size.
         * @return [FileSize] value object.
         */
        fun create(size: Long): FileSize {
            return FileSize(size)
        }
    }

    override fun toString(): String {
        return "FileSize(value='$value')"
    }
}