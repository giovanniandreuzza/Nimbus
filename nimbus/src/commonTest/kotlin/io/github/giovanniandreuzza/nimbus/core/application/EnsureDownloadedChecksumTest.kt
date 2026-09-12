package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `ensureDownloaded` does with an expected checksum when the task already exists.
 *
 * `ensureDownloaded` is the call for "I need this file, whatever state it is in", so the
 * task usually does already exist — enqueued by an earlier run, or paused. Carrying the
 * expectation only into the branch that creates a task means the argument is honoured
 * exactly in the case the caller could have handled themselves, and silently dropped in the
 * case they reached for this method to handle. Silently is the problem: the download then
 * completes, reports success, and was never checked against the digest the caller supplied.
 */
class EnsureDownloadedChecksumTest {

    @Test
    fun `an expectation reaches a task that was enqueued without one`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        f.service.ensureDownloaded(URL, PATH, NAME, expectedChecksum = EXPECTED)
        advanceUntilIdle()

        val task = when (val r = f.service.getDownloadTask(URL)) {
            is Success -> r.value
            is Failure -> throw AssertionError("task disappeared: ${r.error}")
        }
        assertEquals(
            EXPECTED,
            task.expectedChecksum,
            "the expectation the caller supplied must reach the task that gets started"
        )
    }

    @Test
    fun `a new expectation replaces the old one on a task still in flight`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME, expectedChecksum = EXPECTED)

        f.service.ensureDownloaded(URL, PATH, NAME, expectedChecksum = OTHER)
        advanceUntilIdle()

        val task = when (val r = f.service.getDownloadTask(URL)) {
            is Success -> r.value
            is Failure -> throw AssertionError("task disappeared: ${r.error}")
        }
        assertEquals(
            OTHER,
            task.expectedChecksum,
            "the caller's latest word governs a verification that has not happened yet"
        )
    }

    @Test
    fun `a finished file is not reported complete against a different expectation`() = runTest {
        val f = fixture()
        val finished = DownloadTask.create(
            id = URL,
            fileUrl = URL,
            filePath = PATH,
            fileName = NAME,
            fileSize = SIZE,
            expectedChecksum = EXPECTED
        )
        finished.start()
        finished.finish(EXPECTED)
        f.repository.saveDownloadTask(finished)
        f.storage.write(PATH, ByteArray(SIZE.toInt()))

        val result = f.service.ensureDownloaded(URL, PATH, NAME, expectedChecksum = OTHER)
        advanceUntilIdle()

        assertTrue(result is Success, "expected a flow, got $result")
        assertTrue(
            f.logger.events.any { it is NimbusLogEvent.EnsureDownloadedStaleFinishedRemoved },
            "a file accepted against a different digest is stale, not complete: " +
                    "events were ${f.logger.events}"
        )
    }

    @Test
    fun `a stale finished task carries its expectation into the download that replaces it`() =
        runTest {
            // The caller passes nothing, so nothing carries the expectation but the task —
            // and the stale-Finished branch throws the task away. The replacement would then
            // transfer unverified, which is the same silence as accepting an expectation
            // nothing checks, reached from the other side.
            val f = fixture()
            val finished = DownloadTask.create(
                id = URL,
                fileUrl = URL,
                filePath = PATH,
                fileName = NAME,
                fileSize = SIZE,
                expectedChecksum = EXPECTED
            )
            finished.start()
            finished.finish(EXPECTED)
            f.repository.saveDownloadTask(finished)
            // No file on disk: the finished task is stale and is removed and re-enqueued.

            f.service.ensureDownloaded(URL, PATH, NAME, expectedChecksum = null)
            advanceUntilIdle()

            val task = when (val r = f.service.getDownloadTask(URL)) {
                is Success -> r.value
                is Failure -> throw AssertionError("task disappeared: ${r.error}")
            }
            assertEquals(
                EXPECTED,
                task.expectedChecksum,
                "the expectation the store was holding must survive the task being recreated"
            )
        }

    private fun TestScope.fixture(): Fixture {
        val storage = InMemoryStorage()
        val repository = FakeDownloadTaskRepository()
        val logger = RecordingLogger()
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = ScriptedDownloadPort(),
            repository = repository,
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(Success(EXPECTED)),
            digestAlgorithm = DigestAlgorithm.SHA256,
            minReservedDiskBytes = null,
            logger = logger,
            autoStart = false,
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        return Fixture(service, repository, storage, logger)
    }

    private class Fixture(
        val service: DownloadService,
        val repository: FakeDownloadTaskRepository,
        val storage: InMemoryStorage,
        val logger: RecordingLogger
    )

    private companion object {
        const val URL = "https://example.com/payload.bin"
        const val PATH = "/tmp/payload.bin"
        const val NAME = "payload.bin"
        const val SIZE = 64L
        val EXPECTED = Checksum(DigestAlgorithm.SHA256, "a".repeat(64))
        val OTHER = Checksum(DigestAlgorithm.SHA256, "b".repeat(64))
    }
}
