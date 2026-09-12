package io.github.giovanniandreuzza.nimbus.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every request this adapter makes carries a deadline for inactivity.
 *
 * It is the only thing that can end a wedged read. The body is read through
 * `ByteReadChannel.asSource()`, which waits for bytes inside `runBlocking`: not a suspension
 * point, so cancelling the download does not interrupt it, and neither Nimbus's stall guard
 * nor `pauseDownload` can do anything but wait. The socket has to be the one to give up.
 *
 * Which means the deadline must be on the *request*, not left to whatever the caller happened
 * to configure on the client — a client with no `HttpTimeout` plugin is the default, and it
 * would be a quiet single point of failure on every device.
 */
class SocketDeadlineTest {

    private val url = "https://example.test/file.bin"

    @Test
    fun `the size request carries the adapter's socket deadline`() = runTest {
        val seen = mutableListOf<Long?>()
        val adapter = KtorDownloadAdapter(HttpClient(recording(seen)))

        adapter.getFileSize(url)

        assertEquals(
            listOf<Long?>(KtorDownloadAdapter.DEFAULT_SOCKET_TIMEOUT_MS),
            seen,
            "a HEAD that hangs suspends whoever asked to enqueue, so it needs the deadline too"
        )
    }

    @Test
    fun `the transfer request carries the adapter's socket deadline`() = runTest {
        val seen = mutableListOf<Long?>()
        val adapter = KtorDownloadAdapter(HttpClient(recording(seen)))

        adapter.downloadFile(url, offset = 0L) { source -> source.readByteArray() }

        assertEquals(listOf<Long?>(KtorDownloadAdapter.DEFAULT_SOCKET_TIMEOUT_MS), seen)
    }

    @Test
    fun `a resume carries it as well`() = runTest {
        val seen = mutableListOf<Long?>()
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine { request ->
                    seen += request.socketDeadline()
                    respond(
                        content = "tail".encodeToByteArray(),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf("Content-Range", listOf("bytes 4-7/8"))
                    )
                }
            )
        )

        adapter.downloadFile(url, offset = 4L) { source -> source.readByteArray() }

        assertEquals(listOf<Long?>(KtorDownloadAdapter.DEFAULT_SOCKET_TIMEOUT_MS), seen)
    }

    @Test
    fun `null leaves the client's own configuration untouched`() = runTest {
        // For the caller who configures timeouts centrally and does not want a per-request
        // value quietly overriding them.
        val seen = mutableListOf<Long?>()
        val adapter = KtorDownloadAdapter(HttpClient(recording(seen)), socketTimeoutMillis = null)

        adapter.getFileSize(url)

        assertEquals(listOf<Long?>(null), seen, "nothing may be imposed on the request")
    }

    private fun recording(seen: MutableList<Long?>) = MockEngine { request ->
        seen += request.socketDeadline()
        respond(
            content = "12345678".encodeToByteArray(),
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Length", listOf("8"))
        )
    }

    /** What the engine would actually apply: the capability written on the request. */
    private fun HttpRequestData.socketDeadline(): Long? =
        getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis
}
