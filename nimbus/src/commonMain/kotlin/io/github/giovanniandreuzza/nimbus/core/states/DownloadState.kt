package io.github.giovanniandreuzza.nimbus.core.application.dtos

import io.github.giovanniandreuzza.nimbus.shared.ddd.application.Application
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Download State.
 *
 * @author Giovanni Andreuzza
 */
@Serializable
public sealed class DownloadState : Application {

    /**
     * Idle state.
     */
    @Serializable
    @SerialName("Idle")
    public object Idle : DownloadState()

    /**
     * Downloading state.
     *
     * @param progress Download progress.
     */
    @Serializable
    @SerialName("Downloading")
    public data class Downloading(val progress: Double) : DownloadState()

    /**
     * Paused state.
     *
     * @param progress Download progress.
     */
    @Serializable
    @SerialName("Paused")
    public data class Paused(val progress: Double) : DownloadState()

    /**
     * Failed state.
     *
     * @param error The error.
     */
    @Serializable
    @SerialName("Failed")
    public data class Failed(val error: Throwable? = null) : DownloadState()

    /**
     * Downloaded state.
     */
    @Serializable
    @SerialName("Downloaded")
    public object Downloaded : DownloadState()
}
