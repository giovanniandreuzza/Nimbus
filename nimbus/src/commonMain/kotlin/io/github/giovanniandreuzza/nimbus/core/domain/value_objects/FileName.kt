package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.domain.ValueObject

/**
 * File Name.
 *
 * @param value File name.
 * @author Giovanni Andreuzza
 */
internal class FileName private constructor(val value: String) : ValueObject() {

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