package io.github.giovanniandreuzza.sample_android.infrastructure

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.nimbus.core.domain.errors.StartDownloadErrors
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.errors.GetFileSizeFailed
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.ports.NimbusDownloadRepository
import io.github.giovanniandreuzza.sample_android.framework.ktor.KtorClient
import io.ktor.client.call.body
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.http.headers
import io.ktor.utils.io.jvm.javaio.toInputStream
import okio.Source
import okio.source

class KtorDownloadRepository(val ktorClient: KtorClient) : NimbusDownloadRepository {
    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeFailed> {
        val fileSizeResult = ktorClient.getFileSize(fileUrl)

        if (fileSizeResult.isFailure()) {
            return Failure(GetFileSizeFailed("Failed to get file size"))
        }

        return Success(fileSizeResult.value)
    }

    override suspend fun downloadFile(
        fileUrl: String,
        offset: Long?
    ): KResult<Source, StartDownloadErrors.StartDownloadFailed> {
        val responseResult = ktorClient.getByteChannel(fileUrl, offset ?: 0)

        if (responseResult.isFailure()) {
            return Failure(StartDownloadErrors.StartDownloadFailed("Failed to download file"))
        }

        val response = responseResult.value

        val source = response.toInputStream().source()

        return Success(source)
    }
}