package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.fail

/**
 * `observeAllDownloadTasks` drives list UIs. It has to emit whenever a task's state
 * changes, not only when a task is added or removed.
 */
@OptIn(ExperimentalSerializationApi::class)
class DownloadRepositoryObservabilityTest {

    @Test
    fun `observeAllDownloadTasks emits when a task changes state`() = runBlocking {
        val repository = emptyRepository()
        repository.loadDownloadTasks()

        val task = taskNamed("a")
        repository.saveDownloadTask(task)

        val snapshots = CopyOnWriteArrayList<List<DownloadState>>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            repository.observeAllDownloadTasks().collect { tasks ->
                snapshots.add(tasks.map { it.state })
            }
        }

        try {
            awaitOrFail("the initial emission") { snapshots.isNotEmpty() }

            task.start()
            repository.saveDownloadTask(task)

            awaitOrFail("an emission carrying Downloading, got $snapshots") {
                snapshots.any { states -> states.any { it is DownloadState.Downloading } }
            }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `observeAllDownloadTasks emits progress updates`() = runBlocking {
        val repository = emptyRepository()
        repository.loadDownloadTasks()

        val task = taskNamed("a").also { it.start() }
        repository.saveDownloadTask(task)

        val snapshots = CopyOnWriteArrayList<List<DownloadState>>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            repository.observeAllDownloadTasks().collect { tasks ->
                snapshots.add(tasks.map { it.state })
            }
        }

        try {
            awaitOrFail("the initial emission") { snapshots.isNotEmpty() }

            task.updateProgress(42.0)
            repository.updateDownloadProgress(task)

            awaitOrFail("an emission carrying progress 42.0, got $snapshots") {
                snapshots.any { states ->
                    states.any { it is DownloadState.Downloading && it.progress == 42.0 }
                }
            }
        } finally {
            collector.cancel()
        }
    }

    private suspend fun awaitOrFail(what: String, condition: () -> Boolean) {
        val satisfied = withTimeoutOrNull(2_000) {
            while (!condition()) delay(5)
            true
        }
        if (satisfied == null) fail("timed out waiting for $what")
    }

    private fun emptyRepository(): DownloadRepository {
        val dir = Files.createTempDirectory("nimbus-observability").toFile()
        return DownloadRepository(
            storePath = dir.resolve("download_manager").absolutePath,
            dispatcher = Dispatchers.IO,
            nimbusStoragePort = FileSystemNimbusStorageAdapter()
        )
    }

    private fun taskNamed(name: String) = DownloadTask.create(
        id = name,
        fileUrl = "https://example.com/$name",
        filePath = "/tmp/nimbus/$name",
        fileName = name,
        fileSize = 1_024L
    )
}
