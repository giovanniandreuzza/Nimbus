package io.github.giovanniandreuzza.nimbus.core.domain.entities

import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId

/**
 * Download Task.
 *
 * @param id Download ID.
 * @param url URL to download.
 * @param path Path to save the file.
 * @param name file name.
 * @author Giovanni Andreuzza
 */
internal data class DownloadTask(
    val id: DownloadId,
    val url: String,
    val path: String,
    val name: String
)