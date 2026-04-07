package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import kotlinx.io.Source

/**
 * Nimbus Download Port.
 *
 * Implementors (e.g. `KtorDownloadAdapter`) **must** return [GetFileSizeError] and [DownloadError]
 * from the respective methods. Both types are `public` precisely because they are part of the
 * implementor contract, not because they are part of the public API surface. Callers of
 * [io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI] never see these types directly —
 * they are mapped to [io.github.giovanniandreuzza.nimbus.presentation.NimbusError] before crossing
 * the public boundary.
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