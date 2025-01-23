package io.github.giovanniandreuzza.nimbus.core.application.dtos

/**
 * Download State.
 *
 * @author Giovanni Andreuzza
 */
public sealed class DownloadState {

    /**
     * Idle state.
     */
    public data object Idle : DownloadState()

    /**
     * Downloading state.
     *
     * @param progress Download progress.
     */
    public data class Downloading(val progress: Double) : DownloadState()

    /**
     * Finished state.
     */
    public data object Finished : DownloadState()

    /**
     * Failed state.
     *
     * @param error The error.
     */
    public data class Failed(val error: Throwable? = null) : DownloadState()
}
