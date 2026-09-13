package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onSuccess
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TransitionFailure
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.ContentDigestPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
import io.github.giovanniandreuzza.nimbus.shared.utils.takeUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Download Service.
 *
 * Consolidated application service that implements [NimbusAPI] and exposes a
 * single [NimbusError] type for all failure cases.
 *
 * @param idProvider   SHA-256-based stable task ID generator.
 * @param downloadPort Drives actual HTTP download execution.
 * @param repository   Source of truth for all task state (in-memory + disk).
 * @param storagePort Storage the service needs: sizes, creation, deletion, free space.
 * @param contentDigestPort Re-derives the digest of a file already on disk.
 * @param digestAlgorithm When non-null, downloads are digested with it.
 * @param minReservedDiskBytes When non-null, requires at least this many free bytes **after** the
 *   remaining download bytes. When null, no disk headroom check is performed.
 * @param logger Optional structured logging (e.g. remote log in kiosk deployments).
 * @author Giovanni Andreuzza
 */
internal class DownloadService(
    private val idProvider: IdProviderPort,
    private val downloadPort: DownloadPort,
    private val repository: DownloadTaskRepository,
    private val storagePort: StoragePort,
    private val contentDigestPort: ContentDigestPort,
    private val digestAlgorithm: DigestAlgorithm?,
    private val minReservedDiskBytes: Long?,
    private val logger: NimbusLogger?,
    private val autoStart: Boolean,
    /**
     * Whether [close] may cancel the scope. False when the caller supplied their own: theirs
     * to end, and cancelling it would take down whatever else they run in it.
     */
    private val ownsDownloadScope: Boolean,
    downloadScope: CoroutineScope
) : NimbusAPI {
    private val lockMapMutex = Mutex()
    private val operationLocks = mutableMapOf<String, OperationLock>()

    /** A [Mutex] paired with a reference count of coroutines currently holding or waiting on it. */
    private class OperationLock {
        val mutex = Mutex()
        var refCount = 0
    }

    private val loadMutex = Mutex()
    private var isLoaded = false

    private val closeMutex = Mutex()
    private var isClosed = false

    private val scope = downloadScope

    /** Called by [io.github.giovanniandreuzza.nimbus.Nimbus.init] to kick off background loading. */
    internal fun startLoad() {
        scope.launch { ensureLoaded() }
    }

    /**
     * Loads the persisted tasks once, on the first call that needs them.
     *
     * Only a **successful** load is remembered: a failure is retried by the next
     * call. Caching the failure would disable the whole API for the rest of the
     * process — on an unattended device that means no download can ever start
     * again until the app data is wiped by hand.
     */
    private suspend fun ensureLoaded(): KResult<Unit, NimbusError> = loadMutex.withLock {
        if (isLoaded) {
            return@withLock Success(Unit)
        }

        loadDownloadTasks().onSuccess { isLoaded = true }
    }

    /**
     * Runs [block] only after loading has completed successfully.
     * Returns [NimbusError.InitializationFailed] immediately if loading failed.
     */
    private suspend fun <T> withReady(
        block: suspend () -> KResult<T, NimbusError>
    ): KResult<T, NimbusError> {
        if (isClosed) {
            return Failure(NimbusError.PermanentError(PermanentNimbusErrorCause.Closed))
        }
        ensureLoaded().onFailure { return Failure(it) }
        return block()
    }

    override suspend fun flush(): KResult<Unit, NimbusError> = withReady {
        repository.flushPendingState().onFailure {
            return@withReady Failure(
                NimbusError.PermanentError(PermanentNimbusErrorCause.StorageError(it))
            )
        }
        Success(Unit)
    }

    /**
     * Stop, commit, release — in that order, because each step decides what the next one sees.
     *
     * Stopping first means nothing is still writing a file or reporting a state while the
     * store is being committed, so what reaches the disk is what the next boot will read.
     * Releasing last means the commit still has a scope to run in.
     *
     * Failures here are deliberately not returned. There is nothing a caller can do with them
     * at this point — the process is going away, which is why they called — and a `close` that
     * can fail is a `close` people wrap in a `try` and get wrong. What can be reported is
     * reported to the logger.
     */
    override suspend fun close() {
        closeMutex.withLock {
            if (isClosed) return
            isClosed = true
        }

        downloadPort.stopAllDownloads()

        repository.flushPendingState().onFailure {
            logger?.log(NimbusLogEvent.StoreFlushFailed(it))
        }

        if (ownsDownloadScope) scope.cancel()
    }

    private suspend fun loadDownloadTasks(): KResult<Unit, NimbusError> {
        repository.loadDownloadTasks().onFailure {
            return Failure(
                NimbusError.PermanentError(
                    PermanentNimbusErrorCause.InitializationFailed(
                        it
                    )
                )
            )
        }
        return Success(Unit)
    }

    override suspend fun isDownloaded(fileUrl: String): Boolean {
        if (isClosed) return false
        ensureLoaded().onFailure { return false }
        val id = idProvider.generateUniqueId(fileUrl)
        val finished = repository.readDownloadTask(DownloadId.create(id)) { task ->
            if (task.state is DownloadState.Finished) {
                task.filePath.value to task.fileSize.value
            } else {
                null
            }
        }.getOr { return false }

        // Verify the file still exists on disk with the expected size.
        // The in-memory state alone is insufficient: the file could have been
        // deleted externally after the download completed.
        val actualSize = storagePort.size(finished.first).getOr { return false }
        return actualSize == finished.second
    }

    // Intentionally does not call withReady: this method only issues a HEAD request
    // via downloadPort and never touches the repository, so there is no need to wait
    // for the loading gate to complete.
    override suspend fun getFileSize(fileUrl: String): KResult<Long, NimbusError> {
        val size = downloadPort.getFileSizeToDownload(fileUrl).getOr {
            return Failure(it.toNimbusError())
        }
        return Success(size)
    }

    override suspend fun getDownloadTask(fileUrl: String): KResult<DownloadTaskDTO, NimbusError> =
        withReady {
            val id = idProvider.generateUniqueId(fileUrl)
            val dto = repository.readDownloadTask(DownloadId.create(id)) {
                DownloadTaskDTO.fromDomain(it)
            }.getOr {
                return@withReady Failure(NimbusError.PermanentError(PermanentNimbusErrorCause.DownloadNotFound))
            }
            Success(dto)
        }

    override suspend fun getAllDownloads(): KResult<List<DownloadTaskDTO>, NimbusError> =
        withReady {
            Success(repository.getAllDownloadTasks())
        }

    override fun observeAllDownloads(): Flow<List<DownloadTaskDTO>> =
        repository.observeAllDownloadTasks()

    override suspend fun enqueueDownload(
        fileUrl: String,
        filePath: String,
        fileName: String,
        expectedChecksum: Checksum?
    ): KResult<DownloadTaskDTO, NimbusError> = withReady {
        validateEnqueueRequest(
            fileUrl,
            filePath,
            fileName,
            expectedChecksum
        ).onFailure { return@withReady Failure(it) }

        val id = idProvider.generateUniqueId(fileUrl)

        withOperationLock(id) {
            repository.readDownloadTask(DownloadId.create(id)) { it.state }
                .onSuccess { existingState ->
                    return@withOperationLock Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.InvalidState(existingState)
                        )
                    )
                }

            if (repository.isFilePathInUse(filePath)) {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.FilePathInUse(filePath)
                    )
                )
            }

            val fileSize = downloadPort.getFileSizeToDownload(fileUrl).getOr {
                return@withOperationLock Failure(it.toNimbusError())
            }
            validateFileSize(fileSize).onFailure { return@withOperationLock Failure(it) }

            ensureDiskHeadroom(
                fileUrl = fileUrl,
                filePath = filePath,
                expectedFileSize = fileSize,
                partialBytesOnDisk = 0L
            )
                .getOr { return@withOperationLock Failure(it) }

            val task = DownloadTask.create(
                id = id,
                fileUrl = fileUrl,
                filePath = filePath,
                fileName = fileName,
                fileSize = fileSize,
                expectedChecksum = expectedChecksum
            )

            repository.saveDownloadTask(task).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }

            logger?.log(
                NimbusLogEvent.DownloadEnqueued(
                    fileUrl = fileUrl,
                    filePath = filePath,
                    expectedSizeBytes = fileSize
                )
            )

            if (autoStart) {
                scope.launch {
                    startDownload(fileUrl).onFailure {
                        logger?.log(
                            NimbusLogEvent.AutoStartFailed(
                                fileUrl,
                                it
                            )
                        )
                    }
                }
            }

            Success(DownloadTaskDTO.fromDomain(task))
        }
    }

    override suspend fun startDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val downloadId = DownloadId.create(id)
            val snapshot = repository.readDownloadTask(downloadId) {
                DownloadTaskDTO.fromDomain(it)
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            // The expectation was checked when the task was created, but a task outlives the
            // build that created it: a store written while a digest was configured is read
            // back by a build where it is not, and nothing downstream would say so.
            validateChecksumExpectation(snapshot.expectedChecksum)
                .onFailure { return@withOperationLock Failure(it) }

            ensureDiskHeadroom(
                fileUrl = fileUrl,
                filePath = snapshot.filePath,
                expectedFileSize = snapshot.fileSize,
                partialBytesOnDisk = partialBytesOnDiskForPath(
                    snapshot.filePath,
                    snapshot.fileSize
                )
            ).getOr { return@withOperationLock Failure(it) }

            if (snapshot.state is DownloadState.Downloading) {
                logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
                downloadPort.startDownload(snapshot).onFailure {
                    return@withOperationLock Failure(it.toNimbusError())
                }
                return@withOperationLock Success(Unit)
            }

            val started = repository.transitionDownloadTask(downloadId) { task ->
                val transitioned = when (task.state) {
                    is DownloadState.Paused -> task.resume()
                    DownloadState.Enqueued -> task.start()
                    else -> false
                }
                if (transitioned) DownloadTaskDTO.fromDomain(task) else null
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
            downloadPort.startDownload(started).onFailure {
                return@withOperationLock Failure(it.toNimbusError())
            }

            Success(Unit)
        }
    }

    override suspend fun observeDownload(fileUrl: String): KResult<Flow<DownloadState>, NimbusError> =
        withReady {
            val id = idProvider.generateUniqueId(fileUrl)
            val flow = repository.observeDownloadTask(DownloadId.create(id)).getOr {
                return@withReady Failure(NimbusError.PermanentError(PermanentNimbusErrorCause.DownloadNotFound))
            }
            Success(flow.takeUntil { it.endsTheFlow() })
        }

    /**
     * Whether a flow watching one download should stop at this state.
     *
     * Finished and Cancelled always end it. A failure depends on what happens next: without
     * `autoStart` nothing does, so it ends the flow. With `autoStart` a *temporary* failure is
     * about to be retried and the flow has to survive the `Failed → Enqueued → Downloading`
     * cycle — but a permanent one will never be retried, and leaving the flow open there is a
     * caller suspended forever. A reconciliation loop that awaits each asset in a manifest
     * meets exactly that on the first asset the backend has removed: one 404 and the loop
     * stops, with every other file it was supposed to keep current left behind it.
     *
     * One case remains open by design: a *capped* auto-retry policy that runs out still leaves
     * the task `Failed` with a temporary cause and nothing else to emit. The default policy is
     * unbounded, and a caller who sets a cap should watch
     * [NimbusLogEvent.AutoRetryExhausted] for the end of it.
     */
    private fun DownloadState.endsTheFlow(): Boolean = when (this) {
        DownloadState.Finished,
        DownloadState.Cancelled -> true

        is DownloadState.Failed -> !autoStart || error is DownloadError.PermanentError

        DownloadState.Enqueued,
        is DownloadState.Downloading,
        is DownloadState.Paused -> false
    }

    override suspend fun pauseDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            // The transfer is stopped before the state changes, not after. Progress callbacks
            // run on the download's own coroutine, so while that coroutine is alive it can
            // still report — and a tick landing after `pause()` wrote `Downloading` straight
            // back over it, leaving a task persisted as downloading with no job behind it.
            // `stopDownload` joins the job, so once it returns nothing else will touch this
            // task.
            //
            val downloadId = DownloadId.create(id)

            // Asked first only so that a task with nothing running is refused without being
            // stopped. The check that decides anything is the transition below, which the
            // entity makes under the repository's lock.
            repository.readDownloadTask(downloadId) { task ->
                if (task.state is DownloadState.Downloading) Unit else null
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            downloadPort.stopDownload(id)

            repository.transitionDownloadTask(downloadId) { task ->
                if (task.pause()) Unit else null
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            Success(Unit)
        }
    }

    override suspend fun resumeDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val downloadId = DownloadId.create(id)
            val snapshot = repository.readDownloadTask(downloadId) {
                DownloadTaskDTO.fromDomain(it)
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            // The expectation was checked when the task was created, but a task outlives the
            // build that created it: a store written while a digest was configured is read
            // back by a build where it is not, and nothing downstream would say so.
            validateChecksumExpectation(snapshot.expectedChecksum)
                .onFailure { return@withOperationLock Failure(it) }

            ensureDiskHeadroom(
                fileUrl = fileUrl,
                filePath = snapshot.filePath,
                expectedFileSize = snapshot.fileSize,
                partialBytesOnDisk = partialBytesOnDiskForPath(
                    snapshot.filePath,
                    snapshot.fileSize
                )
            ).getOr { return@withOperationLock Failure(it) }

            if (snapshot.state is DownloadState.Downloading) {
                logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
                downloadPort.startDownload(snapshot).onFailure {
                    return@withOperationLock Failure(it.toNimbusError())
                }
                return@withOperationLock Success(Unit)
            }

            val resumed = repository.transitionDownloadTask(downloadId) { task ->
                if (task.resume()) DownloadTaskDTO.fromDomain(task) else null
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
            downloadPort.startDownload(resumed).onFailure {
                return@withOperationLock Failure(it.toNimbusError())
            }

            Success(Unit)
        }
    }

    override suspend fun cancelDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val downloadId = DownloadId.create(id)
            val filePath = repository.readDownloadTask(downloadId) { it.filePath.value }
                .getOr { return@withOperationLock Failure(it.toNimbusError()) }

            downloadPort.stopDownload(id)

            // Published, not persisted: the task is about to be deleted, and the point of the
            // transition is that an observer sees `Cancelled` before the flow completes.
            repository.transitionDownloadTask(downloadId, persist = false) { task ->
                task.cancel()
            }

            repository.deleteDownloadTask(downloadId).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }
            storagePort.delete(filePath).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(PermanentNimbusErrorCause.StorageError(it))
                )
            }

            Success(Unit)
        }
    }

    override suspend fun removeDownload(
        fileUrl: String,
        deleteAssociatedFile: Boolean
    ): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val downloadId = DownloadId.create(id)
            // The state check belongs inside the read: asking whether a task may be removed
            // and then removing it are one decision, and between two statements they are two.
            val path = repository.readDownloadTask(downloadId) { task ->
                val settled = task.state is DownloadState.Finished ||
                        task.state is DownloadState.Failed
                if (settled) task.filePath.value else null
            }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

            repository.deleteDownloadTask(downloadId).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }

            if (deleteAssociatedFile) {
                storagePort.delete(path).onFailure {
                    return@withOperationLock Failure(
                        NimbusError.PermanentError(PermanentNimbusErrorCause.StorageError(it))
                    )
                }
            }

            Success(Unit)
        }
    }

    /**
     * Applies [expectedChecksum] to a task that already exists.
     *
     * A task still in flight simply adopts it: nothing has been verified yet, so the latest
     * word is the one the transfer should be checked against. A finished task cannot — its
     * file was accepted against the old expectation, and rewriting the expectation would
     * describe bytes nobody checked. Asking for different bytes at a url that has already
     * been satisfied makes what is on disk stale, so it is removed and downloaded again,
     * the same conclusion this method already reaches when a finished file has vanished.
     */
    private suspend fun carryExpectationToExistingTask(
        fileUrl: String,
        expectedChecksum: Checksum
    ): KResult<Unit, NimbusError> {
        val id = DownloadId.create(idProvider.generateUniqueId(fileUrl))
        val held = repository.readDownloadTask(id) { it.expectedChecksum ?: NoExpectation }
            .getOr { return Success(Unit) }
        if (held == expectedChecksum) return Success(Unit)

        repository.transitionDownloadTask(id) { task ->
            if (task.updateExpectedChecksum(expectedChecksum)) Unit else null
        }.onFailure { failure ->
            // Refused means the task has finished: its file was accepted against the old
            // expectation, so what is on disk is stale rather than mislabelled.
            if (failure !is TransitionFailure.Refused) return Failure(failure.toNimbusError())

            removeDownload(fileUrl, deleteAssociatedFile = true)
                .onFailure { return Failure(it) }
            logger?.log(NimbusLogEvent.EnsureDownloadedStaleFinishedRemoved(fileUrl))
        }

        return Success(Unit)
    }

    /**
     * Stands in for "this task expects nothing", because a locked read reports null as a
     * refusal and a task without an expectation is not a refusal.
     */
    private object NoExpectation

    override suspend fun ensureDownloaded(
        fileUrl: String,
        filePath: String,
        fileName: String,
        expectedChecksum: Checksum?
    ): KResult<Flow<DownloadState>, NimbusError> = withReady {
        validateEnqueueRequest(
            fileUrl,
            filePath,
            fileName,
            expectedChecksum
        ).onFailure { return@withReady Failure(it) }

        // Carry the caller's expectation into a task that already exists, before anything
        // below can report the file complete. Forwarding it only to enqueueDownload would
        // honour it exactly where the caller could have called enqueueDownload themselves,
        // and drop it everywhere they reached for this method instead.
        if (expectedChecksum != null) {
            carryExpectationToExistingTask(fileUrl, expectedChecksum)
                .onFailure { return@withReady Failure(it) }
        }

        // What the caller asked for, or — when they asked for nothing — what the task was
        // already carrying. The stale-Finished branch below removes the task and enqueues a
        // replacement, and enqueueing with the argument alone drops an expectation the store
        // was holding: the new transfer then runs unverified with nobody told, which is the
        // silence this whole change exists to end. It is carried only when this build could
        // honour it — a stale expectation from a build that had a digest must not wall off a
        // build that has none, since the task is being recreated from scratch anyway and the
        // caller asked for no verification.
        var expectation = expectedChecksum

        // The loop only continues when a stale Finished task is removed (at most once),
        // after which the task no longer exists and the loop exits via the Failure branch.
        // A limit of 2 prevents an infinite loop if the state machine behaves unexpectedly.
        repeat(2) {
            if (isDownloaded(fileUrl)) {
                logger?.log(NimbusLogEvent.EnsureDownloadedAlreadyComplete(fileUrl))
                return@withReady Success(flowOf(DownloadState.Finished))
            }

            when (val taskRes = getDownloadTask(fileUrl)) {
                is Success -> {
                    val dto = taskRes.value
                    if (dto.state is DownloadState.Finished) {
                        val stored = dto.expectedChecksum
                        if (expectation == null &&
                            stored != null &&
                            validateChecksumExpectation(stored) is Success
                        ) {
                            expectation = stored
                        }
                        removeDownload(
                            fileUrl,
                            deleteAssociatedFile = true
                        ).getOr { return@withReady Failure(it) }
                        logger?.log(NimbusLogEvent.EnsureDownloadedStaleFinishedRemoved(fileUrl))
                        return@repeat
                    }
                    if (dto.state is DownloadState.Failed) {
                        retryFailedDownload(fileUrl).getOr { return@withReady Failure(it) }
                    }
                    // Disk headroom is re-checked inside startDownload.
                    startDownload(fileUrl).getOr { return@withReady Failure(it) }
                    return@withReady observeDownload(fileUrl)
                }

                is Failure -> {
                    val taskError = taskRes.error
                    val isDownloadNotFound = taskError is NimbusError.PermanentError &&
                            taskError.errorCause is PermanentNimbusErrorCause.DownloadNotFound
                    if (!isDownloadNotFound) {
                        return@withReady Failure(taskError)
                    }
                    // enqueueDownload fetches the remote size and checks disk headroom
                    // internally; startDownload re-checks headroom before issuing the request.
                    enqueueDownload(
                        fileUrl,
                        filePath,
                        fileName,
                        expectation
                    ).getOr { return@withReady Failure(it) }
                    startDownload(fileUrl).getOr { return@withReady Failure(it) }
                    return@withReady observeDownload(fileUrl)
                }
            }
        }
        Failure(
            NimbusError.PermanentError(
                PermanentNimbusErrorCause.UnexpectedError(
                    KError(
                        "ensure_downloaded_loop",
                        "ensureDownloaded exceeded maximum iterations."
                    )
                )
            )
        )
    }

    override suspend fun checksum(fileUrl: String): KResult<Checksum, NimbusError> = withReady {
        val algorithm = digestAlgorithm ?: return@withReady Failure(
            NimbusError.PermanentError(PermanentNimbusErrorCause.ContentDigestDisabled)
        )

        val id = idProvider.generateUniqueId(fileUrl)
        val filePath = repository.readDownloadTask(DownloadId.create(id)) { task ->
            if (task.state is DownloadState.Finished) task.filePath.value else null
        }.getOr { return@withReady Failure(it.toNimbusError()) }

        contentDigestPort.digestOf(filePath, algorithm).getOr {
            return@withReady Failure(
                NimbusError.PermanentError(PermanentNimbusErrorCause.StorageError(it))
            )
        }.let { Success(it) }
    }

    override suspend fun retryFailedDownload(fileUrl: String): KResult<Unit, NimbusError> =
        withReady {
            val id = idProvider.generateUniqueId(fileUrl)
            withOperationLock(id) {
                val downloadId = DownloadId.create(id)
                val failed = repository.readDownloadTask(downloadId) { task ->
                    val failure = task.state as? DownloadState.Failed
                    if (failure == null) {
                        null
                    } else {
                        FailedSnapshot(
                            filePath = task.filePath.value,
                            previousSize = task.fileSize.value,
                            error = failure.error
                        )
                    }
                }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

                val newSize = downloadPort.getFileSizeToDownload(fileUrl).getOr {
                    return@withOperationLock Failure(it.toNimbusError())
                }
                validateFileSize(newSize).onFailure { return@withOperationLock Failure(it) }

                // Keep the partial only when nothing about this retry can invalidate it.
                //
                // Discarding it is the safe default and was the only behaviour until now, but
                // on a device downloading a large file over a link that keeps dropping it is
                // also the expensive one: the transfer restarts from zero every time, which is
                // the bandwidth this library exists to stop wasting. The transport failing
                // says nothing about the bytes already written — they are what the server
                // sent, in order — so a resume picks up from them correctly.
                //
                // Deliberately a whitelist. A checksum mismatch leaves a file of exactly the
                // right length and the wrong content, and resuming into it would re-verify the
                // same wrong bytes on every attempt and never converge. A cause added later
                // therefore falls through to discarding, which is the old behaviour, rather
                // than silently keeping a file nobody has reasoned about.
                val resumable = newSize == failed.previousSize &&
                        failed.error.isAboutTheTransport()

                if (!resumable) {
                    storagePort.delete(failed.filePath).onFailure { deleteError ->
                        return@withOperationLock Failure(
                            NimbusError.PermanentError(
                                PermanentNimbusErrorCause.StorageError(deleteError)
                            )
                        )
                    }
                    storagePort.create(failed.filePath).onFailure { createError ->
                        return@withOperationLock Failure(
                            NimbusError.PermanentError(
                                PermanentNimbusErrorCause.StorageError(createError)
                            )
                        )
                    }
                }

                // The new size and the reset are one transition. As two they were two windows:
                // a failure landing from a job that had already deregistered itself could write
                // `Failed` back between them, and the reset this method exists to perform would
                // be the change that got lost.
                repository.transitionDownloadTask(downloadId) { task ->
                    if (!task.updateExpectedFileSize(newSize)) return@transitionDownloadTask null
                    if (!task.resetFromFailedToEnqueued()) return@transitionDownloadTask null
                    Unit
                }.getOr { return@withOperationLock Failure(it.toNimbusError()) }

                Success(Unit)
            }
        }

    /** What `retryFailedDownload` needs to know about the task it is bringing back. */
    private class FailedSnapshot(
        val filePath: String,
        val previousSize: Long,
        val error: DownloadError
    )

    /**
     * Whether [this] failure is about the link or the server rather than the bytes on disk.
     *
     * Only these two are unambiguous. Both describe the other end of the connection, and
     * neither can be a statement about what was already written locally, so a partial file
     * left behind by one of them is still a valid prefix of the target.
     */
    private fun DownloadError.isAboutTheTransport(): Boolean =
        this is DownloadError.TemporaryError &&
                (errorCause is TemporaryDownloadErrorCause.TransportFailure ||
                        errorCause is TemporaryDownloadErrorCause.ServerError)

    private fun partialBytesOnDiskForPath(filePath: String, expectedSize: Long): Long {
        return when (val s = storagePort.size(filePath)) {
            is Success -> s.value.coerceIn(0L, expectedSize)
            is Failure -> 0L
        }
    }

    private suspend fun ensureDiskHeadroom(
        fileUrl: String?,
        filePath: String,
        expectedFileSize: Long,
        partialBytesOnDisk: Long
    ): KResult<Unit, NimbusError> {
        val minReserved = minReservedDiskBytes ?: return Success(Unit)
        val remaining = (expectedFileSize - partialBytesOnDisk).coerceAtLeast(0L)
        val required = remaining + minReserved
        when (val space = storagePort.usableSpaceBytes(filePath)) {
            is Failure -> {
                return Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(space.error)
                    )
                )
            }

            is Success -> {
                // A platform that cannot report free space answers null; skip the check
                // rather than refuse the download.
                val available = space.value ?: return Success(Unit)
                if (available < required) {
                    logger?.log(
                        NimbusLogEvent.InsufficientDiskSpace(
                            fileUrl = fileUrl,
                            path = filePath,
                            requiredBytes = required,
                            availableBytes = available
                        )
                    )
                    return Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.InsufficientDiskSpace(
                                path = filePath,
                                requiredBytes = required,
                                availableBytes = available
                            )
                        )
                    )
                }
            }
        }
        return Success(Unit)
    }

    private suspend fun <T> withOperationLock(
        id: String,
        block: suspend () -> KResult<T, NimbusError>
    ): KResult<T, NimbusError> {
        // Increment the ref-count while holding the map lock so the entry is never
        // removed while another coroutine is about to use the same Mutex.
        val lock = lockMapMutex.withLock {
            val entry = operationLocks.getOrPut(id) { OperationLock() }
            entry.refCount++
            entry
        }
        return try {
            lock.mutex.withLock { block() }
        } finally {
            // Decrement and prune: once nobody else holds or is waiting on this lock,
            // the entry can be removed to prevent the map from growing without bound.
            lockMapMutex.withLock {
                lock.refCount--
                if (lock.refCount == 0) operationLocks.remove(id)
            }
        }
    }

    private fun validateEnqueueRequest(
        fileUrl: String,
        filePath: String,
        fileName: String,
        expectedChecksum: Checksum?
    ): KResult<Unit, NimbusError> {
        if (!fileUrl.hasUriScheme()) return Failure(
            NimbusError.PermanentError(
                PermanentNimbusErrorCause.InvalidUrl
            )
        )
        if (filePath.isBlank() || filePath.containsPathTraversal()) return Failure(
            NimbusError.PermanentError(
                PermanentNimbusErrorCause.InvalidPath
            )
        )
        if (!fileName.isValidFileName()) return Failure(
            NimbusError.PermanentError(
                PermanentNimbusErrorCause.InvalidFileName
            )
        )
        validateChecksumExpectation(expectedChecksum).onFailure { return Failure(it) }
        return Success(Unit)
    }

    /**
     * Rejects an expectation the configured digest could never check.
     *
     * A caller passing `expectedChecksum` has asked for a verification, and until now was
     * told nothing when it did not happen: with no algorithm configured nothing is hashed,
     * the comparison has no value to compare against and is skipped, and the download
     * reports finished. The caller believes bytes were checked that nobody read — the
     * silently wrong answer this library prefers a loud failure to, and an asymmetry with
     * `checksum`, which has always answered
     * [PermanentNimbusErrorCause.ContentDigestDisabled] for the same configuration.
     *
     * A checksum naming a different algorithm is the same mistake with a worse ending: the
     * two values are compared, never agree, and the transfer is failed as a mismatch —
     * temporary, so it is retried, and the whole file is fetched again on every pass, for a
     * condition no retry can change.
     *
     * Both are settled before a task exists rather than at the point of comparison: the
     * adapter sees only a task, and a task that cannot be verified should never have been
     * created. The start and resume paths re-check, because a task outlives the build that
     * created it.
     */
    private fun validateChecksumExpectation(
        expectedChecksum: Checksum?
    ): KResult<Unit, NimbusError> {
        if (expectedChecksum == null) return Success(Unit)

        val configured = digestAlgorithm ?: return Failure(
            NimbusError.PermanentError(PermanentNimbusErrorCause.ContentDigestDisabled)
        )

        if (expectedChecksum.algorithm != configured) return Failure(
            NimbusError.PermanentError(
                PermanentNimbusErrorCause.ChecksumAlgorithmMismatch(
                    expected = expectedChecksum.algorithm,
                    configured = configured
                )
            )
        )

        return Success(Unit)
    }

    private fun validateFileSize(fileSize: Long): KResult<Unit, NimbusError> {
        if (fileSize <= 0L) {
            return Failure(
                NimbusError.PermanentError(
                    PermanentNimbusErrorCause.InvalidFileSize(
                        KError("invalid_file_size", "Remote file size must be > 0.")
                    )
                )
            )
        }
        return Success(Unit)
    }
}

