package io.github.giovanniandreuzza.nimbus.ktor

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.ktor.client.HttpClient
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
 * - Rejects **200** with a non-empty body when [offset] > 0 ([InconsistentRangeResponse]).
 * - Maps **416** to [DownloadError.RangeNotSatisfiable].
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
            Failure(GetFileSizeError.UnexpectedError(unexpectedError(e)))
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
                        else Failure(GetFileSizeError.UnexpectedError(KError("no_total_in_range", "No total in Content-Range")))
                    }
                    in 200..299 -> {
                        val cl = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                        if (cl > 0L) Success(cl)
                        else Failure(GetFileSizeError.UnexpectedError(KError("no_content_length", "Could not determine file size.")))
                    }
                    404 -> Failure(GetFileSizeError.ResourceNotFound)
                    in 400..499 -> Failure(GetFileSizeError.PermanentError(httpError("client_error", response.status.value)))
                    in 500..599 -> Failure(GetFileSizeError.TemporaryError(httpError("server_error", response.status.value)))
                    else -> Failure(GetFileSizeError.UnexpectedError())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failure(GetFileSizeError.UnexpectedError(unexpectedError(e)))
        }
    }

    private fun mapFileSizeResponse(response: HttpResponse): KResult<Long, GetFileSizeError> {
        return when (response.status.value) {
            in 200..299 -> {
                val fileSize = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                Success(fileSize)
            }
            404 -> Failure(GetFileSizeError.ResourceNotFound)
            in 400..499 -> Failure(GetFileSizeError.PermanentError(httpError("client_error", response.status.value)))
            in 500..599 -> Failure(GetFileSizeError.TemporaryError(httpError("server_error", response.status.value)))
            else -> Failure(GetFileSizeError.UnexpectedError())
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
            Failure(DownloadError.UnexpectedError(unexpectedError(e)))
        }
    }

    private suspend fun mapDownloadResponse(
        response: HttpResponse,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        return when (response.status.value) {
            416 -> Failure(DownloadError.RangeNotSatisfiable)
            404 -> Failure(DownloadError.ResourceNotFound)
            in 500..599 -> Failure(DownloadError.TemporaryError(httpError("server_error", response.status.value)))
            206 -> {
                if (offset <= 0L) {
                    return Failure(
                        DownloadError.InconsistentRangeResponse(
                            KError("unexpected_206", "Received 206 but no range was requested.")
                        )
                    )
                }
                val cr = response.headers["Content-Range"]
                val rangeStart = parseContentRangeStart(cr)
                if (offset > 0L) {
                    if (cr.isNullOrBlank()) {
                        return Failure(
                            DownloadError.InconsistentRangeResponse(
                                KError("missing_content_range", "206 response missing Content-Range during resume.")
                            )
                        )
                    }
                    if (rangeStart != null && rangeStart != offset) {
                        return Failure(
                            DownloadError.InconsistentRangeResponse(
                                KError(
                                    "range_start_mismatch",
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
                        DownloadError.InconsistentRangeResponse(
                            KError(
                                "full_200_on_resume",
                                "Expected 206 for resume, got HTTP ${response.status.value}."
                            )
                        )
                    )
                }
                val source = response.bodyAsChannel().asSource().buffered()
                onSourceOpened(source)
                Success(Unit)
            }
            in 400..499 -> Failure(DownloadError.PermanentError(httpError("client_error", response.status.value)))
            else -> Failure(DownloadError.UnexpectedError())
        }
    }

    private fun httpError(code: String, statusCode: Int): KError =
        KError(code = code, message = "HTTP error $statusCode")

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
