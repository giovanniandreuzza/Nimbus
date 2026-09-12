package io.github.giovanniandreuzza.nimbus

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * When a flow watching one download is allowed to end.
 *
 * With `autoStart` on, the library retries a failed task by itself, so a flow that completed
 * on the first `Failed` would hide the recovery it was watching for. That is why it stays
 * open — and until now it stayed open for *every* failure, including the ones nothing will
 * ever retry. A 404 left `ensureDownloaded(...).collect()` suspended for the life of the
 * process: a reconciliation loop walking a manifest stops on the first asset the backend
 * removed, and every other file it was meant to keep current stops with it.
 */
class ObserveCompletionTest {

    @Test
    fun `a permanent failure ends the flow even when auto-retry is on`() = runTest {
        val f = fixture(autoStart = true, port = FailingPort(permanent = true))

        val completed = f.watch(URL)
        advanceTimeBy(60_000L)
        f.scope.cancel()

        assertTrue(
            completed(),
            "nothing will retry a 404; a caller waiting on this flow would wait forever"
        )
    }

    @Test
    fun `a temporary failure keeps the flow open when auto-retry is on`() = runTest {
        val f = fixture(autoStart = true, port = FailingPort(permanent = false))

        val completed = f.watch(URL)
        advanceTimeBy(60_000L)

        assertTrue(
            !completed(),
            "the retry is coming, and the flow is how the caller sees it happen"
        )
        f.scope.cancel()
    }

    @Test
    fun `any failure ends the flow when nothing will retry it`() = runTest {
        val f = fixture(autoStart = false, port = FailingPort(permanent = false))

        val completed = f.watch(URL)
        advanceUntilIdle()
        f.scope.cancel()

        assertTrue(
            completed(),
            "without autoStart a failed task stays failed until the caller acts on it"
        )
    }

    private fun TestScope.fixture(autoStart: Boolean, port: NimbusDownloadPort): Fixture {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val api = Nimbus.Builder()
            .withNimbusDownloadPort(port)
            .withNimbusStoragePort(InMemoryStorage())
            .withDownloadManagerPath("/nimbus/store")
            .withDownloadScope(scope)
            .withIODispatcher(StandardTestDispatcher(testScheduler))
            .withAutoStart(autoStart)
            .withTransportRetry(RetryPolicy(maxAttempts = 0, baseDelayMs = 1L, maxDelayMs = 1L))
            .withAutoRetry(RetryPolicy(maxAttempts = null, baseDelayMs = 1_000L, maxDelayMs = 5_000L))
            .build()
            .init()
        return Fixture(api, scope, this)
    }

    private class Fixture(
        val api: NimbusAPI,
        val scope: CoroutineScope,
        private val test: TestScope
    ) {
        /** Starts watching [url] and returns a way to ask whether the flow has ended. */
        suspend fun watch(url: String): () -> Boolean {
            val flow = api.ensureDownloaded(url, PATH, NAME)
                .getOr { fail("ensureDownloaded refused the download: $it") }
            var completed = false
            // backgroundScope: a flow that is *meant* to stay open would otherwise leave the
            // test waiting on its own collector.
            test.backgroundScope.launch {
                flow.onCompletion { completed = true }.collect { }
            }
            return { completed }
        }
    }

    private companion object {
        const val URL = "https://example.com/asset.bin"
        const val PATH = "/tmp/nimbus/asset.bin"
        const val NAME = "asset.bin"
    }

    /** Fails every transfer, permanently or not. */
    private class FailingPort(private val permanent: Boolean) : NimbusDownloadPort {
        override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
            Success(1_024L)

        override suspend fun downloadFile(
            fileUrl: String,
            offset: Long,
            onSourceOpened: suspend (Source) -> Unit
        ): KResult<Unit, DownloadError> = Failure(
            if (permanent) {
                DownloadError.PermanentError(PermanentDownloadErrorCause.ResourceNotFound)
            } else {
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.ServerError(503))
            }
        )
    }
}
