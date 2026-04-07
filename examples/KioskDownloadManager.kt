/**
 * KioskDownloadManager — reference implementation for unattended 24/7 kiosk devices.
 *
 * # Scenario
 * A kiosk or digital-signage device polls a backend periodically to receive the list of
 * files it should have on disk (videos, images, configs). The device must:
 *
 *  - Automatically download every file in the manifest that is not yet present.
 *  - Remove files that have been removed from the manifest (and optionally delete them
 *    from disk to reclaim space).
 *  - Survive transient network failures: retry with back-off, never give up permanently.
 *  - Survive app restarts: Nimbus restores all persisted tasks on boot; this manager
 *    re-attaches to in-progress downloads without re-downloading from scratch.
 *  - Never require manual intervention: all errors are caught, logged, and retried.
 *  - Emit structured log events so a remote-monitoring back-end can alert on anomalies.
 *
 * # How it works
 *
 *  1. `start()` launches two background coroutines:
 *      a) a **reconciliation loop** that polls the manifest periodically and calls
 *         `ensureDownloaded` for every file in the manifest.
 *      b) an **all-downloads observer** that watches for `Failed` tasks and schedules
 *         automatic retries.
 *
 *  2. `ensureDownloaded` is the single entry-point for each file. It is idempotent:
 *      - Already on disk? → emits `Finished` immediately and returns.
 *      - Already in-progress? → re-attaches to the existing task's flow.
 *      - Failed / stale? → repairs and re-starts automatically.
 *      - Not yet known? → enqueues, starts, and returns the progress flow.
 *
 *  3. The all-downloads observer catches tasks that land in `Failed` state (e.g. mid-
 *     download errors) and re-queues them via `retryFailedDownload` + `startDownload`
 *     after an exponential back-off delay so the reconciliation loop doesn't have to
 *     handle this case.
 *
 * # Wiring (Android example with Koin)
 *
 * ```kotlin
 * val appModule = module {
 *     single { HttpClient(OkHttp) }
 *
 *     single<NimbusAPI> {
 *         Nimbus.Builder()
 *             .withAndroidContext(androidContext())
 *             .withNimbusDownloadPort(KtorDownloadAdapter(get()))
 *             .withConcurrencyLimit(3)
 *             .withMaxRetryAttempts(5)
 *             .withRetryBaseDelayMs(1_000L)
 *             .withMinReservedDiskBytes(200 * 1024 * 1024L)  // keep 200 MB free
 *             .withNimbusLogger(KioskNimbusLogger())
 *             .build()
 *             .init()
 *     }
 *
 *     single {
 *         KioskDownloadManager(
 *             nimbus           = get(),
 *             backendClient    = get(),
 *             downloadDirectory = androidContext().filesDir.absolutePath + "/media",
 *             scope            = CoroutineScope(SupervisorJob() + Dispatchers.IO)
 *         )
 *     }
 * }
 *
 * // In Application.onCreate():
 * get<KioskDownloadManager>().start()
 * ```
 */

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File

// ---------------------------------------------------------------------------
// Domain model — replace with your actual API response classes
// ---------------------------------------------------------------------------

/**
 * A single file entry from the backend manifest.
 *
 * @param url      Full HTTP/HTTPS URL of the file.
 * @param fileName The name to save the file as on disk.
 */
data class RemoteFile(
    val url: String,
    val fileName: String
)

/**
 * Manifest returned by the backend — the device must mirror this set of files exactly.
 */
data class FileManifest(
    val files: List<RemoteFile>
)

// ---------------------------------------------------------------------------
// Backend client interface — implement this with Ktor, Retrofit, etc.
// ---------------------------------------------------------------------------

/**
 * Fetches the current manifest from your backend.
 * Return `null` on any error so the manager can retry on the next poll cycle.
 */
interface BackendClient {
    suspend fun fetchManifest(): FileManifest?
}

// ---------------------------------------------------------------------------
// Nimbus logger — forwards structured events to your logging/monitoring stack
// ---------------------------------------------------------------------------

/**
 * Example [NimbusLogger] that logs to the console. In production, forward events
 * to your remote monitoring service (Datadog, Firebase, custom endpoint, etc.).
 */
