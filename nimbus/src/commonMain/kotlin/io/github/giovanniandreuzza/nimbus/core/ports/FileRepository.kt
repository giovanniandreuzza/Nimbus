package io.github.giovanniandreuzza.nimbus.core.ports

import okio.Sink

/**
 * File Repository.
 *
 * @author Giovanni Andreuzza
 */
public interface FileRepository {

    /**
     * Check if the file is downloaded.
     */
    public fun isDownloaded(filePath: String): Boolean

    /**
     * Get the file size.
     */
    public fun getFileSize(filePath: String): Long

    /**
     * Get the sink.
     */
    public fun getSink(filePath: String): Sink

    /**
     * Delete the file.
     */
    public fun deleteFile(filePath: String)
}