package io.github.giovanniandreuzza.nimbus.core.ports

import io.github.giovanniandreuzza.explicitarchitecture.core.application.ports.IsPort
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.application.errors.TransitionFailure
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import kotlinx.coroutines.flow.Flow

/**
 * Download Task Repository.
 *
 * @author Giovanni Andreuzza
 */
@IsPort
internal interface DownloadTaskRepository {

    suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks>

    /**
     * Every task, as a snapshot taken under the repository's lock.
     *
     * DTOs rather than entities, for the same reason [readDownloadTask] exists: mapping a list
     * of live entities outside the lock reads, for each one, fields another coroutine may be
     * mid-transition on — and on a device holding hundreds of tasks that happened on every
     * emission.
     */
    suspend fun getAllDownloadTasks(): List<DownloadTaskDTO>

    /** Whether any task already writes to [filePath]. */
    suspend fun isFilePathInUse(filePath: String): Boolean

    suspend fun observeDownloadTask(id: DownloadId): KResult<Flow<DownloadState>, DownloadTaskNotFound>

    fun observeAllDownloadTasks(): Flow<List<DownloadTaskDTO>>

    suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, KError>

    /**
     * Applies [transition] to the task for [id] **under the repository's own lock**, publishes
     * the result and — unless [persist] is false — writes it.
     *
     * Every state change goes through here, and that is the point. A `DownloadTask` is a
     * mutable entity shared by everything that holds it: the service, which serialises its own
     * operations per task, and the progress callbacks, which run on the download's coroutine
     * and never took that lock. Reading the task, mutating it and saving it as three separate
     * steps left a window on every transition — `pauseDownload` setting `Paused` while a
     * progress tick wrote `Downloading` back over it, and the task persisted as downloading
     * with no job behind it. Under one lock there is no window, and on Kotlin/Native there is
     * no longer an unsynchronised write either.
     *
     * [transition] returns null to refuse — it is the entity that knows which state may follow
     * which — and anything non-null to accept, so a caller can compute what it needs from the
     * task *inside* the lock rather than reading it back afterwards.
     *
     * @param persist false for the progress hot path, which must not reach the disk.
     */
    /**
     * Reads the task for [id] under the repository's lock, without changing or publishing
     * anything.
     *
     * The counterpart to [transitionDownloadTask], and there for the same reason: a caller
     * that took the entity away and read it later would be reading fields another coroutine
     * may be mid-transition on. Build the snapshot you need — usually a
     * [io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO] — inside
     * [read].
     *
     * Returns [TransitionFailure.Refused] when [read] returns null, which lets a caller say
     * "only if the state allows it" and get the state back when it does not.
     */
    suspend fun <T : Any> readDownloadTask(
        id: DownloadId,
        read: (DownloadTask) -> T?
    ): KResult<T, TransitionFailure>

    suspend fun <T : Any> transitionDownloadTask(
        id: DownloadId,
        persist: Boolean = true,
        transition: (DownloadTask) -> T?
    ): KResult<T, TransitionFailure>

    suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, KError>

}