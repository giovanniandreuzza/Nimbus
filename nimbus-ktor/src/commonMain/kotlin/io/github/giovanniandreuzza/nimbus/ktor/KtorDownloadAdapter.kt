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
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.asSource
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
            if (isTransportTimeout(e)) {
                Failure(
                    GetFileSizeError.TemporaryError(
                        TemporaryGetFileSizeErrorCause.NetworkTimeout(unexpectedError(e))
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
            if (isTransportTimeout(e)) {
                Failure(
                    GetFileSizeError.TemporaryError(
                        TemporaryGetFileSizeErrorCause.NetworkTimeout(unexpectedError(e))
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
            if (isTransportTimeout(e)) {
                Failure(
                    DownloadError.TemporaryError(
                        TemporaryDownloadErrorCause.NetworkTimeout(unexpectedError(e))
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
                    if (rangeStart != null && rangeStart != offset) {
                        return Failure(
                            DownloadError.PermanentError(
                                PermanentDownloadErrorCause.InconsistentRangeResponse(
                                    "Content-Range start $rangeStart does not match requested offset $offset."
                                )
                            )
                        )
                    }
                }
                val source = response.bodyAsChannel().asSource().buffered()
                onSourceOpened(source)
                Success(Unit)
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
                val source = response.bodyAsChannel().asSource().buffered()
                onSourceOpened(source)
                Success(Unit)
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
     * Whether [error] is the transport giving up on time, as opposed to anything else.
     *
     * Deliberately narrow. `downloadFile` hands the caller a [Source] and invokes its
     * callback inside this same `try`, so a failure raised in there — a sink that could not
     * be written, a full disk — arrives here indistinguishable from a failure of the socket:
     * on every platform both are `kotlinx.io.IOException`. Treating `IOException` as
     * transient would therefore retry a full disk as though it were a congested link.
     *
     * These three types do not have that ambiguity. Ktor raises them for connecting, for
     * waiting on bytes, and for the request as a whole; none of them can originate in the
     * caller's callback, so classifying them as transient is correct wherever they surface.
     *
     * What this does not catch is a connection reset or a DNS failure, which are equally
     * transient and equally common on an unattended device. Separating those from a caller
     * -side failure needs the port to stop wrapping the callback in the adapter's own `try`,
     * which is a change to the [NimbusDownloadPort] contract rather than to this mapping.
     */
    private fun isTransportTimeout(error: Throwable): Boolean =
        error is SocketTimeoutException ||
                error is ConnectTimeoutException ||
                error is HttpRequestTimeoutException

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
