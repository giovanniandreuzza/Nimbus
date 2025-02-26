package io.github.giovanniandreuzza.nimbus.frameworks.filemanager.ports

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileCreationFailed
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.errors.FileNotFound
import okio.Sink
import okio.Source

/**
 * Nimbus File Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsFramework
public interface NimbusFileRepository {

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