package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.Dto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.mappers.IsApplicationMapper
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState

/**
 * Download Task DTO.
 *
 * @param id The download id.
 * @param fileName The file name.
 * @param fileUrl The file url.
 * @param filePath The file path.
 * @param fileSize The file size.
 * @param state The download state.
 * @param version The version.
 * @author Giovanni Andreuzza
 */
@IsDto
public data class DownloadTaskDTO(
    val id: String,
    val fileName: String,
    val fileUrl: String,
    val filePath: String,
    val fileSize: Long,
    val state: DownloadState,
    val version: Int
) : Dto {

    internal companion object {
        /**
         * Convert a [DownloadTaskDTO] from a [DownloadTask].
         *
         * @param downloadTask The download task.
         * @return The DownloadTaskDTO.
         */
        @IsApplicationMapper
        fun fromDomain(downloadTask: DownloadTask): DownloadTaskDTO {
            return DownloadTaskDTO(
                id = downloadTask.entityId.id.value,
                fileName = downloadTask.fileName.value,
                fileUrl = downloadTask.fileUrl.value,
                filePath = downloadTask.filePath.value,
                fileSize = downloadTask.fileSize.value,
                state = downloadTask.state,
                version = downloadTask.version
            )
        }

        /**
         * Convert a [DownloadTaskDTO] to a [DownloadTask].
         *
         * @return The DownloadTask.
         */
        @IsApplicationMapper
        fun DownloadTaskDTO.toDomain(): DownloadTask {
            return DownloadTask.create(
                fileName = fileName,
                fileUrl = fileUrl,
                filePath = filePath,
                fileSize = fileSize,
                state = this.state,
                version = version
            )
        }
    }
}