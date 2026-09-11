package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadTaskNotFound
import io.github.giovanniandreuzza.nimbus.core.application.errors.FailedToLoadDownloadTasks
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.core.ports.IdProviderPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store that fails to load once must not disable the whole API for the rest
 * of the process: on a kiosk that means no download can ever start again until
 * someone clears the app data by hand.
 */
class DownloadServiceInitRetryTest {

    @Test
    fun `a failed load is retried on the next call`() = runBlocking {
        val repository = FlakyRepository(failuresBeforeSuccess = 1)
        val service = serviceWith(repository)

        val first = service.getAllDownloads()
        val second = service.getAllDownloads()

        assertTrue(first.isFailure(), "expected the first call to surface the load failure")
        assertTrue(second.isSuccess(), "expected the load to be retried, got $second")
        assertEquals(2, repository.loadAttempts, "expected exactly one retry")
    }

    @Test
    fun `a successful load is not repeated`() = runBlocking {
        val repository = FlakyRepository(failuresBeforeSuccess = 0)
        val service = serviceWith(repository)

        service.getAllDownloads()
        service.getAllDownloads()

        assertEquals(1, repository.loadAttempts, "expected the load result to be cached")
    }

    private fun serviceWith(repository: DownloadTaskRepository) = DownloadService(
        idProvider = EchoIdProvider,
        downloadPort = NoopDownloadPort,
        repository = repository,
        nimbusStoragePort = FileSystemNimbusStorageAdapter(),
        minReservedDiskBytes = null,
        logger = null,
        autoStart = false,
        downloadScope = CoroutineScope(Dispatchers.Default)
    )
}

private class FlakyRepository(private val failuresBeforeSuccess: Int) : DownloadTaskRepository {

    var loadAttempts: Int = 0
        private set

    override suspend fun loadDownloadTasks(): KResult<Unit, FailedToLoadDownloadTasks> {
        loadAttempts++
        return if (loadAttempts <= failuresBeforeSuccess) {
            Failure(FailedToLoadDownloadTasks(KError("io_error", "store temporarily unreadable")))
        } else {
            Success(Unit)
        }
    }

    override suspend fun getDownloadTask(id: DownloadId): KResult<DownloadTask, DownloadTaskNotFound> =
        Failure(DownloadTaskNotFound)

    override suspend fun getAllDownloadTask(): Map<DownloadId, DownloadTask> = emptyMap()

    override suspend fun observeDownloadTask(id: DownloadId): KResult<Flow<DownloadState>, DownloadTaskNotFound> =
        Failure(DownloadTaskNotFound)

    override fun observeAllDownloadTasks(): Flow<List<DownloadTask>> = flowOf(emptyList())

    override suspend fun saveDownloadTask(downloadTask: DownloadTask): KResult<Unit, KError> =
        Success(Unit)

    override suspend fun updateDownloadProgress(downloadTask: DownloadTask): KResult<Unit, KError> =
        Success(Unit)

    override suspend fun deleteDownloadTask(id: DownloadId): KResult<Unit, KError> = Success(Unit)
}

private object EchoIdProvider : IdProviderPort {
    override fun generateUniqueId(value: String): String = value
}

private object NoopDownloadPort : DownloadPort {
    override suspend fun getFileSizeToDownload(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(0L)

    override suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError> =
        Success(Unit)

    override suspend fun stopDownload(downloadId: String): Unit = Unit
}
