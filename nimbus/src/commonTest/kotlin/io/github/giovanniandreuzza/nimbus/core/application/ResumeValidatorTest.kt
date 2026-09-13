package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A file that changed at the origin while a partial of it sat on disk.
 *
 * Resuming asks for the bytes from an offset and appends them to what is already there. If the
 * file at the other end is no longer the file that prefix came from, what lands on disk is the
 * head of one file and the tail of another — and when the two are the same length, which is
 * exactly what a re-encode at the same bitrate or a regenerated manifest produces, every check
 * this library had passed it. The size matched. Only a digest could see it, and the digest is
 * opt-in.
 *
 * So the size is no longer asked to answer alone: the origin also says what identifies *this
 * version* of the file, and that goes back with the resume.
 */
class ResumeValidatorTest {

    @Test
    fun `the validator the origin gave is kept with the task`() = runTest {
        val f = fixture()
        f.downloadPort.remoteValidator = "\"v1\""

        f.service.enqueueDownload(URL, PATH, NAME)

        assertEquals(
            "\"v1\"",
            f.repository.current(URL)?.resumeValidator,
            "a resume days later has nothing else to compare against"
        )
    }

    @Test
    fun `a retry keeps the partial when the file is still the same one`() = runTest {
        val f = fixture()
        f.downloadPort.remoteValidator = "\"v1\""
        f.failedTaskWithPartial(validator = "\"v1\"")

        f.service.retryFailedDownload(URL).valueOrFail()

        assertEquals(
            PARTIAL,
            f.storage.read(PATH)?.size,
            "a transport failure says nothing about the bytes already written: throwing them " +
                    "away restarts a large transfer from zero on every drop"
        )
    }

    @Test
    fun `a retry discards the partial when the origin no longer recognises it`() = runTest {
        // Same length, different file — the case the size check cannot see. Discarding is
        // cheap; appending the tail of a new file to the prefix of an old one is a corrupt
        // file of exactly the right length.
        val f = fixture()
        f.downloadPort.remoteValidator = "\"v2\""
        f.failedTaskWithPartial(validator = "\"v1\"")

        f.service.retryFailedDownload(URL).valueOrFail()

        assertEquals(
            0,
            f.storage.read(PATH)?.size,
            "the partial came from a file that no longer exists at that url"
        )
    }

    @Test
    fun `the task adopts the new validator so the next resume compares against it`() = runTest {
        val f = fixture()
        f.downloadPort.remoteValidator = "\"v2\""
        f.failedTaskWithPartial(validator = "\"v1\"")

        f.service.retryFailedDownload(URL).valueOrFail()

        assertEquals("\"v2\"", f.repository.current(URL)?.resumeValidator)
    }

    @Test
    fun `a transport with no validator keeps working exactly as before`() = runTest {
        // Null means "this transport has no such notion", not "the file changed". A port that
        // never reports one must not have every one of its resumes thrown away.
        val f = fixture()
        f.downloadPort.remoteValidator = null
        f.failedTaskWithPartial(validator = null)

        f.service.retryFailedDownload(URL).valueOrFail()

        assertEquals(PARTIAL, f.storage.read(PATH)?.size)
    }

    private fun TestScope.fixture(): Fixture {
        val storage = InMemoryStorage()
        val repository = FakeDownloadTaskRepository()
        val downloadPort = ScriptedDownloadPort(remoteSize = SIZE)
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = downloadPort,
            repository = repository,
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(
                Success(Checksum(DigestAlgorithm.SHA256, "0".repeat(64)))
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
        return Fixture(service, repository, downloadPort, storage)
    }

    private class Fixture(
        val service: DownloadService,
        val repository: FakeDownloadTaskRepository,
        val downloadPort: ScriptedDownloadPort,
        val storage: InMemoryStorage
    ) {
        /** A task that failed on the link, with the bytes it managed to fetch still on disk. */
        fun failedTaskWithPartial(validator: String?) {
            repository.seed(
                DownloadTask.restore(
                    id = URL,
                    fileUrl = URL,
                    filePath = PATH,
                    fileName = NAME,
                    fileSize = SIZE,
                    state = DownloadState.Failed(
                        DownloadError.TemporaryError(
                            TemporaryDownloadErrorCause.TransportFailure(
                                TemporaryDownloadErrorCause.FileNotAccessible
                            )
                        )
                    ),
                    resumeValidator = validator
                )
            )
            storage.write(PATH, ByteArray(PARTIAL))
        }
    }

    private fun <T> io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<T, NimbusError>.valueOrFail(): T =
        when (this) {
            is Success -> value
            is Failure -> fail("expected success, got $error")
        }

    private companion object {
        const val URL = "https://example.com/asset.bin"
        const val PATH = "/tmp/nimbus/asset.bin"
        const val NAME = "asset.bin"
        const val SIZE = 1_000L
        const val PARTIAL = 400
    }
}
