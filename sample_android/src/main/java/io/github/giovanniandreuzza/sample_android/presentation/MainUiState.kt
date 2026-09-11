package io.github.giovanniandreuzza.sample_android.presentation

data class MainUiState(
    val downloads: List<DownloadItemUiState> = emptyList()
)

data class DownloadItemUiState(
    val fileName: String,
    val displayState: DownloadDisplayState = DownloadDisplayState.Idle,
    /** Hex digest of the transferred bytes — populated by Nimbus once Finished. */
    val checksum: String? = null,
    /** Result of the last [MainUiAction.Verify], re-derived from the file on disk. */
    val verification: VerificationResult? = null
)

sealed class VerificationResult {
    data object Running : VerificationResult()

    /** The file on disk still hashes to what was downloaded. */
    data object Match : VerificationResult()

    /** The bytes on disk changed since the download finished. */
    data class Mismatch(val onDisk: String) : VerificationResult()

    data class Error(val message: String) : VerificationResult()
}

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