class KioskNimbusLogger : NimbusLogger {
    override suspend fun log(event: NimbusLogEvent) {
        when (event) {
            is NimbusLogEvent.DownloadEnqueued ->
                println("[NIMBUS] Enqueued ${event.fileUrl} (${event.expectedSizeBytes} bytes)")

            is NimbusLogEvent.DownloadStartRequested ->
                println("[NIMBUS] Start requested for ${event.fileUrl}")

            is NimbusLogEvent.DownloadFinished ->
                println("[NIMBUS] Finished ${event.fileUrl}")

            is NimbusLogEvent.DownloadFailed ->
                println("[NIMBUS] FAILED ${event.fileUrl} — ${event.error.message}")

            is NimbusLogEvent.InsufficientDiskSpace ->
                println("[NIMBUS] DISK FULL — need ${event.requiredBytes}B, " +
                        "have ${event.availableBytes}B at ${event.path}")

            is NimbusLogEvent.AutoStartFailed ->
                println("[NIMBUS] AutoStart failed for ${event.fileUrl}: ${event.error.message}")

            is NimbusLogEvent.AutoRetryFailed ->
                println("[NIMBUS] AutoRetry failed for ${event.fileUrl}: ${event.error.message}")

            is NimbusLogEvent.PersistenceFailed ->
                println("[NIMBUS] Persistence error for ${event.fileUrl}: ${event.cause.message}")

            is NimbusLogEvent.EnsureDownloadedAlreadyComplete ->
                println("[NIMBUS] Already complete: ${event.fileUrl}")

            is NimbusLogEvent.EnsureDownloadedStaleFinishedRemoved ->
                println("[NIMBUS] Stale task removed, re-downloading: ${event.fileUrl}")
        }
    }
}

// ---------------------------------------------------------------------------
// KioskDownloadManager
// ---------------------------------------------------------------------------

/**
 * Manages the full lifecycle of file downloads for an unattended kiosk device.
 *
 * @param nimbus            Initialised [NimbusAPI] singleton.
 * @param backendClient     Fetches the authoritative file manifest from the backend.
 * @param downloadDirectory Absolute path to the directory where files are saved.
 * @param scope             Long-lived coroutine scope (tied to Application or Service).
 * @param pollIntervalMs    How often to re-fetch the manifest and reconcile.
 * @param retryDelayMs      Base delay before retrying a failed download manually.
 * @param maxRetryDelayMs   Cap for exponential back-off retry delays.
 */
