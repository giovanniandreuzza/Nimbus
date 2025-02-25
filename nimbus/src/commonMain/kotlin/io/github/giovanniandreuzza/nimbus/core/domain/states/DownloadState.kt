package io.github.giovanniandreuzza.nimbus.core.domain.states

/**
 * Download State.
 *
 * @author Giovanni Andreuzza
 */
public sealed class DownloadState {

    /**
     * Enqueued state.
     */
    public data object Enqueued : DownloadState()

    /**
     * Downloading state.
     *
     * @param progress Download progress.
     */
    public data class Downloading(val progress: Double) : DownloadState()

    /**
     * Paused state.
     *
     * @param progress Download progress.
     */
    public data class Paused(val progress: Double) : DownloadState()

    /**
     * Failed state.
     *
     * @param errorCode Error code.
     * @param errorMessage Error message.
     */
    public data class Failed(val errorCode: String, val errorMessage: String) : DownloadState()

    /**
     * Finished state.
     */
    public data object Finished : DownloadState()
}
