package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import kotlinx.io.Source

/**
 * Nimbus Download Port.
 *
 * @author Giovanni Andreuzza
 */
@IsFramework
public interface NimbusDownloadPort {

    /**
     * Get the file size.
     *
     * @param fileUrl The file URL.
     * @return [KResult] with the file size or [GetFileSizeError] on failure.
     */
    public suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError>

    /**
     * Download a file.
     *
     * @param fileUrl The file URL.
     * @param offset The offset.
     * @return [KResult] with [Unit] on success or [DownloadError] on failure.
     */
    public suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError>
}