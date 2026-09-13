package io.github.giovanniandreuzza.nimbus.presentation

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * Main public API of the Nimbus download library.
 *
 * All operations are identified by [fileUrl] — the SHA-256 of the URL is used
 * internally as a stable task ID, so the same URL always refers to the same task.
 *
 * Errors are returned as [NimbusError] values rather than thrown exceptions.
 *
 * @author Giovanni Andreuzza
 */
public interface NimbusAPI {

    /**
     * Whether the file for [fileUrl] is finished **and** on disk at the expected size.
     *
     * `false` also means "cannot say": there is no task, the persisted store could not be
     * loaded, or this instance has been closed. A `Boolean` has nowhere to put the difference,
     * and the alternative — a task that exists and is complete reported as missing — is the
     * safer way round: the caller downloads something they already had rather than plays
     * something they do not.
     *
     * When the difference matters, [getDownloadTask] returns it: a failure names the reason,
     * and a task carries its state.
     */
    public suspend fun isDownloaded(fileUrl: String): Boolean

    /**
     * Fetches the remote file size without starting the download.
     */
    public suspend fun getFileSize(fileUrl: String): KResult<Long, NimbusError>

    /**
     * Returns the current [DownloadTaskDTO] for [fileUrl].
     */
    public suspend fun getDownloadTask(fileUrl: String): KResult<DownloadTaskDTO, NimbusError>

    /**
     * Returns all known download tasks.
     *
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.InitializationFailed] if the library failed to load persisted tasks on boot.
     */
    public suspend fun getAllDownloads(): KResult<List<DownloadTaskDTO>, NimbusError>

    /**
     * Returns a [Flow] that emits the full list of [DownloadTaskDTO] whenever any task state changes.
     *
     * The flow never completes — it stays alive for the lifetime of the repository.
     * Suitable for driving a list UI that needs to reflect live download progress.
     */
    public fun observeAllDownloads(): Flow<List<DownloadTaskDTO>>

    /**
     * Enqueues a new download.
     *
     * [filePath] is the file, in full: Nimbus writes exactly there and creates the parent
     * directories on the way. [fileName] is a label — it is validated, stored and reported in
     * [DownloadTaskDTO], and no I/O uses it — so it defaults to the last segment of the path
     * and is worth passing only when you want the task to carry a different name.
     *
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.InvalidState] if a task for [fileUrl] already exists.
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.InvalidUrl] when the URL
     * carries no scheme. Which schemes are supported is decided by the
     * [io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort]
     * you supply, not here.
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.InvalidPath] or [PermanentNimbusErrorCause.InvalidFileName] when
     * filesystem input is unsafe.
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.ContentDigestDisabled]
     * when [expectedChecksum] is given and no digest is configured, and with
     * [PermanentNimbusErrorCause.ChecksumAlgorithmMismatch] when it names a different algorithm
     * than the configured one — in both cases the verification asked for could not happen, and
     * saying so is the only way the caller learns it did not.
     */
    public suspend fun enqueueDownload(
        fileUrl: String,
        filePath: String,
        fileName: String = filePath.fileNameFromPath(),
        expectedChecksum: Checksum? = null
    ): KResult<DownloadTaskDTO, NimbusError>

    /**
     * Starts a previously enqueued download.
     */
    public suspend fun startDownload(fileUrl: String): KResult<Unit, NimbusError>

    /**
     * Returns a [Flow] that emits [DownloadState] updates for [fileUrl].
     *
     * **Completion behaviour depends on the autoStart setting:**
     * - `autoStart = false` (default): the flow completes when the download reaches
     *   [DownloadState.Finished] **or** [DownloadState.Failed].
     * - `autoStart = true`: the flow only completes on [DownloadState.Finished].
     *   A [DownloadState.Failed] emission is followed by the library automatically
     *   retrying the download, so the flow stays alive and continues emitting through
     *   the `Failed → Enqueued → Downloading → …` retry cycle.
     *
     * Callers relying on flow completion as a terminal signal must account for this
     * difference when autoStart is enabled.
     */
    public suspend fun observeDownload(fileUrl: String): KResult<Flow<DownloadState>, NimbusError>

    /**
     * Pauses an in-progress download. The partial file is kept so the download
     * can be resumed later.
     */
    public suspend fun pauseDownload(fileUrl: String): KResult<Unit, NimbusError>

    /**
     * Resumes a previously paused download.
     */
    public suspend fun resumeDownload(fileUrl: String): KResult<Unit, NimbusError>

    /**
     * Cancels a download and deletes the partial file.
     */
    public suspend fun cancelDownload(fileUrl: String): KResult<Unit, NimbusError>

    /**
     * Removes a finished or failed download from memory and disk.
     *
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.DownloadNotFound] if no task exists for [fileUrl].
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.InvalidState] if the download is still active (use [cancelDownload] instead).
     *
     * @param deleteAssociatedFile If `true`, also deletes the file at the task's destination path
     * (useful to reclaim space). Default `false` keeps the file on disk (metadata only removed).
     */
    public suspend fun removeDownload(
        fileUrl: String,
        deleteAssociatedFile: Boolean = false
    ): KResult<Unit, NimbusError>

