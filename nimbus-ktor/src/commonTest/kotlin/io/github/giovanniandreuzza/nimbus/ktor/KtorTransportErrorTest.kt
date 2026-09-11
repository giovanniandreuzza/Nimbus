package io.github.giovanniandreuzza.nimbus.ktor

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryGetFileSizeErrorCause
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A timeout is the normal failure on the links Nimbus's consumers run over, not the
 * exceptional one. `DownloadAdapter.shouldRetry` only retries `DownloadError.TemporaryError`,
 * so classifying a timeout as permanent means `maxRetryAttempts` never applies to the thing
 * it exists for: the download fails on the first stalled cell and stays failed.
 */
class KtorTransportErrorTest {

    private val url = "https://example.test/file.bin"

    private fun adapterThrowing(error: Throwable) =
        KtorDownloadAdapter(HttpClient(MockEngine { throw error }))

    // -----------------------------------------------------------------------
    // Timeouts are temporary
    // -----------------------------------------------------------------------

    @Test
    fun `a socket timeout while asking for the size is temporary`() = runTest {
        val result = adapterThrowing(SocketTimeoutException("Socket timeout has expired"))
            .getFileSize(url)

        assertEquals(
            "transport_failure",
            result.temporarySizeCause().code,
            "a stalled read must be retried, not reported as permanent"
        )
    }

    @Test
    fun `a connect timeout while asking for the size is temporary`() = runTest {
        val result = adapterThrowing(ConnectTimeoutException("Connect timeout has expired"))
            .getFileSize(url)

        assertEquals("transport_failure", result.temporarySizeCause().code)
    }

    @Test
    fun `a request timeout while asking for the size is temporary`() = runTest {
        val result = adapterThrowing(HttpRequestTimeoutException(url, 1_000L))
            .getFileSize(url)

        assertEquals("transport_failure", result.temporarySizeCause().code)
    }

    @Test
    fun `a socket timeout while transferring is temporary`() = runTest {
        val result = adapterThrowing(SocketTimeoutException("Socket timeout has expired"))
            .downloadFile(url, offset = 0L) { fail("the body was never opened") }

        assertEquals(
            "transport_failure",
            result.temporaryDownloadCause().code,
            "a transfer that stalled must be retried"
        )
    }

    @Test
    fun `the underlying transport error is carried on the cause`() = runTest {
        val result = adapterThrowing(SocketTimeoutException("Socket timeout has expired"))
            .downloadFile(url, offset = 0L) { fail("the body was never opened") }

        val cause = result.temporaryDownloadCause()
        assertTrue(
            cause.message.contains("timeout", ignoreCase = true),
            "the diagnosis must survive the mapping, got: ${cause.message}"
        )
    }

    @Test
    fun `a connection reset before the body is temporary`() = runTest {
        val result = adapterThrowing(IOException("Connection reset by peer"))
            .downloadFile(url, offset = 0L) { fail("the body was never opened") }

        assertEquals(
            "transport_failure",
            result.temporaryDownloadCause().code,
            "a reset is as transient as a timeout and far more common on a long transfer"
        )
    }

    @Test
    fun `a name that does not resolve is temporary`() = runTest {
        val result = adapterThrowing(IOException("Unable to resolve host"))
            .getFileSize(url)

        assertEquals("transport_failure", result.temporarySizeCause().code)
    }

    // -----------------------------------------------------------------------
    // Everything else stays permanent
    // -----------------------------------------------------------------------

    @Test
    fun `a failure that is not a timeout stays permanent`() = runTest {
        val result = adapterThrowing(IllegalStateException("engine exploded"))
            .downloadFile(url, offset = 0L) { fail("the body was never opened") }

        val error = (result as? Failure)?.error ?: fail("expected a failure, got $result")
        assertTrue(
            error is DownloadError.PermanentError,
            "only the transport timing out is transient; got $error"
        )
    }

    /**
     * The classification is deliberately everything-is-transport, and that is only sound
     * because of a guarantee made on the other side of the boundary: the callback Nimbus
     * hands an implementation never raises a failure of its own, so nothing reaching this
     * `catch` can be the caller's disk. `TransferFailureClassificationTest` in the core
     * module is where that guarantee is held to.
     */
    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun KResult<Long, GetFileSizeError>.temporarySizeCause():
            TemporaryGetFileSizeErrorCause {
        val error = (this as? Failure)?.error ?: fail("expected a failure, got $this")
        val temporary = error as? GetFileSizeError.TemporaryError
            ?: fail("expected a temporary error, got $error")
        return temporary.errorCause
    }

    private fun KResult<Unit, DownloadError>.temporaryDownloadCause():
            TemporaryDownloadErrorCause {
        val error = (this as? Failure)?.error ?: fail("expected a failure, got $this")
        val temporary = error as? DownloadError.TemporaryError
            ?: fail("expected a temporary error, got $error")
        return temporary.errorCause
    }
}
