package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
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
            repository.getAllDownloadTask().isEmpty(),
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
            repository.getAllDownloadTask().size,
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

    private fun TestScope.repositoryOn(storage: InMemoryStorage): DownloadRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadRepository(
            storePath = STORE_PATH,
            dispatcher = dispatcher,
            nimbusStoragePort = storage,
            storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    private fun storeStampedAt(version: Int) = FutureStore(
        downloads = mapOf(
            "task-1" to DownloadTaskStore(
                id = "task-1",
                fileName = "screens.apk",
                fileUrl = "https://example.com/screens.apk",
                filePath = "/files/installer/screens.apk",
                fileSize = 1_024L,
                state = DownloadStateStore.Paused(progress = 12.5)
            )
        ),
        schemaVersion = version
    )

    private companion object {
        const val STORE_PATH = "/tmp/nimbus/download_manager"
    }
}
