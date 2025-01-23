package io.github.giovanniandreuzza.nimbus.core.application.dtos

/**
 * Download Request.
 *
 * @param url URL to download.
 * @param path Path to save the file.
 * @param name File name.
 * @author Giovanni Andreuzza
 */
public data class DownloadRequest(
    val url: String,
    val path: String,
    val name: String
)
