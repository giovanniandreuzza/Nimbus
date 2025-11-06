package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.mappers.IsApplicationMapper
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId

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
) {

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
         * Convert a map of [DownloadTaskDTO] from a map of [DownloadTask].
         *
         * @param downloadTaskMap The map of download tasks.
         * @return The map of DownloadTaskDTO.
         */
        @IsApplicationMapper
        fun fromDomains(downloadTaskMap: Map<DownloadId, DownloadTask>): Map<String, DownloadTaskDTO> {
            return downloadTaskMap.map {
                fromDomain(it.value)
            }.associateBy { it.id }
        }
    }
}