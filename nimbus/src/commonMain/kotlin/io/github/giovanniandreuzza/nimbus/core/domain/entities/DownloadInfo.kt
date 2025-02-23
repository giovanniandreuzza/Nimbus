package io.github.giovanniandreuzza.nimbus.core.domain.entities

import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileName
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FilePath
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.FileUrl
import io.github.giovanniandreuzza.nimbus.shared.ddd.domain.AggregateRoot

/**
 * File.
 *
 * @param url URL to download.
 * @param path Path to save the file.
 * @param name file name.
 * @author Giovanni Andreuzza
 */
internal class File private constructor(
    val url: FileUrl,
    val path: FilePath,
    val name: FileName
) : AggregateRoot<DownloadId>(DownloadId.create(url.value, path.value, name.value)) {

    companion object {
        /**
         * Create a new file.
         *
         * @param url URL to download.
         * @param path Path to save the file.
         * @param name file name.
         * @return [File] aggregate root.
         */
        fun create(url: String, path: String, name: String): File {
            val url = FileUrl.create(url)
            val path = FilePath.create(path)
            val name = FileName.create(name)

            return File(
                url = url,
                path = path,
                name = name
            )
        }
    }

    override fun toString(): String {
        return "File(id=$id, url=$url, path=$path, name=$name)"
    }
}