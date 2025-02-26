package io.github.giovanniandreuzza.nimbus.shared.utils

import io.github.giovanniandreuzza.explicitarchitecture.shared.IsShared

/**
 * Get the download progress.
 *
 * @param downloadedBytes The downloaded bytes.
 * @param fileSize The file size.
 *
 * @return The download progress.
 *
 * @author Giovanni Andreuzza
 */
@IsShared
internal fun getDownloadProgress(downloadedBytes: Long, fileSize: Long): Double {
    return ((downloadedBytes * 100.0) / fileSize)
}