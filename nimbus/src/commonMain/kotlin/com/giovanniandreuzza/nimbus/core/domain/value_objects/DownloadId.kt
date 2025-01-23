package com.giovanniandreuzza.nimbus.core.domain.value_objects

/**
 * Download ID.
 *
 * @param id Download ID.
 * @author Giovanni Andreuzza
 */
internal class DownloadId private constructor(val id: Long) {

    companion object {
        /**
         * Create a new Download ID.
         *
         * @param id Download ID.
         */
        fun create(id: Long): DownloadId {
            return DownloadId(id)
        }

        /**
         * Create a new Download ID.
         *
         * @param fileUrl File URL.
         * @param filePath File path.
         * @param fileName File name.
         */
        fun create(fileUrl: String, filePath: String, fileName: String): DownloadId {
            return DownloadId((fileUrl + filePath + fileName).hashCode().toLong())
        }
    }

    override fun toString(): String {
        return id.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadId) return false

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}