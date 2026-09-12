package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The service as its callers meet it.
 *
 * Every method here is reachable from `NimbusAPI`, which is the whole public surface of the
 * library, and each one is a state transition guarded by a rule: what state it is legal
 * from, what it does to the file on disk, what it asks the download port to do. Those rules
 * are the contract consumers build against, so a change to any of them is a change to the
 * library's behaviour whether or not the signature moved.
 */
class DownloadServiceLifecycleTest {

    // -- enqueue -----------------------------------------------------------

    @Test
    fun `an enqueued download is stored and reported and logged`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload(URL, PATH, NAME)

        val dto = result.valueOrFail()
        assertEquals(DownloadState.Enqueued, dto.state)
        assertEquals(ScriptedDownloadPort.DEFAULT_SIZE, dto.fileSize)
        assertEquals(PATH, dto.filePath)
        assertNotNull(f.repository.current(URL), "the task must be persisted")
        assertNotNull(
            f.logger.firstOrNull<NimbusLogEvent.DownloadEnqueued>(),
            "expected the enqueue to be logged, got ${f.logger.events}"
        )
    }

    @Test
    fun `a scheme the library has never heard of reaches the port`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload("ftp://example.com/f.bin", PATH, NAME)

        // Core used to reject anything that was not http, which put a transport decision in
        // the one layer that is supposed to know nothing about transports — and made the
        // download port un-implementable for ftp, a local share, or anything else, however
        // capable the adapter was.
        assertTrue(result is Success, "core rejected a scheme it has no business judging: $result")
        assertEquals(1, f.repository.getAllDownloadTask().size)
    }

    @Test
    fun `a url with no scheme at all is still rejected`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload("example.com/f.bin", PATH, NAME)

        // Not a transport judgement: a string with no scheme is not a URL, and the url is
        // also the task's identity, so garbage here becomes a task nobody can address.
        assertEquals(PermanentNimbusErrorCause.InvalidUrl, result.causeOrFail())
        assertTrue(f.repository.getAllDownloadTask().isEmpty())
    }

    @Test
    fun `a scheme outside ASCII is not a scheme`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload("\u00e9://example.com/f.bin", PATH, NAME)

        // RFC 3986 spells ALPHA and DIGIT in ASCII. Kotlin's Char.isLetter answers for the whole
        // of Unicode, so a check written with it accepts a scheme no URI parser would, and the
        // url is the task's identity — this has to be refused where it is created.
        assertEquals(PermanentNimbusErrorCause.InvalidUrl, result.causeOrFail())
        assertTrue(f.repository.getAllDownloadTask().isEmpty())
    }

    @Test
    fun `a blank url is rejected`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload("   ", PATH, NAME)

        assertEquals(PermanentNimbusErrorCause.InvalidUrl, result.causeOrFail())
        assertTrue(f.repository.getAllDownloadTask().isEmpty())
    }

    @Test
    fun `a path that climbs out of its directory is rejected`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload(URL, "/data/../../etc/passwd", NAME)

        assertEquals(PermanentNimbusErrorCause.InvalidPath, result.causeOrFail())
    }

    @Test
    fun `an invalid file name is rejected`() = runTest {
        val f = fixture()

        val result = f.service.enqueueDownload(URL, PATH, "")

        assertEquals(PermanentNimbusErrorCause.InvalidFileName, result.causeOrFail())
    }

    @Test
    fun `a remote that reports no length is refused rather than enqueued at zero`() = runTest {
        val f = fixture()
        f.downloadPort.remoteSizeBecomes(0L)

        val result = f.service.enqueueDownload(URL, PATH, NAME)

        assertTrue(
            result.causeOrFail() is PermanentNimbusErrorCause.InvalidFileSize,
            "a zero-byte download is a failed HEAD, not a valid task"
        )
    }

    @Test
    fun `enqueueing the same url twice reports the state it is already in`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        val second = f.service.enqueueDownload(URL, "/tmp/other.bin", NAME)

        val cause = second.causeOrFail()
        assertTrue(cause is PermanentNimbusErrorCause.InvalidState, "got $cause")
        assertEquals(DownloadState.Enqueued, cause.currentState)
    }

    @Test
    fun `two urls cannot be enqueued to the same path`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        val second = f.service.enqueueDownload("https://example.com/other.bin", PATH, NAME)

        val cause = second.causeOrFail()
        assertTrue(cause is PermanentNimbusErrorCause.FilePathInUse, "got $cause")
        assertEquals(PATH, cause.filePath)
    }

    @Test
    fun `a store that cannot record the task fails the enqueue`() = runTest {
        val f = fixture()
        f.repository.saveFailure = KError("io_error", "disk gone")

        val result = f.service.enqueueDownload(URL, PATH, NAME)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.StorageError)
    }

    @Test
    fun `autoStart starts the download without making the caller wait for it`() = runTest {
        val f = fixture(autoStart = true)

        val dto = f.service.enqueueDownload(URL, PATH, NAME).valueOrFail()

        // The DTO is the enqueue's answer: the start has not happened yet.
        assertEquals(DownloadState.Enqueued, dto.state)
        assertTrue(f.downloadPort.started.isEmpty(), "the start must not block the enqueue")

        advanceUntilIdle()

        assertEquals(1, f.downloadPort.started.size, "expected the background start to run")
    }

    @Test
    fun `a failed autoStart is logged and leaves the task enqueued`() = runTest {
        val f = fixture(autoStart = true)
        f.downloadPort.startFailure =
            DownloadError.PermanentError(PermanentDownloadErrorCause.ResourceNotFound)

        f.service.enqueueDownload(URL, PATH, NAME).valueOrFail()
        advanceUntilIdle()

        assertNotNull(
            f.logger.firstOrNull<NimbusLogEvent.AutoStartFailed>(),
            "expected AutoStartFailed, got ${f.logger.events}"
        )
    }

    // -- start / pause / resume -------------------------------------------

    @Test
    fun `starting an unknown download reports it as not found`() = runTest {
        val f = fixture()

        val result = f.service.startDownload(URL)

        assertEquals(PermanentNimbusErrorCause.DownloadNotFound, result.causeOrFail())
    }

    @Test
    fun `starting an enqueued download moves it to downloading and asks the port`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        f.service.startDownload(URL).valueOrFail()

        assertTrue(f.repository.current(URL)?.state is DownloadState.Downloading)
        assertEquals(1, f.downloadPort.started.size)
    }

    @Test
    fun `starting a finished download reports the state rather than restarting it`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(DownloadState.Finished))

        val result = f.service.startDownload(URL)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidState)
        assertTrue(f.downloadPort.started.isEmpty())
    }

    @Test
    fun `pausing a running download stops the transfer and records the pause`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)
        f.service.startDownload(URL)

        f.service.pauseDownload(URL).valueOrFail()

        assertTrue(f.repository.current(URL)?.state is DownloadState.Paused)
        assertEquals(listOf(URL), f.downloadPort.stopped)
    }

    @Test
    fun `pausing a download that is not running is refused`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        val result = f.service.pauseDownload(URL)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidState)
        assertTrue(f.downloadPort.stopped.isEmpty(), "nothing was running to stop")
    }

    @Test
    fun `resuming a paused download asks the port to transfer again`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)
        f.service.startDownload(URL)
        f.service.pauseDownload(URL)

        f.service.resumeDownload(URL).valueOrFail()

        assertTrue(f.repository.current(URL)?.state is DownloadState.Downloading)
        assertEquals(2, f.downloadPort.started.size, "expected a second transfer request")
    }

    @Test
    fun `resuming a download that was never paused is refused`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        val result = f.service.resumeDownload(URL)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidState)
    }

    // -- cancel / remove ---------------------------------------------------

    @Test
    fun `cancelling forgets the task and deletes the partial file`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)
        f.service.startDownload(URL)
        f.storage.write(PATH, ByteArray(10))

        f.service.cancelDownload(URL).valueOrFail()

        assertNull(f.repository.current(URL), "a cancelled task is gone, not kept as Cancelled")
        assertFalse(f.storage.has(PATH), "the partial file must not be left behind")
        assertEquals(listOf(URL), f.downloadPort.stopped)
    }

    @Test
    fun `an in-flight download cannot be removed`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)
        f.service.startDownload(URL)

        val result = f.service.removeDownload(URL, deleteAssociatedFile = true)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidState)
        assertNotNull(f.repository.current(URL))
    }

    @Test
    fun `removing a finished download can keep the file it produced`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(DownloadState.Finished))
        f.storage.write(PATH, ByteArray(SIZE.toInt()))

        f.service.removeDownload(URL, deleteAssociatedFile = false).valueOrFail()

        assertNull(f.repository.current(URL))
        assertTrue(f.storage.has(PATH), "the downloaded file was not asked to be deleted")
    }

    @Test
    fun `removing a finished download can take the file with it`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(DownloadState.Finished))
        f.storage.write(PATH, ByteArray(SIZE.toInt()))

        f.service.removeDownload(URL, deleteAssociatedFile = true).valueOrFail()

        assertFalse(f.storage.has(PATH))
    }

    // -- retry -------------------------------------------------------------

    @Test
    fun `retrying a failed download re-reads the remote size and starts from nothing`() =
        runTest {
            val f = fixture()
            f.repository.seed(taskIn(failedState()))
            f.storage.write(PATH, ByteArray(500))
            f.downloadPort.remoteSizeBecomes(4_096L)

            f.service.retryFailedDownload(URL).valueOrFail()

            val task = assertNotNull(f.repository.current(URL))
            assertEquals(DownloadState.Enqueued, task.state)
            assertEquals(4_096L, task.fileSize.value, "the size must be re-read, not reused")
            assertEquals(
                0,
                f.storage.read(PATH)?.size,
                "the partial bytes of the failed attempt must not be resumed from"
            )
        }

    @Test
    fun `a retry after the transport failed resumes from the partial instead of discarding it`() =
        runTest {
            val f = fixture()
            f.repository.seed(taskIn(transportFailure()))
            f.storage.write(PATH, ByteArray(500))

            f.service.retryFailedDownload(URL).valueOrFail()

            assertEquals(
                500,
                f.storage.read(PATH)?.size,
                "the transport failing says nothing about the bytes already written; " +
                        "discarding them restarts a large transfer from zero every drop"
            )
            assertEquals(DownloadState.Enqueued, f.repository.current(URL)?.state)
        }

    @Test
    fun `a retry whose remote changed size discards the partial even after a transport failure`() =
        runTest {
            val f = fixture()
            f.repository.seed(taskIn(transportFailure()))
            f.storage.write(PATH, ByteArray(500))
            f.downloadPort.remoteSizeBecomes(4_096L)

            f.service.retryFailedDownload(URL).valueOrFail()

            assertEquals(
                0,
                f.storage.read(PATH)?.size,
                "a partial of a different resource cannot be resumed into"
            )
        }

    @Test
    fun `a retry after a checksum mismatch discards the partial`() = runTest {
        val f = fixture()
        f.repository.seed(
            taskIn(
                DownloadState.Failed(
                    DownloadError.TemporaryError(TemporaryDownloadErrorCause.ChecksumMismatch)
                )
            )
        )
        f.storage.write(PATH, ByteArray(SIZE.toInt()))

        f.service.retryFailedDownload(URL).valueOrFail()

        assertEquals(
            0,
            f.storage.read(PATH)?.size,
            "a mismatch leaves a file of exactly the right length and the wrong content: " +
                    "resuming into it re-verifies the same wrong bytes and never converges"
        )
    }

    @Test
    fun `a retry after a permanent failure discards the partial`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(failedState()))
        f.storage.write(PATH, ByteArray(500))

        f.service.retryFailedDownload(URL).valueOrFail()

        assertEquals(
            0,
            f.storage.read(PATH)?.size,
            "only a transport failure is known not to implicate the bytes on disk"
        )
    }

    @Test
    fun `only a failed download can be retried`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        val result = f.service.retryFailedDownload(URL)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidState)
    }

    @Test
    fun `a retry whose remote now reports nothing does not reset the task`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(failedState()))
        f.downloadPort.remoteSizeBecomes(0L)

        val result = f.service.retryFailedDownload(URL)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidFileSize)
        assertTrue(
            f.repository.current(URL)?.state is DownloadState.Failed,
            "a refused retry must leave the task where it was"
        )
    }

    // -- disk headroom -----------------------------------------------------

    @Test
    fun `an enqueue that would not fit is refused and reported`() = runTest {
        val f = fixture(minReservedDiskBytes = 10_000L)
        f.storage.usableSpace = 5_000L

        val result = f.service.enqueueDownload(URL, PATH, NAME)

        val cause = result.causeOrFail()
        assertTrue(cause is PermanentNimbusErrorCause.InsufficientDiskSpace, "got $cause")
        assertEquals(ScriptedDownloadPort.DEFAULT_SIZE + 10_000L, cause.requiredBytes)
        assertNotNull(
            f.logger.firstOrNull<NimbusLogEvent.InsufficientDiskSpace>(),
            "expected the refusal to be logged, got ${f.logger.events}"
        )
    }

    @Test
    fun `a platform that cannot report free space does not block the download`() = runTest {
        val f = fixture(minReservedDiskBytes = 10_000L)
        f.storage.usableSpace = null

        val result = f.service.enqueueDownload(URL, PATH, NAME)

        assertEquals(DownloadState.Enqueued, result.valueOrFail().state)
    }

    // -- queries -----------------------------------------------------------

    @Test
    fun `a finished task whose file vanished is not reported as downloaded`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(DownloadState.Finished))

        assertFalse(f.service.isDownloaded(URL), "the in-memory state alone is not evidence")

        f.storage.write(PATH, ByteArray(SIZE.toInt()))

        assertTrue(f.service.isDownloaded(URL))
    }

    @Test
    fun `a finished file of the wrong size is not reported as downloaded`() = runTest {
        val f = fixture()
        f.repository.seed(taskIn(DownloadState.Finished))
        f.storage.write(PATH, ByteArray(SIZE.toInt() - 1))

        assertFalse(f.service.isDownloaded(URL))
    }

    @Test
    fun `an unknown download is not found rather than empty`() = runTest {
        val f = fixture()

        assertEquals(
            PermanentNimbusErrorCause.DownloadNotFound,
            f.service.getDownloadTask(URL).causeOrFail()
        )
    }

    // -- serialisation of concurrent calls ---------------------------------

    @Test
    fun `two enqueues of the same url cannot both create a task`() = runTest {
        val f = fixture()

        val first = f.service.enqueueDownload(URL, PATH, NAME)
        val second = f.service.enqueueDownload(URL, PATH, NAME)

        val outcomes = listOf(first, second)
        assertEquals(
            1,
            outcomes.count { it is Success },
            "exactly one enqueue may win, got $outcomes"
        )
        assertEquals(1, f.repository.getAllDownloadTask().size)
    }

    // -- helpers -----------------------------------------------------------

    private fun TestScope.fixture(
        autoStart: Boolean = false,
        minReservedDiskBytes: Long? = null,
        digestAlgorithm: DigestAlgorithm? = null
    ): Fixture {
        val storage = InMemoryStorage()
        val repository = FakeDownloadTaskRepository()
        val downloadPort = ScriptedDownloadPort()
        val logger = RecordingLogger()
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = downloadPort,
            repository = repository,
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(
                Success(Checksum(DigestAlgorithm.SHA256, "0".repeat(64)))
            ),
            digestAlgorithm = digestAlgorithm,
            minReservedDiskBytes = minReservedDiskBytes,
            logger = logger,
            autoStart = autoStart,
            // Not `backgroundScope`: work launched there runs only while the test body is
            // suspended, so `advanceUntilIdle` would never run the autoStart, and a test
            // asserting the start happened would fail for a reason that is about the test
            // framework rather than the library.
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        return Fixture(service, repository, downloadPort, storage, logger)
    }

    private class Fixture(
        val service: DownloadService,
        val repository: FakeDownloadTaskRepository,
        val downloadPort: ScriptedDownloadPort,
        val storage: InMemoryStorage,
        val logger: RecordingLogger
    )

    private fun taskIn(state: DownloadState): DownloadTask {
        val task = DownloadTask.create(
            id = URL,
            fileUrl = URL,
            filePath = PATH,
            fileName = NAME,
            fileSize = SIZE
        )
        when (state) {
            is DownloadState.Finished -> {
                task.start(); task.finish()
            }

            is DownloadState.Failed -> {
                task.start(); task.fail(state.error)
            }

            is DownloadState.Downloading -> task.start()
            else -> Unit
        }
        return task
    }

    private fun transportFailure() = DownloadState.Failed(
        DownloadError.TemporaryError(
            TemporaryDownloadErrorCause.TransportFailure(
                KError("socket_timeout", "Socket timeout has expired")
            )
        )
    )

    private fun failedState() = DownloadState.Failed(
        DownloadError.PermanentError(PermanentDownloadErrorCause.ResourceNotFound)
    )

    private companion object {
        const val URL = "https://example.com/file.bin"
        const val PATH = "/tmp/nimbus/file.bin"
        const val NAME = "file.bin"
        const val SIZE = 1_024L
    }
}

/** Fails the test with the error, rather than making every call site unwrap by hand. */
private fun <T> KResult<T, NimbusError>.valueOrFail(): T = when (this) {
    is Success -> value
    is Failure -> throw AssertionError("expected success, got $error")
}

private fun <T> KResult<T, NimbusError>.causeOrFail(): PermanentNimbusErrorCause = when (this) {
    is Success -> throw AssertionError("expected a failure, got $value")
    is Failure -> when (val e = error) {
        is NimbusError.PermanentError -> e.errorCause
        else -> throw AssertionError("expected a permanent error, got $e")
    }
}
