package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

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
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store whose write was interrupted between the temp file and the commit.
 *
 * Saving is a strict two-phase write: the whole store is encoded into `<store>.tmp`, and only
 * then moved onto the store itself. So a `.tmp` still sitting there at load time means the
 * move did not complete — and it is the temp, complete by construction, that holds the newer
 * state. The destination holds either the previous version or, where the move had to fall
 * back to copying because the filesystem has no atomic rename, a partial one.
 *
 * Reading the destination first and treating the temp as a last resort therefore has the
 * preference backwards. It only looks harmless because a truncated protobuf usually fails to
 * decode; when it happens to decode, a store is silently accepted with tasks missing from it
 * and the complete copy sitting next to it is discarded.
 */
@OptIn(ExperimentalSerializationApi::class)
class InterruptedStoreWriteTest {

    @Test
    fun `the interrupted write wins over what it was about to replace`() = runTest {
        val storage = InMemoryStorage()
        storage.write(STORE_PATH, ProtoBuf.encodeToByteArray(storeWith("old-task")))
        storage.write("$STORE_PATH.tmp", ProtoBuf.encodeToByteArray(storeWith("new-task")))

        val repository = repositoryOn(storage)
        repository.loadDownloadTasks()

        val ids = repository.getAllDownloadTask().values.map { it.entityId.id.value }
        assertTrue(
            ids.contains("new-task"),
            "the completed temp holds the newer state and must win; loaded $ids"
        )
        assertEquals(1, ids.size, "loaded $ids")
    }

    @Test
    fun `the store is used when no interrupted write is left behind`() = runTest {
        val storage = InMemoryStorage()
        storage.write(STORE_PATH, ProtoBuf.encodeToByteArray(storeWith("only-task")))

        val repository = repositoryOn(storage)
        repository.loadDownloadTasks()

        assertEquals(
            listOf("only-task"),
            repository.getAllDownloadTask().values.map { it.entityId.id.value }
        )
    }

    private fun TestScope.repositoryOn(storage: InMemoryStorage): DownloadRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadRepository(
            storePath = STORE_PATH,
            dispatcher = dispatcher,
            nimbusStoragePort = storage,
            logger = null,
            storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    private fun storeWith(id: String) = DownloadStore(
        downloads = mapOf(
            id to DownloadTaskStore(
                id = id,
                fileName = "payload.bin",
                fileUrl = "https://example.com/$id.bin",
                filePath = "/tmp/$id.bin",
                fileSize = 64L,
                state = DownloadStateStore.Paused(0.5),
                expectedChecksum = null,
                checksum = null
            )
        )
    )

    private companion object {
        const val STORE_PATH = "/tmp/nimbus-store"
    }
}
