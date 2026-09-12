package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
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
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `checksum` promises when it cannot answer.
 *
 * Its documentation names the cause a caller should branch on. An implementation that
 * reports something else is worse than an undocumented one: a caller writes the exhaustive
 * `when` the documentation describes, and the branch that runs is the catch-all it wrote for
 * failures it could not foresee — for a condition that is entirely foreseeable and entirely
 * the caller's to fix.
 */
class ChecksumContractTest {

    @Test
    fun `checksum without a configured algorithm reports a named cause rather than the catch-all`() =
        runTest {
            val f = fixture(digestAlgorithm = null)
            f.service.enqueueDownload(URL, PATH, NAME)

            val result = f.service.checksum(URL)

            val error = when (result) {
                is Failure -> result.error
                is Success -> throw AssertionError("checksum must fail when no algorithm is set")
            }
            val cause = (error as NimbusError.PermanentError).errorCause
            assertEquals(
                "content_digest_disabled",
                cause.code,
                "the cause must say what is wrong"
            )
            assertFalse(
                cause is PermanentNimbusErrorCause.UnexpectedError,
                "a foreseeable configuration mistake must not arrive as UnexpectedError: a " +
                        "caller branching on the documented contract would take the wrong branch"
            )
        }

    @Test
    fun `checksum on an unfinished task reports InvalidState`() = runTest {
        val f = fixture(digestAlgorithm = DigestAlgorithm.SHA256)
        f.service.enqueueDownload(URL, PATH, NAME)

        val result = f.service.checksum(URL)

        val error = when (result) {
            is Failure -> result.error
            is Success -> throw AssertionError("checksum must fail on an unfinished task")
        }
        val cause = (error as NimbusError.PermanentError).errorCause
        assertTrue(
            cause is PermanentNimbusErrorCause.InvalidState,
            "expected InvalidState, got $cause"
        )
    }

    @Test
    fun `enqueueDownload refuses an expected checksum when no digest is configured`() = runTest {
        val f = fixture(digestAlgorithm = null)

        val result = f.service.enqueueDownload(URL, PATH, NAME, EXPECTED)

        val error = when (result) {
            is Failure -> result.error
            is Success -> throw AssertionError(
                "an expectation nothing will ever check must not be accepted: the download " +
                        "would report finished with no comparison ever made"
            )
        }
        assertEquals(
            "content_digest_disabled",
            (error as NimbusError.PermanentError).errorCause.code,
            "the same cause `checksum` answers for the same configuration"
        )
        assertTrue(
            f.repository.getAllDownloadTask().isEmpty(),
            "a task that cannot be verified must not have been created"
        )
    }

    @Test
    fun `ensureDownloaded refuses an expected checksum when no digest is configured`() = runTest {
        val f = fixture(digestAlgorithm = null)

        val result = f.service.ensureDownloaded(URL, PATH, NAME, EXPECTED)

        val error = when (result) {
            is Failure -> result.error
            is Success -> throw AssertionError(
                "ensureDownloaded is the method a caller reaches for to be sure of the file; " +
                        "it must not silently skip the check they asked for"
            )
        }
        assertEquals(
            "content_digest_disabled",
            (error as NimbusError.PermanentError).errorCause.code
        )
        assertTrue(f.repository.getAllDownloadTask().isEmpty())
    }

    @Test
    fun `enqueueDownload accepts an expected checksum the configured digest can produce`() =
        runTest {
            val f = fixture(digestAlgorithm = DigestAlgorithm.SHA256)

            val result = f.service.enqueueDownload(URL, PATH, NAME, EXPECTED)

            assertTrue(result is Success, "the matching algorithm must still be accepted")
            assertEquals(
                EXPECTED,
                result.value.expectedChecksum,
                "the expectation must reach the task"
            )
        }

    @Test
    fun `startDownload refuses a stored expectation this build cannot check`() = runTest {
        // The task was written by a build that had a digest configured. This one does not:
        // the enqueue-time check never ran here, and without this gate the transfer would
        // run to completion and report finished with the expectation never consulted.
        val f = fixture(digestAlgorithm = null)
        f.repository.saveDownloadTask(
            DownloadTask.restore(
                id = URL,
                fileUrl = URL,
                filePath = PATH,
                fileName = NAME,
                fileSize = 1_024L,
                state = DownloadState.Enqueued,
                expectedChecksum = EXPECTED
            )
        )

        val result = f.service.startDownload(URL)

        val error = when (result) {
            is Failure -> result.error
            is Success -> throw AssertionError("the transfer must not start")
        }
        assertEquals(
            "content_digest_disabled",
            (error as NimbusError.PermanentError).errorCause.code
        )
        assertTrue(
            f.downloadPort.started.isEmpty(),
            "nothing may be transferred for an expectation that cannot be checked"
        )
    }

    private fun TestScope.fixture(digestAlgorithm: DigestAlgorithm?): Fixture {
        val storage = InMemoryStorage()
        val repository = FakeDownloadTaskRepository()
        val downloadPort = ScriptedDownloadPort()
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = downloadPort,
            repository = repository,
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(
                Success(Checksum(DigestAlgorithm.SHA256, "0".repeat(64)))
            ),
            digestAlgorithm = digestAlgorithm,
            minReservedDiskBytes = null,
            logger = RecordingLogger(),
            autoStart = false,
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        return Fixture(service, repository, downloadPort)
    }

    private class Fixture(
        val service: DownloadService,
        val repository: FakeDownloadTaskRepository,
        val downloadPort: ScriptedDownloadPort
    )

    private companion object {
        const val URL = "https://example.com/payload.bin"
        const val PATH = "/tmp"
        const val NAME = "payload.bin"
        val EXPECTED = Checksum(DigestAlgorithm.SHA256, "a".repeat(64))
    }
}
