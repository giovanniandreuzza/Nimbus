package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
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
     * What the origin says about the file, before any of it is transferred.
     *
     * The size is what drives progress, the disk-headroom check and the integrity check at the
     * end. The validator is what makes a resume safe: an opaque token — an HTTP `ETag` or
     * `Last-Modified`, or whatever the transport calls the same thing — that identifies *this
     * version* of the file. Return null for it if the transport has no such notion; the resume
     * then works exactly as it did before, which is to say it assumes the file has not changed.
     *
     * @param fileUrl The file URL.
     * @return [KResult] with a [RemoteFile] or [GetFileSizeError] on failure.
     */
    public suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFile, GetFileSizeError>

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
     * **Call [onSourceOpened] exactly once**, with a single [Source] covering the whole body.
     * Nimbus opens the destination file around that one call and closes it when the call
     * returns, so a second invocation writes to a closed sink and is reported as a storage
     * failure — a permanent one, which stops the download. Stream the body through the one
     * [Source] instead; a chunked or multi-part wire format is the adapter's to join up.
     *
     * **A transport that blocks a thread cannot be interrupted.** Nimbus abandons a transfer
     * that delivers nothing for `withStallTimeoutMs`, and abandoning it means cancelling this
     * call — which only unwinds an implementation that *suspends* while it waits for bytes. An
     * implementation that blocks (Ktor's `ByteReadChannel.asSource()` reads through
     * `runBlocking`) must impose a deadline of its own on the transport, the way
     * `KtorDownloadAdapter` sets a socket timeout on every request it makes.
     *
     * **On a resume, refuse to append to a file that changed.** [resumeValidator] is what
     * [getRemoteFile] reported when the transfer began. Give it back to the origin — over HTTP
     * that is `If-Range` — and if the origin answers with the whole file instead of the range,
     * report [TemporaryDownloadErrorCause.RemoteFileChanged] rather than delivering that body:
     * appending the start of a new file to the prefix of an old one produces a file of exactly
     * the right length holding bytes that were never a file, which the size check cannot see
     * and only a digest would catch. Nimbus answers that cause by discarding the partial and
     * fetching from zero.
     *
     * @param fileUrl The file URL.
     * @param offset The byte offset to resume from, or 0 for a fresh download.
     * @param resumeValidator What identified the file when the transfer started, or null.
     * Meaningless when [offset] is 0.
     * @param onSourceOpened Receives the response body. Never throws on Nimbus's behalf.
     * @return [KResult] with [Unit] on success or [DownloadError] on failure.
     */
    public suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        resumeValidator: String?,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError>
}

/**
 * What a transport can say about a remote file up front.
 *
 * @param sizeBytes how long the file is.
 * @param validator an opaque token identifying this version of it — over HTTP an `ETag`, or
 * `Last-Modified` when there is no `ETag`. Null when the transport has no such notion.
 * @author Giovanni Andreuzza
 */
public data class RemoteFile(
    public val sizeBytes: Long,
    public val validator: String? = null
)
