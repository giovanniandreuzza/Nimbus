package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.application.errors.TransitionFailure
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.ClockPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.frameworks.store.StoreManager
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.InitStoreError
import io.github.giovanniandreuzza.nimbus.frameworks.store.errors.StoreError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
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
    private val clock: ClockPort,
    private val logger: NimbusLogger? = null,
    storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS
) : DownloadTaskRepository {

    private val mutex = Mutex()
    private val tasks = mutableMapOf<DownloadId, DownloadTask>()
    private val stateFlows = mutableMapOf<DownloadId, MutableStateFlow<DownloadState>>()

    /**
     * Destinations that already belong to a task, so `enqueueDownload` can ask in one lookup.
     *
     * It used to walk every task for each enqueue — after copying the whole map, outside the
     * lock. Fine for a handful and quadratic for a manifest: two thousand assets loaded at
     * boot cost two million comparisons and two thousand map copies, on a device whose
     * catalogue only ever grows. A path is fixed for the life of a task, so an index of them
     * needs maintaining in exactly the two places a task appears and disappears.
     */
    private val filePathsInUse = mutableSetOf<String>()

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
        clock = clock,
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
                        diskStore.publish(task.toStore())
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
                            diskStore.publish(task.toStore())
                            recovered = true
                        }
                    }
                }
                tasks[id] = task
                stateFlows[id] = MutableStateFlow(task.state)
                filePathsInUse += task.filePath.value
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
    override suspend fun flushPendingState(): KResult<Unit, KError> = diskStore.flush()

    override suspend fun getAllDownloadTasks(): List<DownloadTaskDTO> = mutex.withLock {
        tasks.values.map { DownloadTaskDTO.fromDomain(it) }
    }

    override suspend fun isFilePathInUse(filePath: String): Boolean = mutex.withLock {
        filePath in filePathsInUse
    }

    /**
     * The entities themselves, for the tests that exercise this class directly. Not on the
     * port: everything else sees snapshots, which is what keeps the entity's mutable state
     * inside this lock.
     */
    internal suspend fun allTasksForTest(): Map<DownloadId, DownloadTask> =
        mutex.withLock { tasks.toMap() }

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
    override fun observeAllDownloadTasks(): Flow<List<DownloadTaskDTO>> =
        revision.map { getAllDownloadTasks() }.conflate()

    override suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, KError> {
        // The snapshot is taken inside the lock and the write happens outside it. A
        // `DownloadTask` is mutable and shared, so serialising it out here would read fields
        // another transition may be changing — and what reached the disk would be neither
        // state in full.
        val snapshot = mutex.withLock {
            tasks[downloadTask.entityId.id] = downloadTask
            filePathsInUse += downloadTask.filePath.value
            stateFlows[downloadTask.entityId.id]?.update { downloadTask.state }
                ?: run {
                    stateFlows[downloadTask.entityId.id] = MutableStateFlow(downloadTask.state)
                }
            revision.value++
            Snapshot(downloadTask.toStore(), downloadTask.state.mustBeDurable())
        }

        diskStore.save(snapshot.task, durable = snapshot.durable)
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

    override suspend fun <T : Any> readDownloadTask(
        id: DownloadId,
        read: (DownloadTask) -> T?
    ): KResult<T, TransitionFailure> = mutex.withLock {
        val task = tasks[id] ?: return Failure(TransitionFailure.NotFound)
        val value = read(task) ?: return Failure(TransitionFailure.Refused(task.state))
        Success(value)
    }

    override suspend fun <T : Any> transitionDownloadTask(
        id: DownloadId,
        persist: Boolean,
        transition: (DownloadTask) -> T?
    ): KResult<T, TransitionFailure> {
        // Everything the entity touches happens here, inside the lock: the read, the
        // transition, and whatever the caller needs to take away from it.
        val applied = mutex.withLock {
            val task = tasks[id] ?: return Failure(TransitionFailure.NotFound)
            val value = transition(task)
                ?: return Failure(TransitionFailure.Refused(task.state))

            stateFlows[id]?.update { task.state } ?: run {
                stateFlows[id] = MutableStateFlow(task.state)
            }
            revision.value++
            Applied(Snapshot(task.toStore(), task.state.mustBeDurable()), value)
        }

        if (!persist) return Success(applied.value)

        diskStore.save(applied.snapshot.task, durable = applied.snapshot.durable)
            .onFailure { return Failure(TransitionFailure.NotPersisted(it)) }

        return Success(applied.value)
    }

    /** A task as it was under the lock, ready to be written without reading the entity again. */
    private class Snapshot(val task: DownloadTaskStore, val durable: Boolean)

    private class Applied<T>(val snapshot: Snapshot, val value: T)

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, KError> {
        mutex.withLock {
            tasks.remove(id)?.let { filePathsInUse -= it.filePath.value }
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
        private val clock: ClockPort,
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
            val result = init(DownloadStore(schemaVersion = DownloadStore.SCHEMA_VERSION))
            if (result.isFailure()) {
                return result
            }

            val stored = data
            if (stored != null && stored.schemaVersion > DownloadStore.SCHEMA_VERSION) {
                // Written by a newer build, whose fields this one would misread.
                return reset(
                    DownloadStore(schemaVersion = DownloadStore.SCHEMA_VERSION),
                    "unknown schema version ${stored.schemaVersion}, " +
                            "expected at most ${DownloadStore.SCHEMA_VERSION}"
                )
            }

            if (stored != null && stored.schemaVersion < DownloadStore.SCHEMA_VERSION) {
                // Every version this build still understands decodes into the current
                // shape, with fields added since then absent and therefore null. Restamp
                // it so the migration is paid once rather than on every boot; the tasks
                // themselves are kept, because discarding them would cost the device a
                // re-download of everything it had already fetched.
                //
                // The timestamps are the one field that cannot be left at its decoded value:
                // zero reads as 1970, and the first `pruneFinished` would take that as
                // "older than anything you could ask for" and delete every file the device
                // already had. Stamping them with the time of the migration says what is
                // actually known — that these tasks existed by the time this build first ran.
                val now = clock.nowEpochMs()
                update { store ->
                    store.copy(
                        schemaVersion = DownloadStore.SCHEMA_VERSION,
                        downloads = store.downloads.mapValues { (_, task) -> task.stamped(now) }
                    )
                }
            }

            return result
        }

        /**
         * Gives a task from an older store the timestamps it never had.
         *
         * Only where they are missing: a store already carrying them is left exactly as it is,
         * so a second migration — or a build that reads a v3 store — cannot move a date.
         */
        private fun DownloadTaskStore.stamped(now: Long): DownloadTaskStore {
            if (createdAtEpochMs != 0L) return this
            return copy(
                createdAtEpochMs = now,
                finishedAtEpochMs = finishedAtEpochMs
                    ?: now.takeIf { state is DownloadStateStore.Finished }
            )
        }

        fun getAll(): Map<DownloadId, DownloadTask> =
            data?.downloads?.toDomains() ?: emptyMap()

        /**
         * @param durable commit before returning, rather than letting the change ride along
         * with the next coalesced commit.
         */
        suspend fun save(taskStore: DownloadTaskStore, durable: Boolean): KResult<Unit, StoreError> {
            val transform: (DownloadStore) -> DownloadStore = {
                it.copy(downloads = it.downloads + (taskStore.id to taskStore))
            }

            if (durable) return update(transform)

            mutate(transform)
            flushRequests.trySend(Unit)
            return Success(Unit)
        }

        /** Publishes [taskStore] in memory without committing and without asking for a commit. */
        suspend fun publish(taskStore: DownloadTaskStore) {
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