// ---------------------------------------------------------------------------
// Private error-mapping extensions
// ---------------------------------------------------------------------------

private fun String.containsPathTraversal(): Boolean =
    split('/', '\\').any { it == ".." }

/**
 * Whether this looks like a URI at all — syntax only, never which transport it names.
 *
 * Core used to require http or https here, which put a transport decision in the one layer
 * that is supposed to know nothing about transports: it made [NimbusDownloadPort] unusable
 * for ftp, a local share, or anything else, however capable the adapter was, because the
 * request was refused before the port was ever asked. Which schemes exist is the port's
 * business; an adapter that does not recognise one fails it.
 *
 * What is still checked is that the string carries a scheme, per RFC 3986:
 * `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":"`. That is not a judgement about
 * transports — the url is also the task's identity, so a string that is not a URL becomes a
 * task nobody can address.
 */
private fun String.hasUriScheme(): Boolean {
    val colon = indexOf(':')
    if (colon <= 0) return false
    val scheme = substring(0, colon)
    // ASCII explicitly: Kotlin's Char.isLetter answers for the whole of Unicode, so writing
    // this with it would accept 'é://host' — a scheme no URI parser recognises, stored as a
    // task nobody can address.
    return scheme[0].isAsciiAlpha() &&
            scheme.all { it.isAsciiAlpha() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
}

private fun Char.isAsciiAlpha(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun String.isValidFileName(): Boolean {
    if (isBlank() || this == "." || this == "..") return false
    if (contains('/') || contains('\\') || contains('\u0000')) return false
    return none { it.code < 32 }
}
