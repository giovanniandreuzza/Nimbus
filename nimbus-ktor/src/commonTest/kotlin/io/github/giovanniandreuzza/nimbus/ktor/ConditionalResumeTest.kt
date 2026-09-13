package io.github.giovanniandreuzza.nimbus.ktor

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Resuming a file that may not be the same file any more.
 *
 * `Range` alone asks for bytes from an offset and takes whatever comes back. If the origin has
 * replaced the file in the meantime, the tail of the new one is appended to the prefix of the
 * old one — and when the two are the same length, which a re-encode at the same bitrate or a
 * regenerated manifest produces routinely, the result passes every check but a digest.
 *
 * `If-Range` is the header that exists for this: honour the range only if the file still looks
 * like this, otherwise send the whole thing. The whole thing arriving is the answer this
 * adapter must recognise and refuse to append.
 */
class ConditionalResumeTest {

    private val url = "https://example.test/file.bin"

    @Test
    fun `the ETag is what identifies the file`() = runTest {
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine {
                    respond(
                        content = ByteArray(0),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            "Content-Length" to listOf("2048"),
                            "ETag" to listOf("\"abc123\""),
                            "Last-Modified" to listOf("Wed, 10 Sep 2026 10:00:00 GMT")
                        )
                    )
                }
            )
        )

        val remote = adapter.getRemoteFile(url).valueOrFail()

        assertEquals(2048L, remote.sizeBytes)
        assertEquals(
            "\"abc123\"",
            remote.validator,
            "the ETag is the origin's own answer to 'is this still the same file'"
        )
    }

    @Test
    fun `Last-Modified stands in when there is no ETag`() = runTest {
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine {
                    respond(
                        content = ByteArray(0),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            "Content-Length" to listOf("2048"),
                            "Last-Modified" to listOf("Wed, 10 Sep 2026 10:00:00 GMT")
                        )
                    )
                }
            )
        )

        val remote = adapter.getRemoteFile(url).valueOrFail()

        assertEquals(
            "Wed, 10 Sep 2026 10:00:00 GMT",
            remote.validator,
            "weaker — one second of resolution — and still better than assuming nothing changes"
        )
    }

    @Test
    fun `an origin with neither reports no validator rather than inventing one`() = runTest {
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine {
                    respond(
                        content = ByteArray(0),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Length", listOf("2048"))
                    )
                }
            )
        )

        assertNull(adapter.getRemoteFile(url).valueOrFail().validator)
    }

    @Test
    fun `a resume carrying a validator asks the origin to check it`() = runTest {
        var seen: HttpRequestData? = null
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine { request ->
                    seen = request
                    respond(
                        content = "tail".encodeToByteArray(),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf("Content-Range", listOf("bytes 4-7/8"))
                    )
                }
            )
        )

        adapter.downloadFile(url, offset = 4L, resumeValidator = "\"abc123\"") { source ->
            source.readByteArray()
        }

        assertEquals("bytes=4-", seen?.headers?.get("Range"))
        assertEquals("\"abc123\"", seen?.headers?.get("If-Range"))
    }

    @Test
    fun `no If-Range is sent when there is nothing to check`() = runTest {
        var seen: HttpRequestData? = null
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine { request ->
                    seen = request
                    respond(
                        content = "tail".encodeToByteArray(),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf("Content-Range", listOf("bytes 4-7/8"))
                    )
                }
            )
        )

        adapter.downloadFile(url, offset = 4L, resumeValidator = null) { source ->
            source.readByteArray()
        }

        assertNull(
            seen?.headers?.get("If-Range"),
            "an empty If-Range is not a weaker check, it is a header a server may reject"
        )
    }

    @Test
    fun `a whole file in answer to a conditional resume means the file changed`() = runTest {
        // RFC 9110: this is exactly what a server says when the validator no longer matches.
        // Appending this body would corrupt the file, and it is not the server misbehaving —
        // the recovery is to throw the partial away and take the file it is now offering.
        val adapter = KtorDownloadAdapter(
            HttpClient(
                MockEngine {
                    respond(
                        content = ByteArray(2048),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Length", listOf("2048"))
                    )
                }
            )
        )

        val result = adapter.downloadFile(url, offset = 512L, resumeValidator = "\"old\"") { }

        val cause = (result as? Failure)?.error as? DownloadError.TemporaryError
            ?: fail("a changed file is worth another attempt, got $result")
        assertTrue(
            cause.errorCause is TemporaryDownloadErrorCause.RemoteFileChanged,
            "got ${cause.errorCause}"
        )
    }

    @Test
    fun `the same answer without a validator stays a server that cannot be resumed from`() =
        runTest {
            // Nothing was asked, so nothing was answered: a 200 to a plain `Range` request is
            // a server ignoring the range, and there is no safe way to resume from it.
            val adapter = KtorDownloadAdapter(
                HttpClient(
                    MockEngine {
                        respond(
                            content = ByteArray(2048),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Length", listOf("2048"))
                        )
                    }
                )
            )

            val result = adapter.downloadFile(url, offset = 512L, resumeValidator = null) { }

            val error = (result as? Failure)?.error as? DownloadError.PermanentError
                ?: fail("got $result")
            assertTrue(
                error.errorCause is PermanentDownloadErrorCause.InconsistentRangeResponse,
                "got ${error.errorCause}"
            )
        }

    private fun <T> io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<T, *>.valueOrFail(): T =
        when (this) {
            is Success -> value
            else -> fail("expected success, got $this")
        }
}
