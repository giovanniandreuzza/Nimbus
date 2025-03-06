package io.github.giovanniandreuzza.sample_android.framework.ktor

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import timber.log.Timber


@IsFramework
class KtorClient {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15000 // 15 seconds
        }
    }

    suspend fun getFileSize(url: String): KResult<Long, KError> {
        try {
            val response = client.head(url)
            val fileSize = response.headers["Content-Length"]?.toLong() ?: 0
            return Success(fileSize)
        } catch (e: Exception) {
            Timber.e(e)
            return Failure(
                KError(
                    "get_file_size_failed",
                    e.message ?: "Failed to make request",
                    ""
                )
            )
        }
    }

    suspend fun getByteChannel(url: String, offset: Long): KResult<ByteReadChannel, KError> {
        try {
            val response = client.prepareGet(url) {
                headers {
                    if (offset > 0) {
                        append("Range", "bytes=$offset-")
                    }
                }
            }
            return Success(response.body())
        } catch (e: Exception) {
            return Failure(KError("download_failed", e.message ?: "Failed to make request", ""))
        }
    }

    fun close() {
        client.close()
    }

}