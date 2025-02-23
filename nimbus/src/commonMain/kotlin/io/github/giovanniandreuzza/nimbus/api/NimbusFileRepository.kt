package io.github.giovanniandreuzza.nimbus.api

import io.github.giovanniandreuzza.nimbus.core.errors.FileCreationFailed
import io.github.giovanniandreuzza.nimbus.core.errors.FileNotFound
import io.github.giovanniandreuzza.nimbus.shared.utils.KResult
import okio.Sink
import okio.Source

/**
 * File Callback.
 *
 * @author Giovanni Andreuzza
 */
public interface FileCallback {

    /**
     * Check if the file is downloaded.
     */
    public fun exists(filePath: String): Boolean

    /**
     * Create the file.
     */
    public fun createFile(filePath: String): KResult<Unit, FileCreationFailed>

    /**
     * Get the file size.
     */
    public fun getFileSize(filePath: String): KResult<Long, FileNotFound>

    /**
     * Get the file sink.
     */
    public fun getFileSink(filePath: String, hasToAppend: Boolean): KResult<Sink, FileNotFound>

    /**
     * Get the file source.
     */
    public fun getFileSource(filePath: String): KResult<Source, FileNotFound>

    /**
     * Delete the file.
     */
    public fun deleteFile(filePath: String): KResult<Unit, FileNotFound>
}