package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage.FileSystemNimbusStorageAdapter
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.LocalFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.ExperimentalSerializationApi
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures how many times the whole store is rewritten for a realistic task lifecycle.
 *
 * The store is a single ProtoBuf blob, so one commit re-serialises and rewrites every
 * known task. On an appliance holding hundreds of cached assets that is flash wear, not
 * just latency, which makes the number of commits the quantity worth watching.
 */
@OptIn(ExperimentalSerializationApi::class)
class DownloadStoreWriteAmplificationTest {

    @Test
    fun `a burst of non-terminal saves costs far fewer commits than saves`() = runBlocking {
        val counter = AtomicInteger()
        val repository = repositoryCounting(counter)
        repository.loadDownloadTasks()
        val before = counter.get()

        val tasks = (0 until TASK_COUNT).map { taskNamed("task-$it") }
        tasks.forEach { repository.saveDownloadTask(it) }
        tasks.forEach { it.start(); repository.saveDownloadTask(it) }

        val commits = counter.get() - before
        println(
            "[write-amplification] ${TASK_COUNT * 2} non-terminal saves -> $commits store commits"
        )
        assertTrue(
            commits <= TASK_COUNT / 5,
            "expected a burst of ${TASK_COUNT * 2} non-terminal saves to coalesce, got $commits commits"
        )
    }

    @Test
    fun `terminal states are committed before the save returns`() = runBlocking {
        val counter = AtomicInteger()
        val storeFile = storeFile()
        val repository = repositoryCounting(counter, storeFile)
        repository.loadDownloadTasks()

        // The finished file has to be on disk at its expected size, or boot recovery
        // legitimately demotes the task back to Enqueued and the assertion below would be
        // testing recovery rather than durability.
        val downloaded = storeFile.parentFile.resolve("task-0")
            .also { it.writeBytes(ByteArray(FILE_SIZE.toInt())) }

        val task = taskNamed("task-0", downloaded.absolutePath)
        repository.saveDownloadTask(task)
        task.start()
        task.finish()
        repository.saveDownloadTask(task)

        // No waiting: a reload right now must already see Finished.
        val reloaded = repositoryCounting(AtomicInteger(), storeFile)
        reloaded.loadDownloadTasks()

        assertEquals(
            DownloadState.Finished,
            reloaded.getAllDownloadTask()[DownloadId.create("task-0")]?.state,
            "expected a finished task to be on disk as soon as the save returned"
        )
    }

    @Test
    fun `a coalesced save reaches disk without any further save`() = runBlocking {
        val storeFile = storeFile()
        val repository = repositoryCounting(AtomicInteger(), storeFile)
        repository.loadDownloadTasks()

        val task = taskNamed("task-0")
        repository.saveDownloadTask(task)

        val landed = withTimeoutOrNull(5_000) {
            while (true) {
                val reloaded = repositoryCounting(AtomicInteger(), storeFile)
                reloaded.loadDownloadTasks()
                if (reloaded.getAllDownloadTask().containsKey(DownloadId.create("task-0"))) break
                delay(25)
            }
            true
        }
        assertTrue(landed == true, "expected the coalesced save to be committed on its own")
    }

    private fun storeFile() =
        Files.createTempDirectory("nimbus-amplification").toFile().resolve("download_manager")

    private fun repositoryCounting(
        commits: AtomicInteger,
        store: java.io.File = storeFile()
    ): DownloadRepository = DownloadRepository(
        storePath = store.absolutePath,
        dispatcher = Dispatchers.IO,
        nimbusStoragePort = CommitCountingStoragePort(FileSystemNimbusStorageAdapter(), commits)
    )

    private fun taskNamed(name: String, path: String = "/tmp/nimbus/$name") = DownloadTask.create(
        id = name,
        fileUrl = "https://example.com/$name",
        filePath = path,
        fileName = name,
        fileSize = FILE_SIZE
    )

    private companion object {
        const val TASK_COUNT = 50
        const val FILE_SIZE = 1_024L
    }
}

/**
 * Counts store commits. [StoreManager] commits with a two-phase write, so one
 * `atomicMove` is exactly one full rewrite of the store.
 */
internal class CommitCountingStoragePort(
    private val delegate: NimbusStoragePort,
    private val commits: AtomicInteger
) : NimbusStoragePort {

    override fun atomicMove(
        sourcePath: String,
        destinationPath: String
    ): KResult<Unit, MoveFileError> {
        commits.incrementAndGet()
        return delegate.atomicMove(sourcePath, destinationPath)
    }

    override fun exists(path: String): KResult<Boolean, DoesFileExistError> = delegate.exists(path)
    override fun create(path: String): KResult<Unit, CreateFileError> = delegate.create(path)
    override fun size(path: String): KResult<Long, LocalFileSizeError> = delegate.size(path)
    override fun delete(path: String): KResult<Unit, DeleteFileError> = delegate.delete(path)
    override fun source(path: String): KResult<Source, GetFileSourceError> = delegate.source(path)

    override fun usableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError> =
        delegate.usableSpaceBytes(path)

    override fun sink(path: String, hasToAppend: Boolean): KResult<Sink, GetFileSinkError> =
        delegate.sink(path, hasToAppend)
}
