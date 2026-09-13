package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import io.github.giovanniandreuzza.nimbus.testing.FakeClock
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Store written by a schema version this build does not know. Unknown protobuf
 * fields are skipped silently, so without an explicit version stamp the tasks
 * would be read back under the wrong assumptions.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("nimbus.store")
private data class FutureStore(
    @ProtoNumber(1) val downloads: Map<String, DownloadTaskStore>,
    @ProtoNumber(2) val schemaVersion: Int
)

@OptIn(ExperimentalSerializationApi::class)
class DownloadStoreVersionTest {

    @Test
    fun `load discards a store stamped with an unknown schema version`() = runTest {
        val storage = InMemoryStorage()
        storage.write(STORE_PATH, ProtoBuf.encodeToByteArray(storeStampedAt(99)))

        val repository = repositoryOn(storage)
        val result = repository.loadDownloadTasks()

        assertTrue(result.isSuccess(), "expected the load to recover, got $result")
        assertTrue(
            repository.allTasksForTest().isEmpty(),
            "expected tasks from an unknown schema version to be discarded"
        )
    }

    @Test
    fun `load keeps the tasks of a store written by an older schema version`() = runTest {
        val storage = InMemoryStorage()
        storage.write(STORE_PATH, ProtoBuf.encodeToByteArray(storeStampedAt(1)))

        val repository = repositoryOn(storage)
        val result = repository.loadDownloadTasks()

        assertTrue(result.isSuccess(), "expected the load to succeed, got $result")
        assertEquals(
            1,
            repository.allTasksForTest().size,
            "an app update must not throw away pending downloads: version 1 has no checksum " +
                    "fields, which decode as null, so its tasks are already valid version 2 tasks"
        )
    }

    @Test
    fun `a migrated store is restamped so the migration is paid once`() = runTest {
        val storage = InMemoryStorage()
        storage.write(STORE_PATH, ProtoBuf.encodeToByteArray(storeStampedAt(1)))

        repositoryOn(storage).loadDownloadTasks()

        val bytes = assertNotNull(storage.read(STORE_PATH), "the store should still be there")
        val restamped = ProtoBuf.decodeFromByteArray<FutureStore>(bytes)
        assertEquals(DownloadStore.SCHEMA_VERSION, restamped.schemaVersion)
    }

    @Test
    fun `a migrated task is stamped with the time of the migration`() = runTest {
        // Its timestamps do not exist in the old blob and decode as zero, which reads as
        // 1970 — and the first `pruneFinished` after the upgrade would take that as "older
        // than anything you could ask for" and delete every file the device already had.
        // Stamping says what is actually known: these tasks existed by the time this build
        // first ran.
        val storage = InMemoryStorage()
        storage.write(STORE_PATH, ProtoBuf.encodeToByteArray(storeStampedAt(2)))
        val clock = FakeClock(nowMs = MIGRATED_AT)

        val repository = repositoryOn(storage, clock)
        repository.loadDownloadTasks()

        val task = assertNotNull(repository.allTasksForTest().values.firstOrNull())
        assertEquals(MIGRATED_AT, task.createdAtEpochMs)
        assertEquals(
            null,
            task.finishedAtEpochMs,
            "this one is paused: stamping a finish time on it would make it prunable"
        )
    }

    @Test
    fun `a migrated task that had already finished is stamped as finished then`() = runTest {
        val storage = InMemoryStorage()
        storage.write(
            STORE_PATH,
            ProtoBuf.encodeToByteArray(storeStampedAt(2, DownloadStateStore.Finished))
        )
        // Its file has to be there, or boot recovery rightly decides the task is not finished
        // at all — see the test below.
        storage.write(FILE_PATH, ByteArray(FILE_SIZE.toInt()))
        val clock = FakeClock(nowMs = MIGRATED_AT)

        val repository = repositoryOn(storage, clock)
        repository.loadDownloadTasks()

        val task = assertNotNull(repository.allTasksForTest().values.firstOrNull())
        assertEquals(
            MIGRATED_AT,
            task.finishedAtEpochMs,
            "its age is measured from the upgrade, which is when this build first knew of it"
        )
    }

    @Test
    fun `a finished task whose file has vanished loses its finish time with its state`() =
        runTest {
            // Boot recovery resets it to Enqueued because the file is gone. A finish time left
            // behind would make `pruneFinished` delete a task that is waiting to be
            // downloaded — and the file it would delete does not exist either way, but the
            // task the device still needs would be gone.
            val storage = InMemoryStorage()
            storage.write(
                STORE_PATH,
                ProtoBuf.encodeToByteArray(storeStampedAt(2, DownloadStateStore.Finished))
            )

            val repository = repositoryOn(storage, FakeClock(nowMs = MIGRATED_AT))
            repository.loadDownloadTasks()

            val task = assertNotNull(repository.allTasksForTest().values.firstOrNull())
            assertEquals(null, task.finishedAtEpochMs, "it is not finished any more")
        }

    @Test
    fun `timestamps already on disk are left alone`() = runTest {
        // A second migration, or any later boot, must not move a date that was recorded.
        val storage = InMemoryStorage()
        val recorded = MIGRATED_AT - 5_000L
        storage.write(
            STORE_PATH,
            ProtoBuf.encodeToByteArray(
                storeStampedAt(2, DownloadStateStore.Finished, createdAt = recorded)
            )
        )
        storage.write(FILE_PATH, ByteArray(FILE_SIZE.toInt()))

        val repository = repositoryOn(storage, FakeClock(nowMs = MIGRATED_AT))
        repository.loadDownloadTasks()

        val task = assertNotNull(repository.allTasksForTest().values.firstOrNull())
        assertEquals(recorded, task.createdAtEpochMs)
    }

    private fun TestScope.repositoryOn(storage: InMemoryStorage): DownloadRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadRepository(
            storePath = STORE_PATH,
            dispatcher = dispatcher,
            nimbusStoragePort = storage,
            clock = FakeClock(),
            storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    private fun TestScope.repositoryOn(
        storage: InMemoryStorage,
        clock: FakeClock
    ): DownloadRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadRepository(
            storePath = STORE_PATH,
            dispatcher = dispatcher,
            nimbusStoragePort = storage,
            clock = clock,
            storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    private fun storeStampedAt(
        version: Int,
        state: DownloadStateStore = DownloadStateStore.Paused(progress = 12.5),
        createdAt: Long = 0L
    ) = FutureStore(
        downloads = mapOf(
            "task-1" to DownloadTaskStore(
                id = "task-1",
                fileName = "screens.apk",
                fileUrl = "https://example.com/screens.apk",
                filePath = FILE_PATH,
                fileSize = FILE_SIZE,
                state = state,
                createdAtEpochMs = createdAt
            )
        ),
        schemaVersion = version
    )

    private companion object {
        const val STORE_PATH = "/tmp/nimbus/download_manager"
        const val MIGRATED_AT = 1_757_700_000_000L
        const val FILE_PATH = "/files/installer/screens.apk"
        const val FILE_SIZE = 1_024L
    }
}
