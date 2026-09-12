package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.nimbus.di.init
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob

/**
 * Entry point for the Nimbus download library.
 *
 * Build an instance with [Builder], then call [init] once before using the
 * returned [NimbusAPI].
 *
 * **Singleton responsibility belongs to the caller** (e.g., a DI container like
 * Koin `single {}`). The library does not enforce a process-wide singleton — do
 * not call [Builder.build] more than once with the same store path.
 *
 * ```kotlin
 * val nimbus = Nimbus.Builder()
 *     .withNimbusDownloadPort(myDownloadPort)
 *     .withDownloadManagerPath(cacheDir.path)
 *     .build()
 *
 * val api: NimbusAPI = nimbus.init()
 * api.enqueueDownload(url, filePath, fileName)
 * ```
 *
 * @author Giovanni Andreuzza
 */
public class Nimbus private constructor(
    downloadScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    concurrencyLimit: Int,
    nimbusDownloadPort: NimbusDownloadPort,
    nimbusStoragePort: NimbusStoragePort?,
    downloadManagerPath: String,
    downloadBufferSize: Long,
    downloadNotifyEveryBytes: Long,
    maxRetryAttempts: Int,
    retryBaseDelayMs: Long,
    minReservedDiskBytes: Long?,
    autoStart: Boolean,
    digestAlgorithm: DigestAlgorithm?,
    logger: NimbusLogger?
) {
    private val downloadService = init(
        downloadScope = downloadScope,
        ioDispatcher = ioDispatcher,
        concurrencyLimit = concurrencyLimit,
        nimbusDownloadPort = nimbusDownloadPort,
        nimbusStoragePort = nimbusStoragePort,
        downloadManagerPath = downloadManagerPath,
        downloadBufferSize = downloadBufferSize,
        downloadNotifyEveryBytes = downloadNotifyEveryBytes,
        maxRetryAttempts = maxRetryAttempts,
        retryBaseDelayMs = retryBaseDelayMs,
        minReservedDiskBytes = minReservedDiskBytes,
        autoStart = autoStart,
        digestAlgorithm = digestAlgorithm,
        logger = logger
    )

    public class Builder {
        private var downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        private var concurrencyLimit = 1
        private var nimbusDownloadPort: NimbusDownloadPort? = null
        private var nimbusStoragePort: NimbusStoragePort? = null
        private var downloadManagerPath: String? = null
        private var downloadBufferSize: Long = 8 * 1024L
        private var downloadNotifyEveryBytes: Long = 16 * 32 * 1024L
        private var maxRetryAttempts: Int = 3
        private var retryBaseDelayMs: Long = 500L
        private var minReservedDiskBytes: Long? = null
        private var autoStart: Boolean = false
        private var digestAlgorithm: DigestAlgorithm? = null
        private var logger: NimbusLogger? = null

        public fun withDownloadScope(scope: CoroutineScope): Builder =
            apply { downloadScope = scope }

        public fun withIODispatcher(dispatcher: CoroutineDispatcher): Builder =
            apply { ioDispatcher = dispatcher }

        public fun withConcurrencyLimit(limit: Int): Builder =
            apply { concurrencyLimit = limit }

        public fun withNimbusDownloadPort(port: NimbusDownloadPort): Builder =
            apply { nimbusDownloadPort = port }

        public fun withNimbusStoragePort(port: NimbusStoragePort): Builder =
            apply { nimbusStoragePort = port }

        public fun withDownloadManagerPath(path: String): Builder =
            apply { downloadManagerPath = path }

        public fun withDownloadBufferSize(size: Long): Builder =
            apply { downloadBufferSize = size }

        public fun withDownloadNotifyEveryBytes(bytes: Long): Builder =
            apply { downloadNotifyEveryBytes = bytes }

        public fun withMaxRetryAttempts(attempts: Int): Builder =
            apply { maxRetryAttempts = attempts }

        public fun withRetryBaseDelayMs(delayMs: Long): Builder =
            apply { retryBaseDelayMs = delayMs }

        /**
         * When non-null, requires at least this many bytes to remain free on the destination
         * volume **after** accounting for bytes still to be written for the download.
         * When null (default), no free-space check is performed.
         */
        public fun withMinReservedDiskBytes(bytes: Long?): Builder =
            apply {
                require(bytes == null || bytes >= 0L) { "minReservedDiskBytes must be null or >= 0" }
                minReservedDiskBytes = bytes
            }

        /**
         * When `true`, automatically starts a download immediately after [enqueueDownload]
         * succeeds. The start runs in the background — the caller does not need to call
         * [NimbusAPI.startDownload] manually. Any [NimbusError] from the background start
         * is silently logged via [NimbusLogger] as [io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent.AutoStartFailed]
         * and the task remains in [io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState.Enqueued]
         * so it can be started manually. Default `false`.
         */
        public fun withAutoStart(enabled: Boolean): Builder =
            apply { autoStart = enabled }

        /** Optional structured logging (e.g. remote diagnostics in unattended devices). */
        /**
         * Computes a content digest of every download, with [algorithm].
         *
         * Opt-in, and off by default: without it nothing is hashed, no extra state is
         * kept, and the transfer path is unchanged.
         *
         * With it, [io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO.checksum]
         * is populated when a download finishes, an `expectedChecksum` passed to
         * [NimbusAPI.enqueueDownload] or [NimbusAPI.ensureDownloaded] is verified
         * against the transferred bytes, and [NimbusAPI.checksum] can re-derive the
         * digest of a finished file later.
         *
         * The bytes are hashed in the pass that already writes them to disk, so a
         * caller that would otherwise read the whole file back to hash it itself pays
         * nothing for this beyond the hashing.
         */
        public fun withContentDigest(algorithm: DigestAlgorithm?): Builder =
            apply { this.digestAlgorithm = algorithm }

        public fun withNimbusLogger(logger: NimbusLogger?): Builder =
            apply { this.logger = logger }

        public fun createAndInit(): NimbusAPI = build().init()

        public fun build(): Nimbus {
            requireNotNull(downloadManagerPath) { "downloadManagerPath must be provided" }
            requireNotNull(nimbusDownloadPort) { "nimbusDownloadPort must be provided" }
            require(maxRetryAttempts >= 0) { "maxRetryAttempts must be >= 0" }
            require(retryBaseDelayMs > 0L) { "retryBaseDelayMs must be > 0" }
            // When using the default file system storage, the path must be absolute so that
            // kotlinx.io can open/create it. Relative paths resolve to the process working
            // directory, which is read-only on Android and iOS.
            if (nimbusStoragePort == null) {
                require(downloadManagerPath!!.isAbsolutePath()) {
                    "downloadManagerPath must be an absolute path when using the default " +
                            "file system storage. Got: '${downloadManagerPath}'. " +
                            "On Android use .withAndroidContext(context), or pass " +
                            "context.filesDir.absolutePath + \"/subdir\" explicitly. " +
                            "On iOS use the documents or caches directory absolute path."
                }
            }

            return Nimbus(
                downloadScope = downloadScope,
                ioDispatcher = ioDispatcher,
                concurrencyLimit = concurrencyLimit,
                nimbusDownloadPort = nimbusDownloadPort!!,
                nimbusStoragePort = nimbusStoragePort,
                downloadManagerPath = downloadManagerPath!!,
                downloadBufferSize = downloadBufferSize,
                downloadNotifyEveryBytes = downloadNotifyEveryBytes,
                maxRetryAttempts = maxRetryAttempts,
                retryBaseDelayMs = retryBaseDelayMs,
                minReservedDiskBytes = minReservedDiskBytes,
                autoStart = autoStart,
                digestAlgorithm = digestAlgorithm,
                logger = logger
            )
        }
    }

    /**
     * Starts loading persisted tasks in the background and returns [NimbusAPI] immediately.
     *
     * The first actual API call will suspend briefly if loading is still in progress —
     * subsequent calls return without any delay once loading has completed.
     * Safe to call multiple times; loading is only triggered once.
     */
    public fun init(): NimbusAPI {
        downloadService.startLoad()
        return downloadService
    }
}

// Returns true for POSIX absolute paths (/…) and Windows absolute paths (C:\… or \\…).
private fun String.isAbsolutePath(): Boolean =
    startsWith("/") ||
            startsWith("\\\\") ||
            (length >= 3 && this[1] == ':' && (this[2] == '\\' || this[2] == '/'))
