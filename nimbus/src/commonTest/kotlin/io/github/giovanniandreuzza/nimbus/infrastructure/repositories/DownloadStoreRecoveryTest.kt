package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
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
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replica of the store schema shipped up to nimbus 1.4.1, whose sealed-class
 * discriminator was the class name of the *then* package
 * (`frameworks.filemanager.models`). Devices updated from that version carry a
 * blob the current schema cannot decode.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private sealed class LegacyStateStore {
    @Serializable
    @SerialName("io.github.giovanniandreuzza.nimbus.frameworks.filemanager.models.DownloadStateStore.Paused")
    data class Paused(@ProtoNumber(1) val progress: Double) : LegacyStateStore()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class LegacyTaskStore(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val fileName: String,
    @ProtoNumber(3) val fileUrl: String,
    @ProtoNumber(4) val filePath: String,
    @ProtoNumber(5) val fileSize: Long,
    @ProtoNumber(6) val state: LegacyStateStore,
    @ProtoNumber(7) val version: Int
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class LegacyStore(
    @ProtoNumber(1) val downloads: Map<String, LegacyTaskStore>
)

@OptIn(ExperimentalSerializationApi::class)
class DownloadStoreRecoveryTest {

    @Test
    fun `load recovers when the store on disk was written by an incompatible schema`() =
        runTest {
            val repository = repositoryOn(legacyStorage())

            val result = repository.loadDownloadTasks()

            assertTrue(
                result.isSuccess(),
                "expected the unreadable store to be discarded, got $result"
            )
            assertTrue(
                repository.allTasksForTest().isEmpty(),
                "expected no tasks to survive the reset"
            )
        }

    @Test
    fun `discarding the store is reported to the logger`() = runTest {
        val events = mutableListOf<NimbusLogEvent>()
        val repository = repositoryOn(legacyStorage(), logger = { events.add(it) })

        repository.loadDownloadTasks()

        assertTrue(
            events.any { it is NimbusLogEvent.StoreReset },
            "expected a StoreReset event, got $events"
        )
    }

    private fun legacyStorage() = InMemoryStorage().apply {
        write(STORE_PATH, ProtoBuf.encodeToByteArray(legacyStore()))
    }

    @Test
    fun `the destinations index is rebuilt from the store on load`() = runTest {
        // `enqueueDownload` asks this rather than walking every task, so an index that is only
        // filled by saves would report a path as free the moment a device restarted — and the
        // second task would happily write over the first one's file.
        val storage = InMemoryStorage()
        val repository = repositoryOn(storage)
        // The store is created by the load, and nothing persists before it.
        repository.loadDownloadTasks()
        repository.saveDownloadTask(
            DownloadTask.create(
                id = "task-1",
                fileUrl = "https://example.com/clip.mp4",
                filePath = TAKEN_PATH,
                fileName = "clip.mp4",
                fileSize = 1_024L
            )
        )
        // Enqueued is a coalesced state, so without this the store on disk is still empty and
        // the test would be asserting about a repository that loaded nothing.
        repository.flushPendingState()

        val afterRestart = repositoryOn(storage)
        afterRestart.loadDownloadTasks()

        assertTrue(
            afterRestart.isFilePathInUse(TAKEN_PATH),
            "the path belongs to a task that survived the restart"
        )
        assertTrue(!afterRestart.isFilePathInUse("/tmp/nimbus/other.mp4"))
    }

    @Test
    fun `a destination is free again once its task is gone`() = runTest {
        val storage = InMemoryStorage()
        val repository = repositoryOn(storage)
        val task = DownloadTask.create(
            id = "task-1",
            fileUrl = "https://example.com/clip.mp4",
            filePath = TAKEN_PATH,
            fileName = "clip.mp4",
            fileSize = 1_024L
        )
        repository.saveDownloadTask(task)

        repository.deleteDownloadTask(task.entityId.id)

        assertTrue(
            !repository.isFilePathInUse(TAKEN_PATH),
            "an index that only ever grows refuses a path nothing is using"
        )
    }

    private fun TestScope.repositoryOn(
        storage: InMemoryStorage,
        logger: NimbusLogger? = null
    ): DownloadRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DownloadRepository(
            storePath = STORE_PATH,
            dispatcher = dispatcher,
            nimbusStoragePort = storage,
            clock = FakeClock(),
            logger = logger,
            storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    private fun legacyStore() = LegacyStore(
        downloads = mapOf(
            "task-1" to LegacyTaskStore(
                id = "task-1",
                fileName = "screens.apk",
                fileUrl = "https://example.com/screens.apk",
                filePath = "/data/data/com.nakipower.appinstaller/files/installer/screens.apk",
                fileSize = 1_024L,
                state = LegacyStateStore.Paused(progress = 12.5),
                version = 1
            )
        )
    )

    private companion object {
        const val TAKEN_PATH = "/tmp/nimbus/clip.mp4"
        const val STORE_PATH = "/tmp/nimbus/download_manager"
    }
}
