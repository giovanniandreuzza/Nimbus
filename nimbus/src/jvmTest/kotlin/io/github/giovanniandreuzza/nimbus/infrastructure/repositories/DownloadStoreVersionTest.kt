package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `load discards a store stamped with an unknown schema version`() = runBlocking {
        val dir = Files.createTempDirectory("nimbus-store-version").toFile()
        val storeFile = File(dir, "download_manager")
        storeFile.writeBytes(ProtoBuf.encodeToByteArray(futureStore()))

        val repository = DownloadRepository(
            storePath = storeFile.absolutePath,
            dispatcher = Dispatchers.IO,
            nimbusStoragePort = FileSystemNimbusStorageAdapter()
        )

        val result = repository.loadDownloadTasks()

        assertTrue(result.isSuccess(), "expected the load to recover, got $result")
        assertTrue(
            repository.getAllDownloadTask().isEmpty(),
            "expected tasks from an unknown schema version to be discarded"
        )
    }

    @Test
    fun `load keeps the tasks of a store written by an older schema version`() = runBlocking {
        val dir = Files.createTempDirectory("nimbus-store-migration").toFile()
        val storeFile = File(dir, "download_manager")
        storeFile.writeBytes(ProtoBuf.encodeToByteArray(versionOneStore()))

        val repository = repositoryOn(storeFile)
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
    fun `a migrated store is restamped so the migration is paid once`() = runBlocking {
        val dir = Files.createTempDirectory("nimbus-store-restamp").toFile()
        val storeFile = File(dir, "download_manager")
        storeFile.writeBytes(ProtoBuf.encodeToByteArray(versionOneStore()))

        repositoryOn(storeFile).loadDownloadTasks()

        val restamped = ProtoBuf.decodeFromByteArray<FutureStore>(storeFile.readBytes())
        assertEquals(DownloadStore.SCHEMA_VERSION, restamped.schemaVersion)
    }

    private fun repositoryOn(storeFile: File) = DownloadRepository(
        storePath = storeFile.absolutePath,
        dispatcher = Dispatchers.IO,
        nimbusStoragePort = FileSystemNimbusStorageAdapter()
    )

    private fun versionOneStore() = FutureStore(
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
        schemaVersion = 1
    )

    private fun futureStore() = FutureStore(
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
        schemaVersion = 99
    )
}
