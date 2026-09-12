package io.github.giovanniandreuzza.nimbus.ktor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The adapter against an HTTP server that misbehaves on purpose.
 *
 * Everything else about resume is tested against a fake port, which is the right level for
 * the adapter's own logic but proves nothing about the wire: whether a `Range` header is
 * actually sent, whether a 206 is recognised, what a real client raises when the body stops
 * arriving. This runs the real Ktor engine against a real socket so those answers come from
 * the stack rather than from a stand-in.
 *
 * JVM-only because the server is `com.sun.net.httpserver`, which ships with the JDK and needs
 * no dependency of its own.
 */
class HostileServerTest {

    private val server = HostileServer(CONTENT)
    private val client = HttpClient(OkHttp)
    private val adapter = KtorDownloadAdapter(client)

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
    }

    // -- the wire actually resumes -------------------------------------------

    @Test
    fun `a resume asks for the bytes it is missing and receives only those`() = runBlockingTest {
        server.behaviour = Behaviour.HonourRange

        val delivered = collect(offset = 3_000L).getOrFail()

        assertEquals(
            "bytes=3000-",
            server.lastRange,
            "the adapter has to ask, or the server has nothing to honour"
        )
        assertContentEquals(
            CONTENT.copyOfRange(3_000, CONTENT.size),
            delivered,
            "a resume that re-sends the prefix would double the cost of every drop"
        )
    }

    @Test
    fun `a fresh download sends no range and receives everything`() = runBlockingTest {
        server.behaviour = Behaviour.HonourRange

        val delivered = collect(offset = 0L).getOrFail()

        assertEquals(null, server.lastRange)
        assertContentEquals(CONTENT, delivered)
    }

    // -- servers that answer a resume badly ----------------------------------

    @Test
    fun `a server that answers a resume with the whole body is refused rather than appended`() =
        runBlockingTest {
            // No Range support: the reply is a 200 carrying the file from byte zero. Appending
            // that to what is already on disk would produce a longer file that no size check
            // can repair, so it has to be refused before a single byte is written.
            server.behaviour = Behaviour.IgnoreRange

            val error = collect(offset = 3_000L).errorOrFail()

            assertTrue(
                error is DownloadError.PermanentError &&
                        error.errorCause is PermanentDownloadErrorCause.InconsistentRangeResponse,
                "expected the resume to be refused, got $error"
            )
        }

    @Test
    fun `a server that answers 206 from the wrong place is refused`() = runBlockingTest {
        // The status says partial content and the Content-Range says it starts somewhere else.
        // Trusting the status alone would splice two different offsets into one file.
        server.behaviour = Behaviour.WrongRangeStart

        val error = collect(offset = 3_000L).errorOrFail()

        assertTrue(
            error is DownloadError.PermanentError &&
                    error.errorCause is PermanentDownloadErrorCause.InconsistentRangeResponse,
            "expected a mismatched Content-Range to be refused, got $error"
        )
    }

    // -- the link failing ------------------------------------------------------

    @Test
    fun `a body that stops arriving part-way is the transport`() = runBlockingTest {
        server.behaviour = Behaviour.DieMidBody

        val error = collect(offset = 0L).errorOrFail()

        assertTrue(
            error is DownloadError.TemporaryError &&
                    error.errorCause is TemporaryDownloadErrorCause.TransportFailure,
            "a connection that died has to be retried, got $error"
        )
    }

    @Test
    fun `a body that stops arriving part-way is the transport every time`() = runBlockingTest {
        server.behaviour = Behaviour.DieMidBody

        // Repeated because the single-shot version above only observes this by luck. A
        // dropped connection was reported as a completed transfer delivering zero bytes in
        // roughly nineteen attempts out of twenty, so one run tells you almost nothing —
        // which is how this reached a release pull request looking green.
        val outcomes = (1..10).map { collect(offset = 0L) }

        val described = outcomes.map { outcome ->
            when (outcome) {
                is Success -> "completed, delivering ${outcome.value.size} bytes"
                is Failure -> {
                    val error = outcome.error
                    if (error is DownloadError.TemporaryError &&
                        error.errorCause is TemporaryDownloadErrorCause.TransportFailure
                    ) {
                        null
                    } else {
                        "$error"
                    }
                }
            }
        }
        val wrong = described.filterNotNull()
        assertTrue(
            wrong.isEmpty(),
            "every dropped connection has to be reported as a retryable transport failure; " +
                    "${wrong.size} of ${outcomes.size} were not: $wrong"
        )
    }

    // -- statuses --------------------------------------------------------------

    @Test
    fun `a 416 is temporary so the local file can be truncated and tried again`() =
        runBlockingTest {
            server.behaviour = Behaviour.Status(416)

            val error = collect(offset = 3_000L).errorOrFail()

            assertTrue(
                error is DownloadError.TemporaryError &&
                        error.errorCause is TemporaryDownloadErrorCause.RangeNotSatisfiable,
                "expected 416 to be recoverable, got $error"
            )
        }

    @Test
    fun `a 404 is permanent`() = runBlockingTest {
        server.behaviour = Behaviour.Status(404)

        val error = collect(offset = 0L).errorOrFail()

        assertTrue(
            error is DownloadError.PermanentError &&
                    error.errorCause is PermanentDownloadErrorCause.ResourceNotFound,
            "expected 404 to be permanent, got $error"
        )
    }

    @Test
    fun `a 503 is temporary`() = runBlockingTest {
        server.behaviour = Behaviour.Status(503)

        val error = collect(offset = 0L).errorOrFail()

        assertTrue(
            error is DownloadError.TemporaryError &&
                    error.errorCause is TemporaryDownloadErrorCause.ServerError,
            "expected 5xx to be retried, got $error"
        )
    }

    @Test
    fun `a 403 is permanent`() = runBlockingTest {
        server.behaviour = Behaviour.Status(403)

        val error = collect(offset = 0L).errorOrFail()

        assertTrue(
            error is DownloadError.PermanentError &&
                    error.errorCause is PermanentDownloadErrorCause.ClientError,
            "expected 4xx to be permanent, got $error"
        )
    }

    // -- helpers ---------------------------------------------------------------

    /** Runs [downloadFile] and returns what actually crossed the wire. */
    private suspend fun collect(offset: Long): KResult<ByteArray, DownloadError> {
        var delivered = ByteArray(0)
        val result = adapter.downloadFile(server.url, offset) { source ->
            delivered = source.readByteArray()
        }
        return when (result) {
            is Success -> Success(delivered)
            is Failure -> Failure(result.error)
        }
    }

    private fun KResult<ByteArray, DownloadError>.getOrFail(): ByteArray = when (this) {
        is Success -> value
        is Failure -> fail("expected the transfer to succeed, got $error")
    }

    private fun KResult<ByteArray, DownloadError>.errorOrFail(): DownloadError = when (this) {
        is Success -> fail("expected a failure, the transfer delivered ${value.size} bytes")
        is Failure -> error
    }

    /** The server blocks, so it does not belong on the test dispatcher. */
    private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking {
        withContext(Dispatchers.IO) { block() }
    }

    private companion object {
        val CONTENT = ByteArray(9_000) { (it * 37 % 251).toByte() }
    }
}

