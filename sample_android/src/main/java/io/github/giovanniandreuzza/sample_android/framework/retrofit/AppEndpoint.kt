package io.github.giovanniandreuzza.sample_android.framework.retrofit

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Download Endpoint.
 *
 * @author Giovanni Andreuzza
 */
interface AppEndpoint {

    @HEAD
    suspend fun getFileSize(@Url appUrl: String): Response<Unit>

    @Streaming
    @GET
    suspend fun downloadApp(@Url appUrl: String, @Header("Range") range: String): Response<ResponseBody>

}