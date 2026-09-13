package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.FakeContentDigestPort
import io.github.giovanniandreuzza.nimbus.testing.FakeDownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.RecordingLogger
import io.github.giovanniandreuzza.nimbus.testing.ScriptedDownloadPort
import io.github.giovanniandreuzza.nimbus.testing.UrlAsIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Shutting down on purpose.
 *
 * Non-terminal states are coalesced: an enqueue, a pause or a progress position waits for the
 * next commit, because a commit rewrites every task and paying that per transition makes one
 * download's cost grow with the whole catalogue. That is the right trade until the process is
 * about to end — an Android service being torn down, a provisioning run that reboots the
 * device, a `SIGTERM` from a supervisor — and until now there was no way to say so. The
 * repository had a `flushPendingState` that nothing could call, and a comment naming exactly
 * this case.
 */
class LifecycleTest {

    @Test
    fun `flush commits what was still waiting for a coalesced write`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)

        f.service.flush().valueOrFail()

        assertEquals(1, f.repository.flushCount, "the commit has to be asked for, not implied")
    }

    @Test
    fun `close stops the transfers before it commits`() = runTest {
        val f = fixture()
        f.service.enqueueDownload(URL, PATH, NAME)
        f.service.startDownload(URL)

        f.service.close()

        assertEquals(
            1,
            f.downloadPort.stoppedAll,
            "a transfer still writing while the store is committed makes the commit a guess"
        )
        assertEquals(1, f.repository.flushCount)
    }

    @Test
    fun `a call after close says so instead of quietly doing nothing`() = runTest {
        // The scope is gone, so a download started here would be registered and never run.
        // Reporting success for that is the silent failure this library refuses everywhere
        // else: on an unattended device it surfaces weeks later as a file that never arrived.
        val f = fixture()
        f.service.close()

        val result = f.service.enqueueDownload(URL, PATH, NAME)

        val error = when (result) {
            is Failure -> result.error
            is Success -> fail("a closed instance must not accept work")
        }
        assertTrue(
            (error as NimbusError.PermanentError).errorCause is PermanentNimbusErrorCause.Closed,
            "got ${error.errorCause}"
        )
        assertTrue(!f.service.isDownloaded(URL), "and the boolean answers must not lie either")
    }

    @Test
    fun `closing twice is harmless`() = runTest {
        val f = fixture()

        f.service.close()
        f.service.close()

        assertEquals(
            1,
            f.repository.flushCount,
            "the second close has nothing left to commit and must not pretend otherwise"
        )
    }

    @Test
    fun `a scope the caller supplied is left running`() = runTest {
        // Cancelling it would take down whatever else the app runs in it — in the sample, the
        // scope belongs to a ViewModel that outlives one download manager.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val f = fixture(scope = scope, ownsScope = false)

        f.service.close()

        assertTrue(scope.isActive, "the caller's scope is the caller's to end")
    }

    @Test
    fun `a scope Nimbus made is cancelled with it`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val f = fixture(scope = scope, ownsScope = true)

        f.service.close()

        assertTrue(!scope.isActive, "otherwise closing leaks the threads it started")
    }

    private fun TestScope.fixture(
        scope: CoroutineScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        ),
        ownsScope: Boolean = false
    ): Fixture {
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
            digestAlgorithm = null,
            minReservedDiskBytes = null,
            logger = RecordingLogger(),
            autoStart = false,
            ownsDownloadScope = ownsScope,
            downloadScope = scope
        )
        return Fixture(service, repository, downloadPort)
    }

    private class Fixture(
        val service: DownloadService,
        val repository: FakeDownloadTaskRepository,
        val downloadPort: ScriptedDownloadPort
    )

    private fun io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<Unit, NimbusError>.valueOrFail() {
        if (this is Failure) fail("expected success, got $error")
    }

    private companion object {
        const val URL = "https://example.com/asset.bin"
        const val PATH = "/tmp/nimbus/asset.bin"
        const val NAME = "asset.bin"
    }
}
