package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What is in memory after a save must also be on disk. A device that loses power has
 * only the disk.
 */
@OptIn(ExperimentalSerializationApi::class)
class DownloadRepositoryPersistenceTest {

    @Test
    fun `concurrent saves of distinct tasks all reach disk`() = runBlocking {
        val storeFile = emptyStoreFile()
        val repository = repositoryOn(storeFile)
        repository.loadDownloadTasks()

        val count = 200
        coroutineScope {
            (0 until count)
                .map { index ->
                    async(Dispatchers.Default) {
                        repository.saveDownloadTask(taskNamed("task-$index"))
                    }
                }
                .awaitAll()
        }

        assertEquals(
            count,
            repository.getAllDownloadTask().size,
            "expected every task to be held in memory"
        )

        repository.flushPendingState()
        val reloaded = repositoryOn(storeFile)
        reloaded.loadDownloadTasks()

        assertEquals(
            count,
            reloaded.getAllDownloadTask().size,
            "expected every saved task to survive a reload from disk"
        )
    }

    @Test
    fun `the last state written to memory is the state found on disk`() = runBlocking {
        val storeFile = emptyStoreFile()
        val repository = repositoryOn(storeFile)
        repository.loadDownloadTasks()

        val task = taskNamed("a")
        repository.saveDownloadTask(task)
        task.start()
        repository.saveDownloadTask(task)
        task.pause()
        repository.saveDownloadTask(task)

        repository.flushPendingState()
        val reloaded = repositoryOn(storeFile)
        reloaded.loadDownloadTasks()

        val onDisk = reloaded.getAllDownloadTask()[DownloadId.create("a")]
        assertEquals(
            task.state,
            onDisk?.state,
            "expected the persisted state to match the in-memory state"
        )
    }

    private fun emptyStoreFile(): File {
        val dir = Files.createTempDirectory("nimbus-persistence").toFile()
        return dir.resolve("download_manager")
    }

    private fun repositoryOn(storeFile: File) = DownloadRepository(
        storePath = storeFile.absolutePath,
        dispatcher = Dispatchers.IO,
        nimbusStoragePort = FileSystemNimbusStorageAdapter()
    )

    private fun taskNamed(name: String) = DownloadTask.create(
        id = name,
        fileUrl = "https://example.com/$name",
        filePath = "/tmp/nimbus/$name",
        fileName = name,
        fileSize = 1_024L
    )
}
