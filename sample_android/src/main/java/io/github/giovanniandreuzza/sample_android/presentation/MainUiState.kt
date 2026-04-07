package io.github.giovanniandreuzza.sample_android.presentation

data class MainUiState(
    val downloads: List<DownloadItemUiState> = emptyList()
)

data class DownloadItemUiState(
    val fileName: String,
    val displayState: DownloadDisplayState = DownloadDisplayState.Idle
)

sealed class DownloadDisplayState {
    data object Idle : DownloadDisplayState()
    data object Enqueued : DownloadDisplayState()

    /** [progress] in 0f..1f */
    data class Downloading(val progress: Float) : DownloadDisplayState()

    /** [progress] in 0f..1f */
    data class Paused(val progress: Float) : DownloadDisplayState()

    data object Finished : DownloadDisplayState()
    data class Failed(val message: String) : DownloadDisplayState()
}
