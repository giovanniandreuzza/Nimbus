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

    /**
     * There was nothing to compare against: the task finished without a digest recorded,
     * or predates content digests entirely.
     *
     * Distinct from [Mismatch] on purpose. Reporting an unanswerable question as a negative
     * answer accuses an intact file of having changed, which is the conclusion this feature
     * exists to stop a caller reaching.
     */
    data object Unavailable : VerificationResult()

    data class Error(val message: String) : VerificationResult()
}

/**
 * What to show for a file that hashed to [onDisk], given what the task [recorded].
 */
fun verificationOf(onDisk: String, recorded: String?): VerificationResult = when {
    recorded == null -> VerificationResult.Unavailable
    onDisk == recorded -> VerificationResult.Match
    else -> VerificationResult.Mismatch(onDisk)
}

/**
 * Whether a verification result still describes the row it is attached to.
 *
 * A result is about one file: once the row has been cancelled or has started again, it
 * describes a file that row no longer represents. A verification still [running] is kept,
 * because it is about the action, not about a file yet.
 */
fun verificationToCarry(
    previous: VerificationResult?,
    state: DownloadDisplayState
): VerificationResult? = when {
    previous == null -> null
    previous is VerificationResult.Running -> previous
    state is DownloadDisplayState.Finished -> previous
    else -> null
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
