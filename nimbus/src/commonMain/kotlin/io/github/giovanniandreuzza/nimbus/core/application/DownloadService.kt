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
        ensureLoaded().onFailure { return Failure(it) }
        return block()
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
        ensureLoaded().onFailure { return false }
        val id = idProvider.generateUniqueId(fileUrl)
        val task = repository.getDownloadTask(DownloadId.create(id)).getOr { return false }
        if (task.state !is DownloadState.Finished) return false

        // Verify the file still exists on disk with the expected size.
        // The in-memory state alone is insufficient: the file could have been
        // deleted externally after the download completed.
        val actualSize = storagePort.size(task.filePath.value).getOr { return false }
        return actualSize == task.fileSize.value
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
            val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                return@withReady Failure(NimbusError.PermanentError(PermanentNimbusErrorCause.DownloadNotFound))
            }
            Success(DownloadTaskDTO.fromDomain(task))
        }

    override suspend fun getAllDownloads(): KResult<List<DownloadTaskDTO>, NimbusError> =
        withReady {
            Success(repository.getAllDownloadTask().values.map { DownloadTaskDTO.fromDomain(it) })
        }

    override fun observeAllDownloads(): Flow<List<DownloadTaskDTO>> =
        repository.observeAllDownloadTasks().map { tasks ->
            tasks.map { DownloadTaskDTO.fromDomain(it) }
        }

    override suspend fun enqueueDownload(
        fileUrl: String,
        filePath: String,
        fileName: String,
        expectedChecksum: Checksum?
    ): KResult<DownloadTaskDTO, NimbusError> = withReady {
        validateEnqueueRequest(
            fileUrl,
            filePath,
            fileName
        ).onFailure { return@withReady Failure(it) }

        val id = idProvider.generateUniqueId(fileUrl)

        withOperationLock(id) {
            repository.getDownloadTask(DownloadId.create(id)).onSuccess { existing ->
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.InvalidState(existing.state)
                    )
                )
            }

            val pathBusy =
                repository.getAllDownloadTask().values.any { it.filePath.value == filePath }
            if (pathBusy) {
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
            val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.DownloadNotFound
                    )
                )
            }

            ensureDiskHeadroom(
                fileUrl = fileUrl,
                filePath = task.filePath.value,
                expectedFileSize = task.fileSize.value,
                partialBytesOnDisk = partialBytesOnDiskForPath(
                    task.filePath.value,
                    task.fileSize.value
                )
            ).getOr { return@withOperationLock Failure(it) }

            if (task.state is DownloadState.Downloading) {
                logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
                downloadPort.startDownload(DownloadTaskDTO.fromDomain(task)).onFailure {
                    return@withOperationLock Failure(it.toNimbusError())
                }
                return@withOperationLock Success(Unit)
            }

            val transitioned = when (task.state) {
                is DownloadState.Paused -> task.resume()
                DownloadState.Enqueued -> task.start()
                else -> false
            }
            if (!transitioned) {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.InvalidState(task.state)
                    )
                )
            }

            repository.saveDownloadTask(task).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }

            logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
            downloadPort.startDownload(DownloadTaskDTO.fromDomain(task)).onFailure {
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
            // When autoStart is enabled the library retries automatically after failure,
            // so the flow must stay alive through the Failed → Enqueued → Downloading cycle.
            // It only completes on Finished (or also on Failed when not auto-retrying).
            Success(
                if (autoStart) {
                    flow.takeUntil { it is DownloadState.Finished || it is DownloadState.Cancelled }
                } else {
                    flow.takeUntil { it is DownloadState.Finished || it is DownloadState.Failed || it is DownloadState.Cancelled }
                }
            )
        }

    override suspend fun pauseDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.DownloadNotFound
                    )
                )
            }

            if (!task.pause()) {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.InvalidState(task.state)
                    )
                )
            }

            downloadPort.stopDownload(id)

            repository.saveDownloadTask(task).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }

            Success(Unit)
        }
    }

    override suspend fun resumeDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.DownloadNotFound
                    )
                )
            }

            ensureDiskHeadroom(
                fileUrl = fileUrl,
                filePath = task.filePath.value,
                expectedFileSize = task.fileSize.value,
                partialBytesOnDisk = partialBytesOnDiskForPath(
                    task.filePath.value,
                    task.fileSize.value
                )
            ).getOr { return@withOperationLock Failure(it) }

            if (task.state is DownloadState.Downloading) {
                logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
                downloadPort.startDownload(DownloadTaskDTO.fromDomain(task)).onFailure {
                    return@withOperationLock Failure(it.toNimbusError())
                }
                return@withOperationLock Success(Unit)
            }

            if (!task.resume()) {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.InvalidState(task.state)
                    )
                )
            }

            repository.saveDownloadTask(task).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }

            logger?.log(NimbusLogEvent.DownloadStartRequested(fileUrl))
            downloadPort.startDownload(DownloadTaskDTO.fromDomain(task)).onFailure {
                return@withOperationLock Failure(it.toNimbusError())
            }

            Success(Unit)
        }
    }

    override suspend fun cancelDownload(fileUrl: String): KResult<Unit, NimbusError> = withReady {
        val id = idProvider.generateUniqueId(fileUrl)
        withOperationLock(id) {
            val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.DownloadNotFound
                    )
                )
            }

            downloadPort.stopDownload(id)
            task.cancel()
            repository.updateDownloadProgress(task)
            repository.deleteDownloadTask(DownloadId.create(id)).onFailure {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.StorageError(it)
                    )
                )
            }
            storagePort.delete(task.filePath.value).onFailure {
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
            val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.DownloadNotFound
                    )
                )
            }

            if (task.state !is DownloadState.Finished && task.state !is DownloadState.Failed) {
                return@withOperationLock Failure(
                    NimbusError.PermanentError(
                        PermanentNimbusErrorCause.InvalidState(task.state)
                    )
                )
            }

            val path = task.filePath.value
            repository.deleteDownloadTask(DownloadId.create(id)).onFailure {
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
        val task = repository.getDownloadTask(id).getOr { return Success(Unit) }
        if (task.expectedChecksum == expectedChecksum) return Success(Unit)

        if (task.updateExpectedChecksum(expectedChecksum)) {
            repository.saveDownloadTask(task).onFailure {
                return Failure(
                    NimbusError.PermanentError(PermanentNimbusErrorCause.StorageError(it))
                )
            }
            return Success(Unit)
        }

        removeDownload(fileUrl, deleteAssociatedFile = true)
            .onFailure { return Failure(it) }
        logger?.log(NimbusLogEvent.EnsureDownloadedStaleFinishedRemoved(fileUrl))
        return Success(Unit)
    }

    override suspend fun ensureDownloaded(
        fileUrl: String,
        filePath: String,
        fileName: String,
        expectedChecksum: Checksum?
    ): KResult<Flow<DownloadState>, NimbusError> = withReady {
        validateEnqueueRequest(
            fileUrl,
            filePath,
            fileName
        ).onFailure { return@withReady Failure(it) }

        // Carry the caller's expectation into a task that already exists, before anything
        // below can report the file complete. Forwarding it only to enqueueDownload would
        // honour it exactly where the caller could have called enqueueDownload themselves,
        // and drop it everywhere they reached for this method instead.
        if (expectedChecksum != null) {
            carryExpectationToExistingTask(fileUrl, expectedChecksum)
                .onFailure { return@withReady Failure(it) }
        }

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
                        expectedChecksum
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
        val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
            return@withReady Failure(
                NimbusError.PermanentError(PermanentNimbusErrorCause.DownloadNotFound)
            )
        }

        if (task.state !is DownloadState.Finished) {
            return@withReady Failure(
                NimbusError.PermanentError(PermanentNimbusErrorCause.InvalidState(task.state))
            )
        }

        contentDigestPort.digestOf(task.filePath.value, algorithm).getOr {
            return@withReady Failure(
                NimbusError.PermanentError(PermanentNimbusErrorCause.StorageError(it))
            )
        }.let { Success(it) }
    }

    override suspend fun retryFailedDownload(fileUrl: String): KResult<Unit, NimbusError> =
        withReady {
            val id = idProvider.generateUniqueId(fileUrl)
            withOperationLock(id) {
                val task = repository.getDownloadTask(DownloadId.create(id)).getOr {
                    return@withOperationLock Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.DownloadNotFound
                        )
                    )
                }

                val failure = task.state as? DownloadState.Failed
                    ?: return@withOperationLock Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.InvalidState(task.state)
                        )
                    )

                val previousSize = task.fileSize.value

                val newSize = downloadPort.getFileSizeToDownload(fileUrl).getOr {
                    return@withOperationLock Failure(it.toNimbusError())
                }
                validateFileSize(newSize).onFailure { return@withOperationLock Failure(it) }

                if (!task.updateExpectedFileSize(newSize)) {
                    return@withOperationLock Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.UnexpectedError(
                                KError("update_size_failed", "Could not update expected file size.")
                            )
                        )
                    )
                }

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
                val resumable = newSize == previousSize && failure.error.isAboutTheTransport()

                if (!resumable) {
                    storagePort.delete(task.filePath.value).onFailure { deleteError ->
                        return@withOperationLock Failure(
                            NimbusError.PermanentError(
                                PermanentNimbusErrorCause.StorageError(deleteError)
                            )
                        )
                    }
                    storagePort.create(task.filePath.value).onFailure { createError ->
                        return@withOperationLock Failure(
                            NimbusError.PermanentError(
                                PermanentNimbusErrorCause.StorageError(createError)
                            )
                        )
                    }
                }

                if (!task.resetFromFailedToEnqueued()) {
                    return@withOperationLock Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.UnexpectedError(
                                KError("reset_failed", "Could not reset failed task.")
                            )
                        )
                    )
                }

                repository.saveDownloadTask(task).onFailure {
                    return@withOperationLock Failure(
                        NimbusError.PermanentError(
                            PermanentNimbusErrorCause.StorageError(it)
                        )
                    )
                }

                Success(Unit)
            }
        }

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
        fileName: String
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
