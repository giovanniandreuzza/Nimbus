package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentGetFileSizeErrorCause
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
import io.github.giovanniandreuzza.nimbus.presentation.TemporaryNimbusErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryGetFileSizeErrorCause
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parts of the API a caller meets in the first ten minutes.
 *
 * Two of them were papercuts with teeth. `fileName` was required and used for nothing, so
 * every integration had to guess whether `filePath` was a directory. And the error hierarchy
 * is three levels deep — right for the caller who wants precision, and the reason the
 * repository's own reference consumer once carried a thirty-line `when` that stopped
 * compiling: everyone else writes `else -> report(it)` and loses the precision anyway.
 */
class ErgonomicsTest {

    @Test
    fun `the file name defaults to the last segment of the path`() = runTest {
        val f = fixture()

        val task = f.service.enqueueDownload(URL, "/files/nimbus/videos/clip.mp4")
            .valueOrFail()

        assertEquals("clip.mp4", task.fileName)
    }

    @Test
    fun `a name given explicitly is the one the task carries`() = runTest {
        // It is a label: validated, stored, reported, and used by no file operation.
        val f = fixture()

        val task = f.service.enqueueDownload(URL, "/files/nimbus/a1b2c3", "Opening titles")
            .valueOrFail()

        assertEquals("Opening titles", task.fileName)
        assertEquals("/files/nimbus/a1b2c3", task.filePath, "the path is what gets written")
    }

    @Test
    fun `causeCode reaches past the wrapper to the reason`() = runTest {
        // PermanentError → GetFileSizeFailed → ResourceNotFound. The middle one exists only to
        // carry the last, and matching all three is a when inside a when inside a when.
        val f = fixture()
        f.downloadPort.sizeFailure = GetFileSizeError.PermanentError(
            PermanentGetFileSizeErrorCause.ResourceNotFound
        )

        val error = f.service.enqueueDownload(URL, PATH).errorOrFail()

        assertEquals("resource_not_found", error.causeCode)
        assertEquals(
            "permanent_error",
            error.code,
            "the KError code still says which variant it is; causeCode says why"
        )
        assertFalse(error.isRetryable)
    }

    @Test
    fun `causeCode of a cause that wraps nothing is its own`() = runTest {
        val f = fixture()

        val error = f.service.enqueueDownload("not-a-url", PATH).errorOrFail()

        assertEquals("invalid_url", error.causeCode)
    }

    @Test
    fun `a temporary failure says it is worth trying again`() = runTest {
        val f = fixture()
        f.downloadPort.sizeFailure = GetFileSizeError.TemporaryError(
            TemporaryGetFileSizeErrorCause.ServerError(503)
        )

        val error = f.service.enqueueDownload(URL, PATH).errorOrFail()

        assertTrue(error.isRetryable, "a 503 is the definition of worth trying again")
        assertEquals("server_error", error.causeCode)
        assertTrue(
            (error as NimbusError.TemporaryError).errorCause
                    is TemporaryNimbusErrorCause.GetFileSizeFailed,
            "and the typed hierarchy is still there for callers who want it"
        )
    }

    private fun TestScope.fixture(): Fixture {
        val storage = InMemoryStorage()
        val downloadPort = ScriptedDownloadPort(remoteSize = 64L)
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = downloadPort,
            repository = FakeDownloadTaskRepository(),
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(
                Success(Checksum.of(DigestAlgorithm.SHA256, "0".repeat(64)))
            ),
            clock = FakeClock(),
            downloadRoot = null,
            digestAlgorithm = null,
            minReservedDiskBytes = null,
            logger = RecordingLogger(),
            autoStart = false,
            ownsDownloadScope = false,
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        return Fixture(service, downloadPort)
    }

    private class Fixture(
        val service: DownloadService,
        val downloadPort: ScriptedDownloadPort
    )

    private fun <T> io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<T, NimbusError>.valueOrFail(): T =
        when (this) {
            is Success -> value
            is Failure -> fail("expected success, got $error")
        }

    private fun <T> io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<T, NimbusError>.errorOrFail(): NimbusError =
        when (this) {
            is Failure -> error
            is Success -> fail("expected a failure, got $value")
        }

    private companion object {
        const val URL = "https://example.com/clip.mp4"
        const val PATH = "/files/nimbus/clip.mp4"
    }
}
