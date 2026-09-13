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
     * Returns `true` if the download for [fileUrl] has already finished.
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
        fileName: String,
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
        fileName: String,
        expectedChecksum: Checksum? = null
    ): KResult<Flow<DownloadState>, NimbusError>

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
