package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.digestPortFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reading the body and writing it out both happen inside the callback Nimbus hands to a
 * [NimbusDownloadPort], and on every platform both fail as `kotlinx.io.IOException`. Only one
 * of them is worth retrying: a body that stopped arriving resumes from what is on disk, while
 * a disk with no room will have no more room on the next attempt either.
 *
 * The port implementation cannot draw that line — it sees one `catch` covering both — so the
 * adapter draws it here, which is also why an implementation does not have to. These two tests
 * are what that guarantee rests on.
 */
class TransferFailureClassificationTest {

    @Test
    fun `a body that stops arriving is the transport and is retried`() = runTest {
        val h = Harness(this)
        val adapter = h.adapter(BodyDiesPort(prefix = 32), maxRetryAttempts = 2)

        adapter.startDownload(h.task())
        advanceUntilIdle()

        val error = h.callback.failures[ID] ?: fail("expected a failure, got ${h.callback.failures}")
        val temporary = error as? DownloadError.TemporaryError
            ?: fail("a dropped body must be retried, got $error")
        assertEquals("transport_failure", temporary.errorCause.code)
    }

    @Test
    fun `a sink that cannot be written is storage and is not retried`() = runTest {
        val h = Harness(this, storage = SinkAlwaysFails())
        val port = CountingPort(ByteArray(256))
        val adapter = h.adapter(port, maxRetryAttempts = 3)

        adapter.startDownload(h.task())
        advanceUntilIdle()

        val error = h.callback.failures[ID] ?: fail("expected a failure, got ${h.callback.failures}")
        assertTrue(
            error is DownloadError.PermanentError &&
                    error.errorCause is PermanentDownloadErrorCause.StorageError,
            "a full disk must not be retried as a congested link, got $error"
        )
        assertEquals(
            1,
            port.attempts,
            "retrying a disk that has no room wastes the budget and never converges"
        )
    }

    // -----------------------------------------------------------------------

    private class Harness(
        private val scope: TestScope,
        private val storage: NimbusStoragePort = InMemoryStorage()
    ) {
        val callback = RecordingCallback()

        fun task() = DownloadTaskDTO(
            id = ID,
            fileName = ID,
            fileUrl = "https://example.com/$ID",
            filePath = PATH,
            fileSize = 256L,
            state = DownloadState.Downloading(0.0)
        )

        fun adapter(port: NimbusDownloadPort, maxRetryAttempts: Int) = DownloadAdapter(
            concurrencyLimit = 1,
            downloadScope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
            ),
            downloadProgressCallback = callback,
            nimbusStoragePort = storage,
            nimbusDownloadPort = port,
            bufferSize = 64L,
            notifyEveryBytes = 128L,
            maxRetryAttempts = maxRetryAttempts,
            retryBaseDelayMs = 1L,
            digestAlgorithm = null,
                contentDigestPort = digestPortFor(storage)
        )
    }

    private class RecordingCallback : DownloadProgressCallback {
        val failures = mutableMapOf<String, DownloadError>()
        override suspend fun onDownloadProgress(id: String, progress: Double) = Unit
        override suspend fun onDownloadFailed(id: String, error: DownloadError) {
            failures[id] = error
        }

        override suspend fun onDownloadFinished(id: String, checksum: Checksum?) = Unit
    }

    private companion object {
        const val ID = "task-0"
        const val PATH = "/tmp/nimbus/task-0"
    }
}

/** Serves a prefix and then fails, the way a connection dropped mid-transfer does. */
private class BodyDiesPort(private val prefix: Int) : NimbusDownloadPort {

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(256L)

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        onSourceOpened(DyingSource(prefix).buffered())
        return Success(Unit)
    }
}

private class DyingSource(private val prefix: Int) : RawSource {
    private var served = 0
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (served >= prefix) throw IOException("Connection reset by peer")
        val n = minOf(byteCount, (prefix - served).toLong()).toInt()
        sink.write(ByteArray(n))
        served += n
        return n.toLong()
    }

    override fun close() = Unit
}

/** Counts how many transfers the adapter was willing to open. */
private class CountingPort(private val content: ByteArray) : NimbusDownloadPort {

    var attempts: Int = 0
        private set

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> =
        Success(content.size.toLong())

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        attempts++
        onSourceOpened(Buffer().apply { write(content) })
        return Success(Unit)
    }
}

/** Everything works except writing, which is the shape of a volume with no room left. */
private class SinkAlwaysFails(
    private val delegate: InMemoryStorage = InMemoryStorage()
) : NimbusStoragePort by delegate {
    override fun sink(path: String, hasToAppend: Boolean): KResult<Sink, GetFileSinkError> =
        Success(RefusingSink().buffered())
}

private class RefusingSink : RawSink {
    override fun write(source: Buffer, byteCount: Long): Unit =
        throw IOException("No space left on device")

    override fun flush() = Unit
    override fun close() = Unit
}
