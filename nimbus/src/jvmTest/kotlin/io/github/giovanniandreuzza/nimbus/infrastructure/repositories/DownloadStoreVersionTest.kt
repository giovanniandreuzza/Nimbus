package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadStateStore
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage.DownloadTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
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
