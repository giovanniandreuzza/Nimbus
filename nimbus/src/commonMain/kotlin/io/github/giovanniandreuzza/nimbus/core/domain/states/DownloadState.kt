package io.github.giovanniandreuzza.nimbus.core.domain.states

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.IsDomain
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError

/**
 * Download State.
 *
 * @author Giovanni Andreuzza
 */
@IsDomain
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
     * @param error The Error.
     */
    public data class Failed(val error: KError) : DownloadState()

    /**
     * Finished state.
     */
    public data object Finished : DownloadState()
}
