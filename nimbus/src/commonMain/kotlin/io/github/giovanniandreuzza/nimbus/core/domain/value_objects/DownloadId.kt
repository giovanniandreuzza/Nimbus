package io.github.giovanniandreuzza.nimbus.core.domain.value_objects

import io.github.giovanniandreuzza.explicitarchitecture.domain.ValueObject

/**
 * Download ID.
 *
 * @param value Download ID.
 * @author Giovanni Andreuzza
 */
internal class DownloadId private constructor(val value: String) : ValueObject() {

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

        /**
         * Create a new Download ID.
         *
         * @param fileUrl File URL.
         * @param filePath File path.
         * @param fileName File name.
         * @return [DownloadId] value object.
         */
        fun create(fileUrl: String, filePath: String, fileName: String): DownloadId {
            val idToHash = "$fileUrl|$filePath|$fileName"
            return DownloadId(idToHash.hashCode().toUInt().toString(16))
        }
    }

    override fun toString(): String {
        return "DownloadId(value=$value)"
    }
}