package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.explicitarchitecture.core.application.dtos.IsDto
import io.github.giovanniandreuzza.explicitarchitecture.core.application.mappers.IsApplicationMapper
import io.github.giovanniandreuzza.nimbus.core.domain.entities.DownloadTask
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import io.github.giovanniandreuzza.nimbus.presentation.Checksum

/**
 * Download Task DTO.
 *
 * @param id The download id.
 * @param fileName The file name.
 * @param fileUrl The file url.
 * @param filePath The file path.
 * @param fileSize The file size.
 * @param state The download state.
 * @param expectedChecksum What the caller asked the finished file to hash to, if anything.
 * @param checksum What the file actually hashed to. Non-null only once the download has
 *   finished and a digest algorithm was configured on the builder.
 * @param createdAtEpochMs When the task was created. Zero for a task stored before 2.5.0 and
 *   not yet seen by a build that stamps it.
 * @param finishedAtEpochMs When the file was last reported complete, or null if it has not
 *   been. What `pruneFinished` measures an age against.
 * @param resumeValidator What the origin said identified the file — an HTTP `ETag` or
 *   `Last-Modified`. Handed back on a resume so the transport can refuse to append the tail of
 *   a file that is no longer the one the prefix came from.
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
    val expectedChecksum: Checksum? = null,
    val checksum: Checksum? = null,
    val createdAtEpochMs: Long = 0L,
    val finishedAtEpochMs: Long? = null,
    val resumeValidator: String? = null
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
                expectedChecksum = downloadTask.expectedChecksum,
                checksum = downloadTask.checksum,
                createdAtEpochMs = downloadTask.createdAtEpochMs,
                finishedAtEpochMs = downloadTask.finishedAtEpochMs,
                resumeValidator = downloadTask.resumeValidator
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