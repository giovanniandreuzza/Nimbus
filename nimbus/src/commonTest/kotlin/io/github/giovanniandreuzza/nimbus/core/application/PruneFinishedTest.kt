package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.testing.FakeClock
import io.github.giovanniandreuzza.nimbus.testing.FakeContentDigestPort
import io.github.giovanniandreuzza.nimbus.testing.FakeDownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.RecordingLogger
import io.github.giovanniandreuzza.nimbus.testing.ScriptedDownloadPort
import io.github.giovanniandreuzza.nimbus.testing.UrlAsIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Forgetting what has been finished long enough.
 *
 * A signage player cycles content for years. Every asset it has ever fetched stayed a
 * `Finished` task: a `stat` at every boot, a slot in every commit — and a commit rewrites the
 * whole store. Nothing removed them because nothing recorded *when* they finished, so no
 * caller could tell which ones were old. The timestamp is half the fix and this is the other.
 */
class PruneFinishedTest {

    @Test
    fun `a task finished longer ago than the age is forgotten`() = runTest {
        val f = fixture()
        f.finishedTask(URL, PATH, finishedAt = f.clock.nowEpochMs())
        f.clock.advanceBy(TEN_DAYS)

        val pruned = f.service.pruneFinished(olderThanMs = ONE_DAY).valueOrFail()

        assertEquals(listOf(URL), pruned, "the urls it removed are what a caller logs")
        assertNull(f.repository.current(URL), "and the task is gone")
    }

    @Test
    fun `a task finished recently is kept`() = runTest {
        val f = fixture()
        f.finishedTask(URL, PATH, finishedAt = f.clock.nowEpochMs())
        f.clock.advanceBy(ONE_DAY / 2)

        val pruned = f.service.pruneFinished(olderThanMs = ONE_DAY).valueOrFail()

        assertTrue(pruned.isEmpty(), "pruned $pruned")
        assertTrue(f.repository.current(URL) != null)
    }

    @Test
    fun `a task that has not finished is never pruned however old it is`() = runTest {
        // Age is measured from the finish, and a task still waiting to be downloaded has no
        // finish. Measuring from its creation instead would delete the queue.
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)
        f.clock.advanceBy(365L * ONE_DAY)

        val pruned = f.service.pruneFinished(olderThanMs = ONE_DAY).valueOrFail()

        assertTrue(pruned.isEmpty(), "pruned $pruned")
        assertTrue(f.repository.current(URL) != null)
    }

    @Test
    fun `a task from before the timestamps existed is not pruned on the first call`() = runTest {
        // Its `finishedAtEpochMs` is null: this build has never seen it finish. Treating the
        // absence as "very old" would have the first prune after an upgrade delete every file
        // the device already had. The store migration stamps such tasks at load; a task that
        // reaches here without one is simply left alone.
        val f = fixture()
        f.repository.seed(
            DownloadTask.restore(
                id = URL,
                fileUrl = URL,
                filePath = PATH,
                fileName = NAME,
                fileSize = SIZE,
                state = DownloadState.Finished,
                createdAtEpochMs = 0L,
                finishedAtEpochMs = null
            )
        )
        f.clock.advanceBy(365L * ONE_DAY)

        val pruned = f.service.pruneFinished(olderThanMs = ONE_DAY).valueOrFail()

        assertTrue(pruned.isEmpty(), "pruned $pruned")
    }

    @Test
    fun `a task that stops being finished stops reporting a checksum`() = runTest {
        // The digest described the file that was there. Left behind on a reset it would be
        // reported for whatever arrives next — including a redownload with no digest
        // configured, which never overwrites it.
        val f = fixture()
        f.repository.seed(
            DownloadTask.restore(
                id = URL,
                fileUrl = URL,
                filePath = PATH,
                fileName = NAME,
                fileSize = SIZE,
                state = DownloadState.Finished,
                checksum = Checksum.of(DigestAlgorithm.SHA256, "b".repeat(64)),
                createdAtEpochMs = f.clock.nowEpochMs(),
                finishedAtEpochMs = f.clock.nowEpochMs()
            )
        )

        val task = f.repository.current(URL) ?: fail("seeded task is gone")
        task.resetToEnqueued()

        assertNull(task.checksum, "it describes a file this task no longer has")
        assertNull(task.finishedAtEpochMs)
    }

    @Test
    fun `the file goes only when asked`() = runTest {
        val f = fixture()
        f.finishedTask(URL, PATH, finishedAt = f.clock.nowEpochMs())
        f.storage.write(PATH, ByteArray(SIZE.toInt()))
        f.clock.advanceBy(TEN_DAYS)

        f.service.pruneFinished(olderThanMs = ONE_DAY, deleteFiles = false).valueOrFail()

        assertTrue(
            f.storage.read(PATH) != null,
            "something else on the device may be reading it; forgetting the task is not the " +
                    "same as deleting the file"
        )
    }

    @Test
    fun `the file goes when it is`() = runTest {
        val f = fixture()
        f.finishedTask(URL, PATH, finishedAt = f.clock.nowEpochMs())
        f.storage.write(PATH, ByteArray(SIZE.toInt()))
        f.clock.advanceBy(TEN_DAYS)

        f.service.pruneFinished(olderThanMs = ONE_DAY, deleteFiles = true).valueOrFail()

        assertNull(f.storage.read(PATH), "reclaiming the space is the point of the flag")
    }

    private fun TestScope.fixture(): Fixture {
        val storage = InMemoryStorage()
        val repository = FakeDownloadTaskRepository()
        val clock = FakeClock()
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = ScriptedDownloadPort(remoteSize = SIZE),
            repository = repository,
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(
                Success(Checksum(DigestAlgorithm.SHA256, "0".repeat(64)))
            ),
            clock = clock,
            downloadRoot = null,
            digestAlgorithm = null,
            minReservedDiskBytes = null,
            logger = RecordingLogger(),
            autoStart = false,
            ownsDownloadScope = false,
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        return Fixture(service, repository, storage, clock)
    }

    private class Fixture(
        val service: DownloadService,
        val repository: FakeDownloadTaskRepository,
        val storage: InMemoryStorage,
        val clock: FakeClock
    ) {
        fun finishedTask(url: String, path: String, finishedAt: Long) {
            repository.seed(
                DownloadTask.restore(
                    id = url,
                    fileUrl = url,
                    filePath = path,
                    fileName = NAME,
                    fileSize = SIZE,
                    state = DownloadState.Finished,
                    createdAtEpochMs = finishedAt,
                    finishedAtEpochMs = finishedAt
                )
            )
        }
    }

    private fun <T> io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<T, io.github.giovanniandreuzza.nimbus.presentation.NimbusError>.valueOrFail(): T =
        when (this) {
            is Success -> value
            is Failure -> fail("expected success, got $error")
        }

    private companion object {
        const val URL = "https://example.com/asset.bin"
        const val PATH = "/tmp/nimbus/asset.bin"
        const val NAME = "asset.bin"
        const val SIZE = 64L
        const val ONE_DAY = 24L * 60 * 60 * 1_000
        const val TEN_DAYS = 10 * ONE_DAY
    }
}
