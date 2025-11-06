package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.IsValueObject
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.valueobjects.ValueObject

/**
 * Download ID.
 *
 * @param value Download ID.
 * @author Giovanni Andreuzza
 */
@IsValueObject
internal class DownloadId private constructor(value: String) : ValueObject<String>(value) {

    companion object {
        /**
         * Create a new Download ID.
         *
         * @param id ID to use as value.
         * @return [DownloadId] value object.
         */
        fun create(id: String): DownloadId {
            return DownloadId(id)
        }
    }

    override fun toString(): String {
        return "DownloadId(value=$value)"
    }
}