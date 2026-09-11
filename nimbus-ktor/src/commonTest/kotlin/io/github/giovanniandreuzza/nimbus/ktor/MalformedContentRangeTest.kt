package io.github.giovanniandreuzza.nimbus.ktor

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A 206 whose `Content-Range` cannot be read.
 *
 * Resuming means asking for bytes from an offset and appending what comes back to what is
 * already on disk. The only evidence that the body starts where it was asked to is the
 * `Content-Range` header, so a header that cannot be parsed is no evidence at all — and a
 * body appended without it produces a file of exactly the right length holding the wrong
 * bytes, which the size check cannot see.
 *
 * A missing header was already refused. A present but unreadable one was not: parsing
 * returned null, and the comparison that follows treats null as "nothing to disagree with".
 */
class MalformedContentRangeTest {

    @Test
    fun `a 206 with an unparsable Content-Range is refused during a resume`() = runTest {
        val adapter = adapterRespondingWith("bytes garbage/1000")

        val result = adapter.downloadFile(URL, offset = 100L) { }

        assertRefused(result)
    }

    @Test
    fun `a 206 whose Content-Range holds no number at all is refused during a resume`() =
        runTest {
            val adapter = adapterRespondingWith("pages 1-2/3")

            val result = adapter.downloadFile(URL, offset = 100L) { }

            assertRefused(result)
        }

    private fun assertRefused(result: KResult<Unit, DownloadError>) {
        val error = (result as? Failure)?.error
        assertTrue(
            (error as? DownloadError.PermanentError)
                ?.errorCause is PermanentDownloadErrorCause.InconsistentRangeResponse,
            "expected InconsistentRangeResponse, got $result"
        )
    }

    private fun adapterRespondingWith(contentRange: String) = KtorDownloadAdapter(
        HttpClient(
            MockEngine {
                respond(
                    content = BODY,
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf("Content-Range", contentRange)
                )
            }
        )
    )

    private companion object {
        const val URL = "https://example.com/payload.bin"
        const val BODY = "0123456789"
    }
}