    /**
     * After a [DownloadState.Failed] task, refetches remote size, updates the task, deletes the
     * partial file, and resets state to [DownloadState.Enqueued]. Call [startDownload] afterwards.
     */
    public suspend fun retryFailedDownload(fileUrl: String): KResult<Unit, NimbusError>

    /**
     * High-level orchestration: ensures the file for [fileUrl] exists on disk with correct size.
     *
     * - If already complete ([isDownloaded]), returns a [Flow] that emits [DownloadState.Finished] once.
     * - Otherwise enqueues (if needed), repairs failed tasks, removes stale finished metadata,
     *   applies [min reserved disk][io.github.giovanniandreuzza.nimbus.Nimbus.Builder.withMinReservedDiskBytes]
     *   when configured, starts or resumes the download, then returns the same [observeDownload] flow.
     *
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.ContentDigestDisabled]
     * when [expectedChecksum] is given and no digest is configured, and with
     * [PermanentNimbusErrorCause.ChecksumAlgorithmMismatch] when it names a different algorithm
     * than the configured one — in both cases the verification asked for could not happen, and
     * saying so is the only way the caller learns it did not.
     */
    public suspend fun ensureDownloaded(
        fileUrl: String,
        filePath: String,
        fileName: String = filePath.fileNameFromPath(),
        expectedChecksum: Checksum? = null
    ): KResult<Flow<DownloadState>, NimbusError>

    /**
     * Forgets finished downloads that have been finished for longer than [olderThanMs], and
     * returns the urls it removed.
     *
     * A catalogue that only grows is the shape this library was heading for: a signage player
     * cycles content for years, every asset it has ever fetched stays a `Finished` task, and
     * each one costs a `stat` at every boot and a slot in every commit — a commit rewrites the
     * whole store. Nothing removed them, because nothing recorded *when* they finished and so
     * no caller could tell which ones were old.
     *
     * A task carries no finish time until this build has seen it — tasks from an older store
     * are stamped with the time of the upgrade, so their age is measured from there rather
     * than from 1970, which would have the first call delete everything.
     *
     * @param deleteFiles whether the files go too. False keeps them on disk and forgets only
     * the metadata, which is the safer default when something else on the device reads them.
     */
    public suspend fun pruneFinished(
        olderThanMs: Long,
        deleteFiles: Boolean = false
    ): KResult<List<String>, NimbusError>

    /**
     * Commits anything still waiting for a coalesced write, and returns when it is on disk.
     *
     * State that a caller was told was durable already is: a finished, failed or cancelled
     * task reaches the disk before its call returns. What waits for the next commit is the
     * rest — an enqueue, a pause, a progress position — because a commit rewrites every task
     * and paying that on each transition makes one download's cost grow with the catalogue.
     *
     * Call this when the process may not live long enough for that commit: an Android service
     * being torn down, a provisioning run about to reboot the device, a `SIGTERM` from the
     * supervisor. Without it those states are re-derived at boot, which costs a re-enqueue at
     * worst — but on a device that is about to restart on purpose, "at worst" is avoidable.
     */
    public suspend fun flush(): KResult<Unit, NimbusError>

    /**
     * Stops every transfer, commits the store, and releases the library's resources.
     *
     * In that order, so that what is committed is what the next boot will read. The coroutine
     * scope is cancelled only if Nimbus created it — a scope handed in with
     * [Nimbus.Builder.withDownloadScope][io.github.giovanniandreuzza.nimbus.Nimbus.Builder.withDownloadScope]
     * belongs to the caller and is left alone.
     *
     * The instance is unusable afterwards: every call returns
     * [PermanentNimbusErrorCause.Closed] rather than quietly doing nothing. Calling this twice
     * is harmless.
     */
    public suspend fun close()

    /**
     * Recomputes the content digest of an already-downloaded file, reading it from disk.
     *
     * Uses the algorithm configured with
     * [Nimbus.Builder.withContentDigest][io.github.giovanniandreuzza.nimbus.Nimbus.Builder.withContentDigest].
     *
     * This is what separates a file whose bytes have changed since it was downloaded from a
     * file that is intact and simply cannot be used — the two look identical to a consumer
     * that only sees "it did not work", and treating the second as the first means deleting
     * and re-downloading a perfectly good file forever.
     *
     * It reads the whole file: an explicit verification step, not a cheap accessor. The
     * result is deliberately not cached, because Nimbus cannot know the file changed
     * underneath it — which is the very thing the caller is asking about.
     *
     * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.DownloadNotFound]
     * when no task exists for [fileUrl], with [PermanentNimbusErrorCause.InvalidState] when
     * the task has not finished, and with [PermanentNimbusErrorCause.ContentDigestDisabled]
     * when no digest algorithm is configured.
     */
    public suspend fun checksum(fileUrl: String): KResult<Checksum, NimbusError>
}

/**
 * The last segment of a path, whichever separator the platform writes.
 *
 * What [NimbusAPI.enqueueDownload] and [NimbusAPI.ensureDownloaded] use when no file name is
 * given, which is nearly always the name the caller would have typed.
 */
internal fun String.fileNameFromPath(): String =
    substringAfterLast('/').substringAfterLast('\\')