private sealed interface Behaviour {
    data object HonourRange : Behaviour
    data object IgnoreRange : Behaviour
    data object WrongRangeStart : Behaviour
    data object DieMidBody : Behaviour
    data class Status(val code: Int) : Behaviour
}

private class HostileServer(private val content: ByteArray) : AutoCloseable {

    var behaviour: Behaviour = Behaviour.HonourRange
    var lastRange: String? = null
        private set

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/file.bin") { exchange -> handle(exchange) }
        executor = null
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/file.bin"

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        lastRange = exchange.requestHeaders.getFirst("Range")
        val from = lastRange?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0

        when (val what = behaviour) {
            is Behaviour.Status -> exchange.use {
                it.sendResponseHeaders(what.code, -1)
            }

            is Behaviour.IgnoreRange -> exchange.use {
                it.sendResponseHeaders(200, content.size.toLong())
                it.responseBody.write(content)
            }

            is Behaviour.WrongRangeStart -> exchange.use {
                val body = content.copyOfRange(from, content.size)
                it.responseHeaders.set(
                    "Content-Range",
                    "bytes ${from + 500}-${content.size - 1}/${content.size}"
                )
                it.sendResponseHeaders(206, body.size.toLong())
                it.responseBody.write(body)
            }

            is Behaviour.DieMidBody -> exchange.use {
                // Promise the whole file, send half, hang up. What a dropped connection
                // looks like to a client that had already started reading.
                it.sendResponseHeaders(200, content.size.toLong())
                it.responseBody.write(content, 0, content.size / 2)
                it.responseBody.flush()
            }

            is Behaviour.HonourRange -> exchange.use {
                val body = content.copyOfRange(from, content.size)
                if (lastRange != null) {
                    it.responseHeaders.set(
                        "Content-Range",
                        "bytes $from-${content.size - 1}/${content.size}"
                    )
                    it.sendResponseHeaders(206, body.size.toLong())
                } else {
                    it.sendResponseHeaders(200, body.size.toLong())
                }
                it.responseBody.write(body)
            }
        }
    }
}
