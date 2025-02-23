package io.github.giovanniandreuzza.nimbus.infrastructure

import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadInfoDTO
import io.github.giovanniandreuzza.nimbus.core.states.DownloadState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed class DownloadStateStore {

    /**
     * Idle state.
     */
    @Serializable
    @SerialName("Idle")
    object Idle : DownloadStateStore()

    /**
     * Downloading state.
     *
     * @param progress Download progress.
     */
    @Serializable
    @SerialName("Downloading")
    data class Downloading(val progress: Double) : DownloadStateStore()

    /**
     * Paused state.
     *
     * @param progress Download progress.
     */
    @Serializable
    @SerialName("Paused")
    data class Paused(val progress: Double) : DownloadStateStore()

    /**
     * Failed state.
     *
     * @param error The error.
     */
    @Serializable
    @SerialName("Failed")
    data class Failed(val errorCode: String, val errorMessage: String) : DownloadStateStore()

    /**
     * Downloaded state.
     */
    @Serializable
    @SerialName("Downloaded")
    object Downloaded : DownloadStateStore()
}

@Serializable
internal data class DownloadInfoStore(
    val id: String,
    val fileName: String,
    val fileUrl: String,
    val filePath: String,
    val fileSize: Long,
    val state: DownloadStateStore,
    val version: Int
)

internal fun DownloadStateStore.toState(): DownloadState {
    return when (this) {
        is DownloadStateStore.Idle -> DownloadState.Idle
        is DownloadStateStore.Downloading -> DownloadState.Downloading(progress)
        is DownloadStateStore.Paused -> DownloadState.Paused(progress)
        is DownloadStateStore.Failed -> DownloadState.Failed(errorCode, errorMessage)
        is DownloadStateStore.Downloaded -> DownloadState.Downloaded
    }
}

internal fun DownloadState.toStore(): DownloadStateStore {
    return when (this) {
        is DownloadState.Idle -> DownloadStateStore.Idle
        is DownloadState.Downloading -> DownloadStateStore.Downloading(progress)
        is DownloadState.Paused -> DownloadStateStore.Paused(progress)
        is DownloadState.Failed -> DownloadStateStore.Failed(errorCode, errorMessage)
        is DownloadState.Downloaded -> DownloadStateStore.Downloaded
    }
}

internal fun DownloadInfoStore.toDTO(): DownloadInfoDTO {
    return DownloadInfoDTO(
        id = id,
        fileName = fileName,
        fileUrl = fileUrl,
        filePath = filePath,
        fileSize = fileSize,
        state = state.toState(),
        version = version
    )
}

internal fun DownloadInfoDTO.toStore(): DownloadInfoStore {
    return DownloadInfoStore(
        id = id,
        fileName = fileName,
        fileUrl = fileUrl,
        filePath = filePath,
        fileSize = fileSize,
        state = state.toStore(),
        version = version
    )
}

@Serializable
internal data class DownloadStore(
    val downloads: MutableMap<String, DownloadInfoStore> = mutableMapOf()
)