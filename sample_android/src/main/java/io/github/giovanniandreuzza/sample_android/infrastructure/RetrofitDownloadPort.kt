package io.github.giovanniandreuzza.sample_android.infrastructure

/**
 * Remote Download Repository Adapter.
 *
 * @param appEndpoint App Endpoint.
 * @author Giovanni Andreuzza
 */
//class RetrofitDownloadPort(private val appEndpoint: AppEndpoint) : NimbusDownloadPort {
//
//    override suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError> {
//        try {
//            val fileSize = appEndpoint.getFileSize(fileUrl).headers()["Content-Length"]?.toLong()
//            return Success(fileSize ?: 0)
//        } catch (e: Exception) {
//            val error = KError(
//                code = "unexpected_error",
//                message = e.message ?: "Unexpected error occurred"
//            )
//            return Failure(GetFileSizeError.UnexpectedError(error))
//        }
//    }
//
//    override suspend fun downloadFile(
//        fileUrl: String,
//        offset: Long?
//    ): KResult<Source, DownloadError> {
//        try {
//            val range = "bytes=${offset ?: 0}-"
//            val fileStream = appEndpoint.downloadApp(fileUrl, range)
//
//            return fileStream.body()?.let {
//                val source = it.byteStream().asSource().buffered()
//                Success(source)
//            } ?: let {
//                val error = KError(
//                    code = "unexpected_error",
//                    message = "Download body missing. ${fileStream.message()} - ${fileStream.code()}"
//                )
//                Failure(DownloadError.UnexpectedError(error))
//            }
//        } catch (e: Exception) {
//            val error = KError(
//                code = "unexpected_error",
//                message = e.message ?: "Unexpected error occurred"
//            )
//            return Failure(DownloadError.UnexpectedError(error))
//        }
//    }
//}