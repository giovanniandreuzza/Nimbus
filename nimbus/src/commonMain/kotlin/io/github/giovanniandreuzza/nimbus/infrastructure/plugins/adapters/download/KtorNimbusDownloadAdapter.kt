package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.download

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.frameworks.ktor.KtorClient
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.asSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ktor Nimbus Download Adapter.
 *
 * @author Giovanni Andreuzza
 */
internal class KtorNimbusDownloadAdapter(private val ktorClient: KtorClient) : NimbusDownloadPort {

    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> {
        return withContext(Dispatchers.IO) {
            try {
                val response = ktorClient.client.head(fileUrl)
                when (response.status.value) {
                    in 200..299 -> {
                        val fileSize = response.headers["Content-Length"]?.toLong() ?: 0
                        Success(fileSize)
                    }

                    HttpStatusCode.NotFound.value -> {
                        Failure(GetFileSizeError.ResourceNotFound)
                    }

                    in 400..499 -> {
                        val error = KError(
                            code = "client_error",
                            message = "Client error occurred with status code ${response.status.value}"
                        )
                        Failure(GetFileSizeError.PermanentError(error))
                    }

                    in 500..599 -> {
                        val error = KError(
                            code = "server_error",
                            message = "Server error occurred with status code ${response.status.value}"
                        )
                        Failure(GetFileSizeError.TemporaryError(error))
                    }

                    else -> {
                        Failure(GetFileSizeError.UnexpectedError())
                    }
                }
            } catch (e: Exception) {
                val error = KError(
                    code = "unexpected_error",
                    message = "An unexpected error occurred: ${e.message}"
                )
                Failure(GetFileSizeError.UnexpectedError(error))
            }
        }
    }

    @OptIn(InternalAPI::class)
    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError> {
        return try {
            ktorClient.client.prepareGet(fileUrl) {
                headers {
                    if (offset > 0) {
                        append("Range", "bytes=$offset-")
                    }
                }
            }.execute { response ->
                when (response.status.value) {
                    in 200..299 -> {
                        val source = response.bodyAsChannel().asSource().buffered()
                        onSourceOpened(source)
                        Success(Unit)
                    }

                    HttpStatusCode.NotFound.value -> {
                        Failure(DownloadError.ResourceNotFound)
                    }

                    in 400..499 -> {
                        val error = KError(
                            code = "client_error",
                            message = "Client error occurred with status code ${response.status.value}"
                        )
                        Failure(DownloadError.PermanentError(error))
                    }

                    in 500..599 -> {
                        val error = KError(
                            code = "server_error",
                            message = "Server error occurred with status code ${response.status.value}"
                        )
                        Failure(DownloadError.TemporaryError(error))
                    }

                    else -> {
                        Failure(DownloadError.UnexpectedError())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = KError(
                code = "unexpected_error",
                message = "An unexpected error occurred: ${e.message}"
            )
            Failure(DownloadError.UnexpectedError(error))
        }
    }
}