package io.github.giovanniandreuzza.sample_android.presentation

sealed interface MainUiEvent {
    data class ShowError(val message: String) : MainUiEvent
}
