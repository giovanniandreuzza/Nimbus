package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.RemoteFile
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.MidJitter
import io.github.giovanniandreuzza.nimbus.testing.RecordingLogger
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What reaches a logger when something throws that nobody planned for.
 *
 * Every other event carries a `KError`: a code and a message, enough to act on. For a cause
 * the library itself did not foresee that is not enough — the message arrives at a monitoring
 * backend as "null" or "Index 3 out of bounds", with nothing saying where it came from, on a
 * device nobody can attach a debugger to. The throwable is handed over whole and the logger
 * decides what to do with it.
 */
class UnexpectedThrowableTest {

    @Test
    fun `an unforeseen throwable reaches the logger intact`() = runTest {
        val boom = IllegalStateException("the port did something nobody planned for")
        val logger = RecordingLogger()
        val storage = InMemoryStorage()
        val adapter = adapter(ThrowingPort(boom), logger, storage)

        adapter.startDownload(task())
        advanceUntilIdle()

        val unexpected = logger.events.filterIsInstance<NimbusLogEvent.Unexpected>().singleOrNull()
            ?: fail("nothing was logged: ${logger.events}")
        assertEquals(URL, unexpected.fileUrl)
        assertTrue(
            unexpected.throwable === boom,
            "the throwable itself, not a rendering of it: the stack is the part worth having"
        )
    }

    @Test
    fun `the caller still gets the typed error it can act on`() = runTest {
        // The event is in addition to the failure, not instead of it. A caller branching on
        // `UnexpectedError` must not have to read a logger to find out.
        val logger = RecordingLogger()
        val storage = InMemoryStorage()
        var failure: DownloadError? = null
        val adapter = adapter(
            ThrowingPort(IllegalStateException("boom")),
            logger,
            storage,
            onFailed = { failure = it }
        )

        adapter.startDownload(task())
        advanceUntilIdle()

        val permanent = failure as? DownloadError.PermanentError ?: fail("got $failure")
        assertTrue(
            permanent.errorCause is PermanentDownloadErrorCause.UnexpectedError,
            "got ${permanent.errorCause}"
        )
    }

    private fun kotlinx.coroutines.test.TestScope.adapter(
        port: NimbusDownloadPort,
        logger: RecordingLogger,
        storage: InMemoryStorage,
        onFailed: (DownloadError) -> Unit = {}
    ): DownloadAdapter {
        val callback = object : DownloadProgressCallback {
            override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
            override suspend fun onDownloadFailed(id: String, error: DownloadError) {
                onFailed(error)
            }

            override suspend fun onDownloadFinished(id: String, checksum: Checksum?) = Unit
        }
        return DownloadAdapter(
            concurrencyLimit = 1,
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            downloadProgressCallback = callback,
            nimbusStoragePort = storage,
            nimbusDownloadPort = port,
            bufferSize = 64L,
            notifyEveryBytes = 128L,
            transportRetry = RetryPolicy(maxAttempts = 0, baseDelayMs = 1L, maxDelayMs = 1L),
            random = MidJitter,
            stallTimeoutMs = null,
            digestAlgorithm = null,
            contentDigestPort = digestPortFor(storage),
            logger = logger
        )
    }

    private fun task() = DownloadTaskDTO(
        id = ID,
        fileName = "clip.mp4",
        fileUrl = URL,
        filePath = PATH,
        fileSize = 1_024L,
        state = DownloadState.Downloading(0.0)
    )

    private companion object {
        const val ID = "task-boom"
        const val URL = "https://example.com/clip.mp4"
        const val PATH = "/tmp/nimbus/clip.mp4"
    }
}

/** A port that fails in a way the library has no branch for. */
private class ThrowingPort(private val boom: Throwable) : NimbusDownloadPort {
    override suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFile, GetFileSizeError> =
        Success(RemoteFile(1_024L))

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        resumeValidator: String?,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> = throw boom
}
