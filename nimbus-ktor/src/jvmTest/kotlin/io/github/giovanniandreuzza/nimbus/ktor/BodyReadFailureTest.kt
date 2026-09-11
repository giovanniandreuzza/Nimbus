package io.github.giovanniandreuzza.nimbus.ktor

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A connection that dies part-way through the body, which is what a long transfer on a mobile
 * link actually does.
 *
 * This lives in `jvmTest` rather than beside the rest because `MockEngine` will not reproduce
 * it: a channel closed with a cause reads as end-of-stream, and a declared `Content-Length`
 * longer than the body is not enforced, so on the common source set the response always ends
 * cleanly. A `java.io.InputStream` that throws is the shortest thing that genuinely fails a
 * read.
 *
 * The branch matters more than its size suggests. The adapter labels the caller's own failures
 * so a full disk is never retried as a network problem, and a read that failed has to be
 * labelled the other way for that to work: without it every mid-transfer drop would be
 * reported as the caller's fault and left permanent, which is worse than the behaviour this
 * whole change set replaces.
 */
class BodyReadFailureTest {

    private val url = "https://example.test/file.bin"

    private class FailsPartWayThrough(private val prefix: Int) : InputStream() {
        private var served = 0
        override fun read(): Int {
            if (served++ < prefix) return 0
            throw IOException("Connection reset by peer")
        }
    }

    @Test
    fun `a body that dies part-way through is the transport, not the caller`() = runTest {
        val engine = MockEngine {
            respond(
                content = withContext(Dispatchers.IO) {
                    FailsPartWayThrough(prefix = 64).toByteReadChannel()
                },
                status = HttpStatusCode.OK
            )
        }

        var bytesSeen = 0
        val result = KtorDownloadAdapter(HttpClient(engine))
            .downloadFile(url, offset = 0L) { source ->
                bytesSeen = source.readByteArray().size
            }

        val error = (result as? Failure)?.error ?: fail("expected a failure, got $result")
        val temporary = error as? DownloadError.TemporaryError
            ?: fail("a dropped connection must be retried, got $error")

        assertEquals("transport_failure", temporary.errorCause.code)
        assertTrue(
            bytesSeen < 64 || bytesSeen == 0,
            "the read should have failed rather than completing, saw $bytesSeen bytes"
        )
    }
}
