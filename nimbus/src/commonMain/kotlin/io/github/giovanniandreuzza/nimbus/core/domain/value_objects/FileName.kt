package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.IsValueObject
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.ValueObject

/**
 * File Name.
 *
 * @param value File name.
 * @author Giovanni Andreuzza
 */
@IsValueObject
internal class FileName private constructor(value: String) : ValueObject<String>(value) {

    companion object {
        /**
         * Create a new file name.
         *
         * @param name File name.
         * @return [FileName] value object.
         */
        fun create(name: String): FileName {
            return FileName(name)
        }
    }

    override fun toString(): String {
        return "FileName(value='$value')"
    }
}