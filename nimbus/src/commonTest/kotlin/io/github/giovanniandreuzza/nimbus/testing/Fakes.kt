package io.github.giovanniandreuzza.nimbus.testing

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.ContentDigestPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePortError
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * The repository as a map, with the same observable surface as the real one.
 *
 * A service test that went through the real repository would be testing persistence as
 * well, and would fail for two unrelated reasons at once.
 */
internal class FakeDownloadTaskRepository(
    private val loadFailure: FailedToLoadDownloadTasks? = null
) : DownloadTaskRepository {

    private val tasks = mutableMapOf<DownloadId, DownloadTask>()
    private val flows = mutableMapOf<DownloadId, MutableStateFlow<DownloadState>>()
    private val revision = MutableStateFlow(0L)

    /** Set to fail the next save, as a full disk or a revoked permission would. */
    var saveFailure: KError? = null

    var saveCount: Int = 0
        private set

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> =
        loadFailure?.let { Failure(it) } ?: Success(Unit)

    override suspend fun getDownloadTask(id: DownloadId): KResult<DownloadTask, DownloadTaskNotFound> =
        tasks[id]?.let { Success(it) } ?: Failure(DownloadTaskNotFound)

    override suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask> = tasks.toMap()

    override suspend fun observeDownloadTask(id: DownloadId): KResult<Flow<DownloadState>, DownloadTaskNotFound> =
        flows[id]?.asStateFlow()?.let { Success(it) } ?: Failure(DownloadTaskNotFound)

    override fun observeAllDownloadTasks(): Flow<List<DownloadTask>> =
        revision.map { tasks.values.toList() }

    override suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, KError> {
        saveFailure?.let { return Failure(it) }
        saveCount++
        tasks[downloadTask.entityId.id] = downloadTask
        flows.getOrPut(downloadTask.entityId.id) { MutableStateFlow(downloadTask.state) }.value =
            downloadTask.state
        revision.value++
        return Success(Unit)
    }

    override suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, KError> {
        tasks[downloadTask.entityId.id] = downloadTask
        flows[downloadTask.entityId.id]?.value = downloadTask.state
        revision.value++
        return Success(Unit)
    }

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, KError> {
        tasks.remove(id)
        flows.remove(id)
        revision.value++
        return Success(Unit)
    }

    /** Seeds a task without going through the service, to set up a starting state. */
    fun seed(task: DownloadTask) {
        tasks[task.entityId.id] = task
        flows[task.entityId.id] = MutableStateFlow(task.state)
        revision.value++
    }

    fun current(id: String): DownloadTask? = tasks[DownloadId.create(id)]
}

/**
 * A download port that records what it was asked to do and answers from a script.
 */
internal class ScriptedDownloadPort(
    private var remoteSize: Long = DEFAULT_SIZE
) : DownloadPort {

    val started = mutableListOf<DownloadTaskDTO>()
    val stopped = mutableListOf<String>()

    var sizeFailure: GetFileSizeError? = null
    var startFailure: DownloadError? = null

    fun remoteSizeBecomes(size: Long) {
        remoteSize = size
    }

    override suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeError> =
        sizeFailure?.let { Failure(it) } ?: Success(remoteSize)

    override suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError> {
        started.add(downloadTask)
        return startFailure?.let { Failure(it) } ?: Success(Unit)
    }

    override suspend fun stopDownload(downloadId: String) {
        stopped.add(downloadId)
    }

    companion object {
        const val DEFAULT_SIZE: Long = 1_024L
    }
}

/** The URL is the id: a test that reads `task-0` in an assertion beats one that reads a hash. */
internal object UrlAsIdProvider : IdProviderPort {
    override fun generateUniqueId(value: String): String = value
}

internal class FakeContentDigestPort(
    private val answer: KResult<Checksum, StoragePortError>
) : ContentDigestPort {
    val requested = mutableListOf<String>()

    override suspend fun digestOf(
        path: String,
        algorithm: DigestAlgorithm
    ): KResult<Checksum, StoragePortError> {
        requested.add(path)
        return answer
    }
}

/** Collects log events so a test can assert on what the library reported. */
internal class RecordingLogger : NimbusLogger {
    val events = mutableListOf<NimbusLogEvent>()

    override suspend fun log(event: NimbusLogEvent) {
        events.add(event)
    }

    inline fun <reified T : NimbusLogEvent> firstOrNull(): T? =
        events.filterIsInstance<T>().firstOrNull()
}
