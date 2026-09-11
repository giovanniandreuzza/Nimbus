package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
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
    fun `checksum without a configured algorithm reports a named cause, not the catch-all`() =
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

    private fun TestScope.fixture(digestAlgorithm: DigestAlgorithm?): Fixture {
        val storage = InMemoryStorage()
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = ScriptedDownloadPort(),
            repository = FakeDownloadTaskRepository(),
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
        return Fixture(service)
    }

    private class Fixture(val service: DownloadService)

    private companion object {
        const val URL = "https://example.com/payload.bin"
        const val PATH = "/tmp"
        const val NAME = "payload.bin"
    }
}
