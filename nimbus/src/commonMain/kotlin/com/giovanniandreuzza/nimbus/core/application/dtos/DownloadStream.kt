package com.giovanniandreuzza.nimbus.core.application.dtos

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
)
