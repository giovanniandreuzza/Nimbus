package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.application.Application
import okio.Source

/**
 * Download Stream.
 *
 * @param source The source.
 * @param contentLength The content length.
 * @param downloadedBytes The downloaded bytes.
 * @author Giovanni Andreuzza
 */
public data class DownloadStream(
    val source: Source,
    val contentLength: Long,
    val downloadedBytes: Long
) : Application
