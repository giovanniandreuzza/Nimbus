package io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.ports

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.errors.GetFileSizeFailed
import okio.Source

/**
 * Nimbus Download Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsFramework
public interface NimbusDownloadRepository {

    /**
     * Get the file size.
     *
     * @param fileUrl The file URL.
     * @return The file size.
     */
    public suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeFailed>

    /**
     * Download a file.
     *
     * @param fileUrl The file URL.
     * @param offset The offset.
     * @return The download stream.
     */
    public suspend fun downloadFile(
        fileUrl: String,
        offset: Long?
    ): KResult<Source, StartDownloadErrors.StartDownloadFailed>
}