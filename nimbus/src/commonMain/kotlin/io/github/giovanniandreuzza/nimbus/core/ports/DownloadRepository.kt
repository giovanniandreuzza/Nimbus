package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadStream

/**
 * Download Repository.
 *
 * @author Giovanni Andreuzza
 */
public interface DownloadRepository {

    /**
     * Get the file size.
     *
     * @param fileUrl The file URL.
     * @return The file size.
     */
    public suspend fun getFileSize(fileUrl: String): Long

    /**
     * Download a file.
     *
     * @param fileUrl The file URL.
     * @param offset The offset.
     * @return The download stream.
     */
    public suspend fun downloadFile(fileUrl: String, offset: Long?): DownloadStream
}