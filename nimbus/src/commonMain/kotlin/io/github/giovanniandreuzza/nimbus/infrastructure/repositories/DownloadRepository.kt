package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.frameworks.store.StoreManager
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.InitStoreError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.StoreError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toDomains
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadTaskStoreMappers.toStore
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Download Repository.
 *
 * Consolidated repository that keeps an authoritative in-memory map of all
 * [DownloadTask] objects and a [MutableStateFlow] per task for reactive
 * observation, backed by a disk [DiskStore] for persistence.
 *
 * Progress updates (hot path) are applied to memory only — the disk is **not**
 * written on every progress tick. All other mutations (enqueue, pause, resume,
 * finish, fail, cancel) are persisted to disk.
 *
 * @param storePath Path to the protobuf store file.
 * @param dispatcher [CoroutineDispatcher] used for disk I/O.
 * @param nimbusStoragePort Platform file-system abstraction.
 * @author Giovanni Andreuzza
 */
@OptIn(ExperimentalSerializationApi::class)
internal class DownloadRepository(
    storePath: String,
    dispatcher: CoroutineDispatcher,
    private val nimbusStoragePort: NimbusStoragePort,
    private val logger: NimbusLogger? = null,
    storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS
) : DownloadTaskRepository {

    private val mutex = Mutex()
    private val tasks = mutableMapOf<DownloadId, DownloadTask>()
    private val stateFlows = mutableMapOf<DownloadId, MutableStateFlow<DownloadState>>()

    /**
     * Monotonic counter bumped on every mutation, and the only thing the hot path pushes.
     *
     * [observeAllDownloadTasks] derives its snapshots from this instead of the repository
     * publishing a copy of the task map on every change. Two reasons, and both matter on the
     * devices this library targets:
     *
     * - **Correctness.** [DownloadTask] is an `Entity`, whose `equals` is identity on the id
     *   alone — a task that changed state is still equal to itself. A [MutableStateFlow]
     *   holding a map of tasks therefore conflates every state change away and emits only
     *   when the key set changes, so a list UI would see tasks appear and then never move.
     *   A revision number changes on every mutation, so nothing is conflated away.
     * - **Cost.** Copying the map on every progress tick allocates proportionally to the
     *   number of known tasks whether or not anyone is observing. Bumping a counter does not.
     */
    private val revision = MutableStateFlow(0L)
    private val diskStore = DiskStore(
        storePath = storePath,
        dispatcher = dispatcher,
        nimbusStoragePort = nimbusStoragePort,
        scope = storeScope,
        coalesceWindowMs = coalesceWindowMs,
        onReset = { reason -> logger?.log(NimbusLogEvent.StoreReset(reason)) },
        onBackgroundFlushFailed = { cause -> logger?.log(NimbusLogEvent.StoreFlushFailed(cause)) }
    )

    // -----------------------------------------------------------------------
    // DownloadTaskRepository implementation
    // -----------------------------------------------------------------------

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        diskStore.load().onFailure { return Failure(FailedToLoadDownloadTasks(it)) }

        // Recovery can rewrite every task it loads. Each rewrite is published in memory and
        // the whole set is committed once at the end, so booting a device holding hundreds
        // of tasks costs one commit rather than one per recovered task.
        var recovered = false

        mutex.withLock {
            diskStore.getAll().forEach { (id, task) ->
                when {
                    task.state is DownloadState.Downloading -> {
                        // Crash/restart recovery: Downloading tasks that were never
                        // paused are reset to Paused so they can be resumed cleanly.
                        task.pause()
                        diskStore.publish(task)
                        recovered = true
                    }

                    task.state is DownloadState.Finished -> {
                        // File-integrity check: if the finished file was deleted or
                        // corrupted between sessions, reset the task to Enqueued so
                        // it can be restarted without requiring a new enqueueDownload.
                        val actualSize = nimbusStoragePort.size(task.filePath.value)
                            .let { result -> if (result is Success) result.value else -1L }
                        if (actualSize != task.fileSize.value) {
                            task.resetToEnqueued()
                            diskStore.publish(task)
                            recovered = true
                        }
                    }
                }
                tasks[id] = task
                stateFlows[id] = MutableStateFlow(task.state)
            }
            revision.value++
        }

        if (recovered) {
            diskStore.flush().onFailure { return Failure(FailedToLoadDownloadTasks(it)) }
        }

        return Success(Unit)
    }

    /**
     * Commits anything still waiting for a coalesced write.
     *
     * Terminal states are already durable when their save returns, so this exists for the
     * moments when a caller knows the process may not survive long enough for the next
     * coalesced commit — an appliance being backgrounded or told to shut down.
     */
    internal suspend fun flushPendingState(): KResult<Unit, KError> = diskStore.flush()

    override suspend fun getDownloadTask(id: DownloadId): KResult<DownloadTask, DownloadTaskNotFound> {
        val task = mutex.withLock { tasks[id] }
        return task?.let { Success(it) } ?: Failure(DownloadTaskNotFound)
    }

    override suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask> {
        return mutex.withLock { tasks.toMap() }
    }

    override suspend fun observeDownloadTask(id: DownloadId): KResult<Flow<DownloadState>, DownloadTaskNotFound> {
        val flow = mutex.withLock { stateFlows[id] }
        return flow?.let { Success(it.asStateFlow()) } ?: Failure(DownloadTaskNotFound)
    }

    /**
     * Snapshots are built only when a collector is ready for one: [conflate] lets a slow
     * observer — a list UI redrawing at frame rate — skip the revisions it could not have
     * rendered anyway, so the cost of a snapshot is bounded by how fast it is consumed
     * rather than by how fast downloads report progress.
     */
    override fun observeAllDownloadTasks(): Flow<List<DownloadTask>> =
        revision.map { getAllDownloadTask().values.toList() }.conflate()

    override suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, KError> {
        mutex.withLock {
            tasks[downloadTask.entityId.id] = downloadTask
            stateFlows[downloadTask.entityId.id]?.update { downloadTask.state }
                ?: run {
                    stateFlows[downloadTask.entityId.id] = MutableStateFlow(downloadTask.state)
                }
            revision.value++
        }

        diskStore.save(downloadTask, durable = downloadTask.state.mustBeDurable())
            .onFailure { return Failure(it) }

        return Success(Unit)
    }

    /**
     * Whether reaching this state has to be on disk before the save returns.
     *
     * A commit rewrites the whole store, so committing every transition makes the cost of
     * one state change grow with the number of tasks the device has ever known. The states
     * that can wait are the ones the boot path can re-derive: `loadDownloadTasks` already
     * demotes a `Downloading` task to `Paused` and re-checks a `Finished` task against the
     * file on disk, and the resume offset comes from the partial file's length rather than
     * from anything recorded here. Losing an `Enqueued`, `Downloading` or `Paused` record to
     * a power cut therefore costs the caller a re-enqueue at worst, never a re-download.
     *
     * The terminal states carry information that is nowhere else — why a download failed,
     * that a file is complete and may be handed to the caller, that a task was cancelled on
     * purpose — so they are committed before the save returns.
     */
    private fun DownloadState.mustBeDurable(): Boolean = when (this) {
        is DownloadState.Finished,
        is DownloadState.Failed,
        is DownloadState.Cancelled -> true

        is DownloadState.Enqueued,
        is DownloadState.Downloading,
        is DownloadState.Paused -> false
    }

    override suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, KError> {
        mutex.withLock {
            tasks[downloadTask.entityId.id] = downloadTask
            stateFlows[downloadTask.entityId.id]?.update { downloadTask.state }
            revision.value++
        }
        return Success(Unit)
    }

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, KError> {
        mutex.withLock {
            tasks.remove(id)
            stateFlows.remove(id)
            revision.value++
        }

        diskStore.delete(id).onFailure { return Failure(it) }

        return Success(Unit)
    }

    // -----------------------------------------------------------------------
    // Private DiskStore
    // -----------------------------------------------------------------------

    private class DiskStore(
        storePath: String,
        dispatcher: CoroutineDispatcher,
        nimbusStoragePort: NimbusStoragePort,
        scope: CoroutineScope,
        coalesceWindowMs: Long,
        onReset: suspend (reason: String) -> Unit,
        private val onBackgroundFlushFailed: suspend (KError) -> Unit
    ) : StoreManager<DownloadStore>(
        filePath = storePath,
        nimbusStoragePort = nimbusStoragePort,
        serializer = DownloadStore.serializer(),
        dispatcher = dispatcher,
        onReset = onReset
    ) {

        /**
         * Carries at most one pending flush: a burst of coalescable saves collapses into a
         * single commit of the final value instead of one commit each.
         */
        private val flushRequests = Channel<Unit>(Channel.CONFLATED)

        init {
            scope.launch {
                for (request in flushRequests) {
                    // Let the burst finish before paying for it.
                    delay(coalesceWindowMs)
                    flush().onFailure { onBackgroundFlushFailed(it) }
                }
            }
        }

        suspend fun load(): KResult<Unit, InitStoreError> {
            val result = init(DownloadStore())
            if (result.isFailure()) {
                return result
            }

            val stored = data
            if (stored != null && stored.schemaVersion != DownloadStore.SCHEMA_VERSION) {
                // Written by a build with a different understanding of the format.
                return reset(
                    DownloadStore(),
                    "unknown schema version ${stored.schemaVersion}, " +
                            "expected ${DownloadStore.SCHEMA_VERSION}"
                )
            }

            return result
        }

        fun getAll(): Map<DownloadId, DownloadTask> =
            data?.downloads?.toDomains() ?: emptyMap()

        /**
         * @param durable commit before returning, rather than letting the change ride along
         * with the next coalesced commit.
         */
        suspend fun save(task: DownloadTask, durable: Boolean): KResult<Unit, StoreError> {
            val taskStore = task.toStore()
            val transform: (DownloadStore) -> DownloadStore = {
                it.copy(downloads = it.downloads + (taskStore.id to taskStore))
            }

            if (durable) return update(transform)

            mutate(transform)
            flushRequests.trySend(Unit)
            return Success(Unit)
        }

        /** Publishes [task] in memory without committing and without asking for a commit. */
        suspend fun publish(task: DownloadTask) {
            val taskStore = task.toStore()
            mutate { it.copy(downloads = it.downloads + (taskStore.id to taskStore)) }
        }

        suspend fun delete(id: DownloadId): KResult<Unit, StoreError> =
            update { it.copy(downloads = it.downloads - id.value) }
    }

    internal companion object {
        /**
         * How long a coalescable change waits for company before the store is committed.
         *
         * Long enough that a burst — a boot recovery, an enqueue of a whole playlist, a
         * pause-all — becomes one commit; short enough that a lone change is on disk well
         * within the time it takes a person to notice anything happened.
         */
        const val DEFAULT_COALESCE_WINDOW_MS: Long = 250L
    }
}
