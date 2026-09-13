package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lock that every state transition goes through, under real threads.
 *
 * A `DownloadTask` is a mutable entity shared by the service and by the progress callbacks,
 * which run on the download's own coroutine. Read it, mutate it, save it as three steps and
 * every transition has a window — that is the defect this repository API exists to close, and
 * a virtual-time test cannot show it because nothing there runs in parallel.
 *
 * So: real dispatcher, real contention, and assertions about what must hold afterwards.
 */
class RepositoryConcurrencyTest {

    @Test
    fun `concurrent transitions on one task neither lose an update nor tear one`() =
        runBlocking {
            val repository = repository()
            repository.loadDownloadTasks()
            val task = downloadingTask()
            repository.saveDownloadTask(task)
            val id = task.entityId.id

            // Every writer proposes a progress value; the entity refuses anything lower than
            // what it already has, so the outcome is defined: the highest one wins, exactly
            // once, and the published state agrees with it.
            val proposals = (1..500).map { it / 10.0 }
            withContext(Dispatchers.Default) {
                proposals.map { progress ->
                    async {
                        repository.transitionDownloadTask(id, persist = false) { t ->
                            if (t.updateProgress(progress)) progress else null
                        }
                    }
                }.awaitAll()
            }

            val state = repository.allTasksForTest()[id]?.state as? DownloadState.Downloading
                ?: error("expected a downloading task, got ${repository.allTasksForTest()[id]}")
            assertEquals(
                proposals.max(),
                state.progress,
                "a lost update leaves the entity below the highest value anyone applied"
            )

            assertEquals(
                state,
                repository.getAllDownloadTasks().single().state,
                "the snapshot and the entity must not disagree: they are taken under the same " +
                        "lock precisely so a reader cannot see one without the other"
            )
        }

    @Test
    fun `a transition and a delete cannot both think they own the task`() = runBlocking {
        val repository = repository()
        repository.loadDownloadTasks()

        withContext(Dispatchers.Default) {
            (1..200).map { index ->
                async {
                    val task = downloadingTask(id = "task-$index", path = "/tmp/nimbus/$index.bin")
                    repository.saveDownloadTask(task)
                    // Pausing and deleting the same task from two coroutines: whichever order
                    // they land in, neither may leave a path registered for a task that is gone.
                    val pausing = async {
                        repository.transitionDownloadTask(task.entityId.id) { t ->
                            if (t.pause()) Unit else null
                        }
                    }
                    val deleting = async { repository.deleteDownloadTask(task.entityId.id) }
                    pausing.await()
                    deleting.await()
                }
            }.awaitAll()
        }

        assertTrue(repository.allTasksForTest().isEmpty(), "every task was deleted")
        (1..200).forEach { index ->
            assertTrue(
                !repository.isFilePathInUse("/tmp/nimbus/$index.bin"),
                "path $index is still registered to a task that no longer exists"
            )
        }
    }

    private fun repository(): DownloadRepository {
        val dir = Files.createTempDirectory("nimbus-concurrency").toFile()
        return DownloadRepository(
            storePath = dir.resolve("download_manager").absolutePath,
            dispatcher = Dispatchers.IO,
            nimbusStoragePort = FileSystemNimbusStorageAdapter(),
            clock = FakeClock()
        )
    }

    private fun downloadingTask(
        id: String = "task-0",
        path: String = "/tmp/nimbus/task-0.bin"
    ) = DownloadTask.restore(
        id = id,
        fileUrl = "https://example.com/$id",
        filePath = path,
        fileName = id,
        fileSize = 1_024L,
        state = DownloadState.Downloading(0.0)
    )
}
