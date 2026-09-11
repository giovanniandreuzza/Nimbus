package io.github.giovanniandreuzza.sample_android.presentation

sealed interface MainUiAction {
    // Bulk
    data object EnqueueAll : MainUiAction
    data object PauseAll : MainUiAction
    data object ResumeAll : MainUiAction
    data object CancelAll : MainUiAction

    // Per-item (index = position in the downloads list)
    data class Enqueue(val index: Int) : MainUiAction
    data class Pause(val index: Int) : MainUiAction
    data class Resume(val index: Int) : MainUiAction
    data class Cancel(val index: Int) : MainUiAction
    data class Retry(val index: Int) : MainUiAction

    /** Re-derives the digest of a finished file from disk and compares it. */
    data class Verify(val index: Int) : MainUiAction
}
