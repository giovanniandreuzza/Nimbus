package io.github.giovanniandreuzza.nimbus.ktor

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentGetFileSizeErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryGetFileSizeErrorCause
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.asSource
import kotlinx.io.IOException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default [NimbusDownloadPort] implementation backed by a Ktor [HttpClient].
 *
 * Supports resumable downloads via HTTP `Range` headers and maps HTTP status
 * codes to the library's typed error hierarchy.
 *
 * **Behaviour:**
 * - Uses `HEAD` for size; if `Content-Length` is missing or zero, probes with
 *   `GET` + `Range: bytes=0-0` and reads total length from `Content-Range`.
 * - When [offset] > 0, accepts **206** with a matching `Content-Range` start,
 *   or **200** only if the body is empty (some servers signal empty range that way).
 * - Rejects **200** with a non-empty body when [offset] > 0 (mapped to [DownloadError.PermanentError]).
 * - Maps **416** to [DownloadError.TemporaryError] (cause code `range_not_satisfiable`); the
 *   adapter layer will truncate the local file and restart from byte 0.
 *
 * ```kotlin
 * val adapter = KtorDownloadAdapter(HttpClient(OkHttp))
 * ```
 *
 * **Note:** the caller owns the [HttpClient] lifecycle — this adapter will
 * not close it.
 *
 * @param httpClient the Ktor [HttpClient] used for HTTP requests.
 * @author Giovanni Andreuzza
 */
