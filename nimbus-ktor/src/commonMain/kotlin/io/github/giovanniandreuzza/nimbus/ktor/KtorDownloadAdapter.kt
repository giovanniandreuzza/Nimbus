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
import io.ktor.utils.io.asSource
import kotlinx.io.Buffer
import kotlinx.io.IOException
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
                        TemporaryGetFileSizeErrorCause.TransportFailure(unexpectedError(unwrapped(e)))
                    )
                )
            } else {
                Failure(
                    GetFileSizeError.PermanentError(
                        PermanentGetFileSizeErrorCause.UnexpectedError(
                            unexpectedError(unwrapped(e))
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
                        TemporaryGetFileSizeErrorCause.TransportFailure(unexpectedError(unwrapped(e)))
                    )
                )
            } else {
                Failure(
                    GetFileSizeError.PermanentError(
                        PermanentGetFileSizeErrorCause.UnexpectedError(
                            unexpectedError(unwrapped(e))
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
                        TemporaryDownloadErrorCause.TransportFailure(unexpectedError(unwrapped(e)))
                    )
                )
            } else {
                Failure(
                    DownloadError.PermanentError(
                        PermanentDownloadErrorCause.UnexpectedError(
                            unexpectedError(unwrapped(e))
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
                deliver(response, onSourceOpened)
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
                deliver(response, onSourceOpened)
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
     * Opens the response body and hands it to [onSourceOpened], keeping the two kinds of
     * failure that can come out of that apart.
     *
     * The callback both reads from the network and writes to wherever the caller is putting
     * the bytes, and on every platform a dead socket and a full disk are the same type —
     * `kotlinx.io.IOException`. Without a way to tell them apart, retrying one means
     * retrying the other, which turns a disk that will never have room into a transfer that
     * is attempted forever.
     *
     * So each side is labelled at the point it happens: reads through a [RawSource] wrapper
     * that tags what it throws, everything else in the callback wrapped on the way out. What
     * reaches the caller of this function unlabelled therefore came from neither, which
     * leaves Ktor itself — connecting, resolving, negotiating TLS — and can be classified on
     * its type alone.
     */
    private suspend fun deliver(
        response: HttpResponse,
        onSourceOpened: suspend (Source) -> Unit
    ) {
        val body = response.bodyAsChannel().asSource().taggingReadFailures()
        try {
            onSourceOpened(body.buffered())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportReadFailure) {
            throw e
        } catch (e: Throwable) {
            throw ConsumerFailure(e)
        }
    }

    /** A read from the response body failed: the transport, wherever it surfaces. */
    private class TransportReadFailure(override val cause: Throwable) : Exception(cause)

    /** The caller's own work inside the callback failed: their sink, their digest, not ours. */
    private class ConsumerFailure(override val cause: Throwable) : Exception(cause)

    private fun RawSource.taggingReadFailures(): RawSource {
        val delegate = this
        return object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
                try {
                    delegate.readAtMostTo(sink, byteCount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw TransportReadFailure(e)
                }

            override fun close() = delegate.close()
        }
    }

    /**
     * Whether [error] is the link failing rather than the caller's own work.
     *
     * Total, because [deliver] labels the two ambiguous sources before they reach here. A
     * tagged read failure is the transport by construction. A tagged consumer failure is not,
     * whatever its type. What is left was raised by Ktor outside the callback — connecting,
     * resolving, negotiating TLS, a peer that reset while the headers were being read — and
     * an `IOException` there can only be the network.
     *
     * All of it is transient. Nimbus's demanding consumers are unattended devices on
     * congested cells and flaky access points, where a dropped connection is the normal
     * condition and there is nobody to press retry. And because the adapter resumes from the
     * bytes already on disk, retrying costs the remainder of the file rather than the whole
     * of it — which is the difference between a drop at 90% costing 10% and costing 100%.
     */
    private fun isTransportFailure(error: Throwable): Boolean = when (error) {
        is ConsumerFailure -> false
        is TransportReadFailure -> true
        is IOException -> true
        else -> false
    }

    /** Unwraps the labels [deliver] adds, so the reported cause is the failure itself. */
    private fun unwrapped(error: Exception): Exception = when (error) {
        is TransportReadFailure -> error.cause as? Exception ?: error
        is ConsumerFailure -> error.cause as? Exception ?: error
        else -> error
    }

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
