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
     * Download a file, handing the response body to [onSourceOpened].
     *
     * The callback must be invoked while the response is still open, which means an
     * implementation ends up wrapping it in whatever `try` covers the transfer. That would
     * normally be a problem: the callback both reads from the network and writes the bytes
     * somewhere, and on every platform a dead socket and a full disk are the same type,
     * `kotlinx.io.IOException`. An implementation catching both would have to tell them apart
     * or risk retrying a disk that will never have room.
     *
     * It does not have to. **[onSourceOpened] never throws a failure of its own.** Nimbus
     * supplies the callback, classifies whatever happens inside it, and reports that
     * separately — so anything an implementation catches came from its own transport and can
     * be treated as such without further evidence. A transient one belongs in
     * [DownloadError.TemporaryError]; `KtorDownloadAdapter` maps every `IOException` there,
     * which is the right default for any HTTP client.
     *
     * The one thing to pass through rather than catch is `CancellationException`: it is how a
     * paused or cancelled download unwinds, and swallowing it prevents that.
     *
     * @param fileUrl The file URL.
     * @param offset The byte offset to resume from, or 0 for a fresh download.
     * @param onSourceOpened Receives the response body. Never throws on Nimbus's behalf.
     * @return [KResult] with [Unit] on success or [DownloadError] on failure.
     */
    public suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError>
}