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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    logger: NimbusLogger? = null
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
    private val diskStore = DiskStore(storePath, dispatcher, nimbusStoragePort) { reason ->
        logger?.log(NimbusLogEvent.StoreReset(reason))
    }

    // -----------------------------------------------------------------------
    // DownloadTaskRepository implementation
    // -----------------------------------------------------------------------

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        diskStore.load().onFailure { return Failure(FailedToLoadDownloadTasks(it)) }

        mutex.withLock {
            diskStore.getAll().forEach { (id, task) ->
                when {
                    task.state is DownloadState.Downloading -> {
                        // Crash/restart recovery: Downloading tasks that were never
                        // paused are reset to Paused so they can be resumed cleanly.
                        task.pause()
                        diskStore.save(task).onFailure {
                            return Failure(FailedToLoadDownloadTasks(it))
                        }
                    }

                    task.state is DownloadState.Finished -> {
                        // File-integrity check: if the finished file was deleted or
                        // corrupted between sessions, reset the task to Enqueued so
                        // it can be restarted without requiring a new enqueueDownload.
                        val actualSize = nimbusStoragePort.size(task.filePath.value)
                            .let { result -> if (result is Success) result.value else -1L }
                        if (actualSize != task.fileSize.value) {
                            task.resetToEnqueued()
                            diskStore.save(task).onFailure {
                                return Failure(FailedToLoadDownloadTasks(it))
                            }
                        }
                    }
                }
                tasks[id] = task
                stateFlows[id] = MutableStateFlow(task.state)
            }
            revision.value++
        }

        return Success(Unit)
    }

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

        diskStore.save(downloadTask).onFailure { return Failure(it) }

        return Success(Unit)
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
        onReset: suspend (reason: String) -> Unit
    ) : StoreManager<DownloadStore>(
        filePath = storePath,
        nimbusStoragePort = nimbusStoragePort,
        serializer = DownloadStore.serializer(),
        dispatcher = dispatcher,
        onReset = onReset
    ) {
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

        suspend fun save(task: DownloadTask): KResult<Unit, StoreError> {
            val taskStore = task.toStore()
            return update { it.copy(downloads = it.downloads + (taskStore.id to taskStore)) }
        }

        suspend fun delete(id: DownloadId): KResult<Unit, StoreError> =
            update { it.copy(downloads = it.downloads - id.value) }
    }
}