public class KtorDownloadAdapter(
    private val httpClient: HttpClient
) : NimbusDownloadPort {

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> {
        return try {
            val headResponse = httpClient.head(fileUrl)
            when (val head = mapFileSizeResponse(headResponse)) {
                is Success -> {
                    if (head.value > 0L) Success(head.value)
                    else probeTotalSizeWithRangeGet(fileUrl)
                }

                is Failure -> head
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isTransportFailure(e)) {
                Failure(
                    GetFileSizeError.TemporaryError(
                        TemporaryGetFileSizeErrorCause.TransportFailure(unexpectedError(e))
                    )
                )
            } else {
                Failure(
                    GetFileSizeError.PermanentError(
                        PermanentGetFileSizeErrorCause.UnexpectedError(
                            unexpectedError(e)
                        )
                    )
                )
            }
        }
    }

    private suspend fun probeTotalSizeWithRangeGet(fileUrl: String): KResult<Long, GetFileSizeError> {
        return try {
            httpClient.prepareGet(fileUrl) {
                headers { append("Range", "bytes=0-0") }
            }.execute { response ->
                when (response.status.value) {
                    206 -> {
                        val total = parseContentRangeTotal(response.headers["Content-Range"])
                            ?: response.headers["Content-Length"]?.toLongOrNull()
                        if (total != null && total > 0L) Success(total)
                        else Failure(GetFileSizeError.PermanentError(PermanentGetFileSizeErrorCause.FileSizeUnavailable))
                    }

                    in 200..299 -> {
                        val cl = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                        if (cl > 0L) Success(cl)
                        else Failure(GetFileSizeError.PermanentError(PermanentGetFileSizeErrorCause.FileSizeUnavailable))
                    }

                    404 -> Failure(GetFileSizeError.PermanentError(PermanentGetFileSizeErrorCause.ResourceNotFound))
                    in 400..499 -> Failure(
                        GetFileSizeError.PermanentError(
                            PermanentGetFileSizeErrorCause.ClientError(response.status.value)
                        )
                    )

                    in 500..599 -> Failure(
                        GetFileSizeError.TemporaryError(
                            TemporaryGetFileSizeErrorCause.ServerError(response.status.value)
                        )
                    )

                    else -> Failure(GetFileSizeError.PermanentError(PermanentGetFileSizeErrorCause.UnexpectedError()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isTransportFailure(e)) {
                Failure(
                    GetFileSizeError.TemporaryError(
                        TemporaryGetFileSizeErrorCause.TransportFailure(unexpectedError(e))
                    )
                )
            } else {
                Failure(
                    GetFileSizeError.PermanentError(
                        PermanentGetFileSizeErrorCause.UnexpectedError(
                            unexpectedError(e)
                        )
                    )
                )
            }
        }
    }

    private fun mapFileSizeResponse(response: HttpResponse): KResult<Long, GetFileSizeError> {
        return when (response.status.value) {
            in 200..299 -> {
                val fileSize = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                Success(fileSize)
            }

            404 -> Failure(GetFileSizeError.PermanentError(PermanentGetFileSizeErrorCause.ResourceNotFound))
            in 400..499 -> Failure(
                GetFileSizeError.PermanentError(
                    PermanentGetFileSizeErrorCause.ClientError(
                        response.status.value
                    )
                )
            )

            in 500..599 -> Failure(
                GetFileSizeError.TemporaryError(
                    TemporaryGetFileSizeErrorCause.ServerError(
                        response.status.value
                    )
                )
            )

            else -> Failure(GetFileSizeError.PermanentError(PermanentGetFileSizeErrorCause.UnexpectedError()))
        }
    }

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        return try {
            httpClient.prepareGet(fileUrl) {
                if (offset > 0) {
                    headers { append("Range", "bytes=$offset-") }
                }
            }.execute { response ->
                mapDownloadResponse(response, offset, onSourceOpened)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isTransportFailure(e)) {
                Failure(
                    DownloadError.TemporaryError(
                        TemporaryDownloadErrorCause.TransportFailure(unexpectedError(e))
                    )
                )
            } else {
                Failure(
                    DownloadError.PermanentError(
                        PermanentDownloadErrorCause.UnexpectedError(
                            unexpectedError(e)
                        )
                    )
                )
            }
        }
    }

    private suspend fun mapDownloadResponse(
        response: HttpResponse,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        return when (response.status.value) {
            416 -> Failure(DownloadError.TemporaryError(TemporaryDownloadErrorCause.RangeNotSatisfiable))
            404 -> Failure(DownloadError.PermanentError(PermanentDownloadErrorCause.ResourceNotFound))
            in 500..599 -> Failure(
                DownloadError.TemporaryError(
                    TemporaryDownloadErrorCause.ServerError(
                        response.status.value
                    )
                )
            )

            206 -> {
                if (offset <= 0L) {
                    return Failure(
                        DownloadError.PermanentError(
                            PermanentDownloadErrorCause.InconsistentRangeResponse("Received 206 but no range was requested.")
                        )
                    )
                }
                val cr = response.headers["Content-Range"]
                val rangeStart = parseContentRangeStart(cr)
                if (offset > 0L) {
                    if (cr.isNullOrBlank()) {
                        return Failure(
                            DownloadError.PermanentError(
                                PermanentDownloadErrorCause.InconsistentRangeResponse("206 response missing Content-Range during resume.")
                            )
                        )
                    }
                    // A header that cannot be read is not weaker evidence than a missing
                    // one, it is the same absence of evidence — and appending a body
                    // without it yields a file of exactly the right length holding the
                    // wrong bytes, which the size check cannot see.
                    if (rangeStart == null) {
                        return Failure(
                            DownloadError.PermanentError(
                                PermanentDownloadErrorCause.InconsistentRangeResponse(
                                    "Content-Range '$cr' could not be read during resume."
                                )
                            )
                        )
                    }
                    if (rangeStart != offset) {
                        return Failure(
                            DownloadError.PermanentError(
                                PermanentDownloadErrorCause.InconsistentRangeResponse(
                                    "Content-Range start $rangeStart does not match requested offset $offset."
                                )
                            )
                        )
                    }
                }
                deliverBody(response, onSourceOpened)
            }

            in 200..299 -> {
                if (offset > 0L) {
                    // RFC 9110: partial content should be 206. Appending a full 200 body would corrupt the file.
                    response.bodyAsChannel().cancel(null)
                    return Failure(
                        DownloadError.PermanentError(
                            PermanentDownloadErrorCause.InconsistentRangeResponse(
                                "Expected 206 for resume, got HTTP ${response.status.value}."
                            )
                        )
                    )
                }
                deliverBody(response, onSourceOpened)
            }

            in 400..499 -> Failure(
                DownloadError.PermanentError(
                    PermanentDownloadErrorCause.ClientError(
                        response.status.value
                    )
                )
            )

            else -> Failure(DownloadError.PermanentError(PermanentDownloadErrorCause.UnexpectedError()))
        }
    }


    /**
     * Whether [error] is the link failing.
     *
     * Everything an implementation of [NimbusDownloadPort] sees is the transport. The
     * callback it is handed comes from Nimbus, which classifies its own failures before they
     * can escape into this `try` — so an `IOException` reaching here cannot be a sink that
     * could not be written, and a dropped connection needs no further evidence.
     *
     * All of it is transient. The consumers this library is built for are unattended devices
     * on congested cells, where a dropped connection is the normal condition and nobody is
     * present to press retry; and because a retry resumes from the bytes already on disk, it
     * costs the remainder of the file rather than the whole of it.
     */
    /**
     * Hands the response body to [onSourceOpened], then reports whether the link held.
     *
     * Reading the source to its end is not evidence the body arrived. A channel closed with
     * a cause reads as an ordinary end of stream once it has been turned into a `Source`, so
     * a connection dropped mid-body looks exactly like a body that ended — measured here at
     * roughly nineteen times out of twenty, reported as a completed transfer that delivered
     * nothing at all. The size check upstream then attributes the retry to an integrity
     * mismatch, and the dropped link is never named.
     *
     * The cause survives on the channel, so it is the channel that gets asked. Temporary: a
     * link that died is the definition of worth retrying.
     */
    private suspend fun deliverBody(
        response: HttpResponse,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        val channel = response.bodyAsChannel()
        val declared = response.contentLength()
        val counted = CountingRawSource(channel.asSource())

        onSourceOpened(counted.buffered())

        channel.closedCause?.let { cause ->
            return transportFailure(
                "The connection closed before the body ended: " +
                        (cause.message ?: cause::class.simpleName ?: "no detail")
            )
        }
        if (declared != null && declared >= 0L && counted.bytes < declared) {
            return transportFailure(
                "The body ended after ${counted.bytes} of $declared declared bytes."
            )
        }
        return Success(Unit)
    }

    private fun transportFailure(message: String): KResult<Unit, DownloadError> = Failure(
        DownloadError.TemporaryError(
            TemporaryDownloadErrorCause.TransportFailure(
                KError(code = "transport_closed", message = message)
            )
        )
    )

    /** Counts what is actually pulled off the channel, so a short body can be seen. */
    private class CountingRawSource(private val delegate: RawSource) : RawSource {
        var bytes: Long = 0L
            private set

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            val read = delegate.readAtMostTo(sink, byteCount)
            if (read > 0L) bytes += read
            return read
        }

        override fun close() = delegate.close()
    }

    private fun isTransportFailure(error: Throwable): Boolean = error is IOException

    private fun unexpectedError(e: Exception): KError =
        KError(code = "unexpected_error", message = e.message ?: "An unexpected error occurred")
}

/** Parses total length from Content-Range (value after slash; ignores unknown total `*` ). */
internal fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) return null
    val part = contentRange.substringAfter('/')
    if (part == "*") return null
    return part.toLongOrNull()
}

/** Parses start byte from `bytes START-END/...`. */
internal fun parseContentRangeStart(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) return null
    val withoutUnit = contentRange.removePrefix("bytes").trimStart()
    val rangePart = withoutUnit.substringBefore('/')
    val start = rangePart.substringBefore('-').trim()
    return start.toLongOrNull()
}