class KioskDownloadManager(
    private val nimbus: NimbusAPI,
    private val backendClient: BackendClient,
    private val downloadDirectory: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = 5 * 60_000L,   // 5 minutes
    private val retryDelayMs: Long = 10_000L,          // 10 seconds base
    private val maxRetryDelayMs: Long = 5 * 60_000L    // 5 minutes cap
) {

    /**
     * Starts the manager. Call once from `Application.onCreate()` or your foreground Service.
     * The two loops run independently inside a [supervisorScope] so a crash in one does not
     * bring down the other.
     */
    fun start() {
        scope.launch {
            supervisorScope {
                // Loop A — periodic manifest reconciliation
                launch { reconciliationLoop() }

                // Loop B — watch all downloads, retry failures automatically
                launch { failureWatcherLoop() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Loop A — Reconciliation
    // -----------------------------------------------------------------------

    /**
     * Fetches the manifest on every poll cycle and ensures each listed file is present on disk.
     * Files that have been removed from the manifest are removed from Nimbus and deleted from disk.
     */
    private suspend fun reconciliationLoop() {
        while (isActive) {
            runCatching { reconcile() }
                .onFailure { println("[KIOSK] Unexpected reconcile error: ${it.message}") }

            delay(pollIntervalMs)
        }
    }

    private suspend fun reconcile() {
        val manifest = backendClient.fetchManifest()
        if (manifest == null) {
            println("[KIOSK] Could not fetch manifest, will retry after $pollIntervalMs ms")
            return
        }

        val manifestUrls = manifest.files.map { it.url }.toSet()

        // --- 1. Remove tasks for files no longer in the manifest ---
        when (val all = nimbus.getAllDownloads()) {
            is Failure -> println("[KIOSK] Could not read download list: ${all.error.message}")
            is Success -> {
                for (task in all.value) {
                    if (task.fileUrl !in manifestUrls) {
                        removeObsoleteFile(task.fileUrl, task.filePath)
                    }
                }
            }
        }

        // --- 2. Ensure every manifest file is present on disk ---
        for (file in manifest.files) {
            ensureFileDownloaded(file)
        }
    }

    /**
     * Removes a file that is no longer in the manifest.
     * Active downloads are cancelled first; finished files are deleted from disk.
     */
    private suspend fun removeObsoleteFile(fileUrl: String, filePath: String) {
        println("[KIOSK] Removing obsolete file: $filePath")

        // Cancel if still active (ignore errors — it may already be terminal)
        nimbus.cancelDownload(fileUrl)

        // Remove metadata and delete the file from disk
        when (val result = nimbus.removeDownload(fileUrl, deleteAssociatedFile = true)) {
            is Failure -> println("[KIOSK] Could not remove $fileUrl: ${result.error.message}")
            is Success -> println("[KIOSK] Removed $fileUrl")
        }
    }

    /**
     * Calls `ensureDownloaded` for a single file and collects the resulting state flow until
     * the download reaches a terminal state. Errors are logged and swallowed so the loop
     * continues with the next file.
     */
    private suspend fun ensureFileDownloaded(file: RemoteFile) {
        val filePath = buildFilePath(file.fileName)

        when (val result = nimbus.ensureDownloaded(file.url, filePath, file.fileName)) {
            is Failure -> {
                println("[KIOSK] ensureDownloaded failed for ${file.fileName}: ${result.error.toReadable()}")
                // The failure watcher (Loop B) will handle retries if a task was already
                // created and is in Failed state. For hard errors (InvalidUrl, disk full, etc.)
                // we log and move on — the next reconciliation cycle will retry.
            }
            is Success -> {
                // Collect until terminal — ensureDownloaded already started the download,
                // we just observe until done. Using a nested launch keeps reconcile() from
                // blocking on one slow download while others could proceed.
                scope.launch {
                    result.value.collect { state ->
                        when (state) {
                            is DownloadState.Downloading ->
                                println("[KIOSK] ${file.fileName} — ${state.progress.toInt()}%")
                            is DownloadState.Failed ->
                                println("[KIOSK] ${file.fileName} failed: ${state.error.message}")
                            DownloadState.Finished ->
                                println("[KIOSK] ${file.fileName} ready on disk")
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Loop B — Failure watcher
    // -----------------------------------------------------------------------

    /**
     * Observes all downloads reactively. When a task lands in [DownloadState.Failed],
     * schedules an automatic retry with exponential back-off.
     *
     * This catches failures that happen mid-download (network drop, server error) without
     * waiting for the next reconciliation cycle.
     */
    private suspend fun failureWatcherLoop() {
        nimbus.observeAllDownloads().collect { tasks ->
            for (task in tasks) {
                if (task.state is DownloadState.Failed) {
                    // Launch a retry coroutine so the watcher can continue observing
                    scope.launch { retryWithBackoff(task.fileUrl) }
                }
            }
        }
    }

    /**
     * Resets a failed task and restarts it. Applies exponential back-off between attempts.
     * Gives up after [MAX_RETRY_ROUNDS] rounds — the next reconciliation cycle will pick it
     * up again if the file is still in the manifest.
     */
    private suspend fun retryWithBackoff(fileUrl: String) {
        var delayMs = retryDelayMs
        var round = 0

        while (round < MAX_RETRY_ROUNDS) {
            println("[KIOSK] Retry round ${round + 1} for $fileUrl in ${delayMs}ms")
            delay(delayMs)

            // Reset Failed → Enqueued
            when (val resetResult = nimbus.retryFailedDownload(fileUrl)) {
                is Failure -> {
                    println("[KIOSK] retryFailedDownload failed for $fileUrl: ${resetResult.error.toReadable()}")
                    // Task may have been removed or changed state — stop retrying here.
                    return
                }
                is Success -> Unit
            }

            // Start the download again
            when (val startResult = nimbus.startDownload(fileUrl)) {
                is Failure -> {
                    println("[KIOSK] startDownload after retry failed for $fileUrl: ${startResult.error.toReadable()}")
                    round++
                    delayMs = (delayMs * 2).coerceAtMost(maxRetryDelayMs)
                    continue
                }
                is Success -> {
                    println("[KIOSK] Restarted $fileUrl successfully")
                    return  // The failure watcher will catch it again if it fails once more
                }
            }
        }

        println("[KIOSK] Giving up on $fileUrl after $MAX_RETRY_ROUNDS rounds — next reconcile will retry")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildFilePath(fileName: String): String =
        downloadDirectory + File.separator + fileName

    companion object {
        private const val MAX_RETRY_ROUNDS = 5
    }
}

// ---------------------------------------------------------------------------
// Extension — human-readable error summary
// ---------------------------------------------------------------------------

private fun NimbusError.toReadable(): String = when (this) {
    NimbusError.InvalidPath          -> "invalid path"
    NimbusError.InvalidUrl           -> "invalid URL"
    NimbusError.InvalidFileName      -> "invalid file name"
    is NimbusError.InvalidFileSize   -> "invalid file size"
    NimbusError.DownloadNotFound     -> "download not found"
    is NimbusError.FilePathInUse     -> "path already in use: $filePath"
    is NimbusError.InsufficientDiskSpace ->
        "insufficient disk space (need ${requiredBytes}B, have ${availableBytes}B)"
    is NimbusError.InvalidState      -> "invalid state: $currentState"
    is NimbusError.InitializationFailed -> "init failed: ${cause.message}"
    NimbusError.ResourceNotFound     -> "resource not found (404)"
    is NimbusError.TemporaryError    -> "temporary error: ${cause?.message}"
    is NimbusError.PermanentError    -> "permanent error: ${cause?.message}"
    is NimbusError.UnexpectedError   -> "unexpected error: ${cause?.message}"
}
