package io.github.giovanniandreuzza.nimbus.infrastructure.repositories

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a failed store commit is reported.
 *
 * Since the store commits non-terminal changes on a coalescing timer, the commit that
 * carries them happens after the save that asked for it has already returned `Success` —
 * there is no caller left to hand a failure to. Without a channel of its own such a
 * failure is simply lost, and a device whose store has silently stopped being writable
 * looks exactly like one whose store is fine until the next reboot loses everything that
 * was pending. `StoreFlushFailed` is that channel.
 *
 * The terminal states keep the opposite contract — they commit before the save returns, so
 * their failure belongs to the caller — and the second test holds the two apart.
 */
class StoreFlushFailureTest {

    @Test
    fun `a commit that fails after the save returned is reported to the logger`() = runTest {
        val h = harness()
        h.repository.loadDownloadTasks()
        h.storage.onAtomicMove = { _, _ ->
            Failure(MoveFileError.IOError(KError("eio", "the volume went away")))
        }

        // Enqueued rides along with the next coalesced commit, so this save returns before
        // anything has been written.
        val save = h.repository.saveDownloadTask(enqueuedTask())
        assertTrue(!save.isFailure(), "a coalesced save reports success to its caller")
        assertNull(
            h.logger.firstOrNull<NimbusLogEvent.StoreFlushFailed>(),
            "nothing has been committed yet"
        )

        advanceUntilIdle()

        val event = assertNotNull(
            h.logger.firstOrNull<NimbusLogEvent.StoreFlushFailed>(),
            "a commit failing with nobody to return it to must reach the logger, " +
                    "got ${h.logger.events}"
        )
        assertNotNull(event.cause, "the event has to carry why the commit failed")
    }

    @Test
    fun `a terminal save reports its failure to the caller rather than the logger`() = runTest {
        val h = harness()
        h.repository.loadDownloadTasks()
        h.storage.onAtomicMove = { _, _ ->
            Failure(MoveFileError.IOError(KError("eio", "the volume went away")))
        }

        val task = enqueuedTask()
        task.start()
        task.finish()
        val save = h.repository.saveDownloadTask(task)

        assertTrue(
            save.isFailure(),
            "a state that must be durable has a caller waiting for the commit: got $save"
        )
        advanceUntilIdle()
        assertNull(
            h.logger.firstOrNull<NimbusLogEvent.StoreFlushFailed>(),
            "a failure already returned to the caller must not be reported twice"
        )
    }

    @Test
    fun `a commit that recovers is not reported`() = runTest {
        val h = harness()
        h.repository.loadDownloadTasks()

        h.repository.saveDownloadTask(enqueuedTask())
        advanceUntilIdle()

        assertNull(
            h.logger.firstOrNull<NimbusLogEvent.StoreFlushFailed>(),
            "a store that committed cleanly must stay quiet, got ${h.logger.events}"
        )
    }

    // -- harness -----------------------------------------------------------

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = InMemoryStorage()
        val logger = RecordingLogger()
        return Harness(
            storage = storage,
            logger = logger,
            repository = DownloadRepository(
                storePath = STORE_PATH,
                dispatcher = dispatcher,
                nimbusStoragePort = storage,
                logger = logger,
                storeScope = CoroutineScope(SupervisorJob() + dispatcher)
            )
        )
    }

    private class Harness(
        val storage: InMemoryStorage,
        val logger: RecordingLogger,
        val repository: DownloadRepository
    )

    private fun enqueuedTask() = DownloadTask.create(
        id = "task-0",
        fileUrl = "https://example.com/task-0",
        filePath = "/tmp/nimbus/task-0",
        fileName = "task-0",
        fileSize = 1_024L
    )

    private companion object {
        const val STORE_PATH = "/tmp/nimbus/download_manager"
    }
}
