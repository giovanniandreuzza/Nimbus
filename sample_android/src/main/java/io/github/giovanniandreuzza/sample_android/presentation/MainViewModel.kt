package io.github.giovanniandreuzza.sample_android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Main ViewModel — MVI style.
 *
 * Nimbus is configured with [withAutoStart(true)][io.github.giovanniandreuzza.nimbus.Nimbus.Companion.Builder.withAutoStart],
 * so [MainUiAction.Enqueue] is the only action needed to kick off a download;
 * the library starts it automatically in the background.
 *
 * @param nimbus  Nimbus API singleton.
 * @param downloadFolder  Absolute directory where downloaded files are stored.
 */
class MainViewModel(
    private val nimbus: NimbusAPI,
    private val downloadFolder: File
) : ViewModel() {

    // -----------------------------------------------------------------------
    // Download definitions
    // -----------------------------------------------------------------------

    private data class DownloadConfig(
        val url: String,
        val fileName: String,
        val filePath: String
    )

    private val configs = listOf(
        DownloadConfig(
            url = "https://www.shutterstock.com/shutterstock/videos/3793639367/preview/stock-footage-aaa-cat-pixel-art-animation-meme.webm",
            fileName = "stock-footage-aaa-cat-pixel-art-animation-meme.webm",
            filePath = "${downloadFolder.absolutePath}${File.separator}stock-footage-aaa-cat-pixel-art-animation-meme.webm"
        ),
        DownloadConfig(
            url = "https://www.shutterstock.com/shutterstock/videos/3819179847/preview/stock-footage-man-working-on-laptop-computer-keyboard-with-graphic-user-interface-gui-hologram-showing-concepts.mp4",
            fileName = "video2.mp4",
            filePath = "${downloadFolder.absolutePath}${File.separator}video2.mp4"
        ),
        DownloadConfig(
            url = "https://www.shutterstock.com/shutterstock/videos/3654144259/preview/stock-footage-cat-meme-banana-dress-banana-and-cat-costume-green-screen-background.webm",
            fileName = "stock-footage-cat-meme-banana-dress-banana-and-cat-costume-green-screen-background.webm",
            filePath = "${downloadFolder.absolutePath}${File.separator}stock-footage-cat-meme-banana-dress-banana-and-cat-costume-green-screen-background.webm"
        )
    )

    // -----------------------------------------------------------------------
    // State / Event streams
    // -----------------------------------------------------------------------

    private val _uiState = MutableStateFlow(
        MainUiState(downloads = configs.map { DownloadItemUiState(fileName = it.fileName) })
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>(Channel.BUFFERED)
    val uiEvent: Flow<MainUiEvent> = _uiEvent.receiveAsFlow()

    /** One Job per download index — cancelled on cancel/reset, kept alive through pause/resume. */
    private val observationJobs = mutableMapOf<Int, Job>()

    // -----------------------------------------------------------------------
    // Init — restore persisted state from previous sessions
    // -----------------------------------------------------------------------

    init {
        viewModelScope.launch {
            val existing = nimbus.getAllDownloads().getOr { emptyList() }
            for ((index, config) in configs.withIndex()) {
                val dto = existing.find { it.fileUrl == config.url } ?: continue
                val display = dto.state.toDisplayState()
                updateItemState(index, display)
                // Keep observing everything that isn't a terminal state so UI
                // reacts to state changes when the user acts (resume, etc.).
                if (display !is DownloadDisplayState.Finished &&
                    display !is DownloadDisplayState.Failed
                ) {
                    startObservation(index)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public action handler
    // -----------------------------------------------------------------------

    fun onAction(action: MainUiAction) {
        when (action) {
            MainUiAction.EnqueueAll -> configs.indices.forEach { enqueue(it) }
            MainUiAction.PauseAll -> configs.indices.forEach { pause(it) }
            MainUiAction.ResumeAll -> configs.indices.forEach { resume(it) }
            MainUiAction.CancelAll -> configs.indices.forEach { cancel(it) }
            is MainUiAction.Enqueue -> enqueue(action.index)
            is MainUiAction.Pause -> pause(action.index)
            is MainUiAction.Resume -> resume(action.index)
            is MainUiAction.Cancel -> cancel(action.index)
            is MainUiAction.Retry -> retry(action.index)
        }
    }

    // -----------------------------------------------------------------------
    // Private action implementations
    // -----------------------------------------------------------------------

    private fun enqueue(index: Int) {
        viewModelScope.launch {
            val config = configs[index]
            when (val result =
                nimbus.enqueueDownload(config.url, config.filePath, config.fileName)) {
                is Failure -> {
                    Timber.e("Enqueue $index failed: ${result.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Enqueue failed: ${result.error.message}"))
                }

                is Success -> {
                    Timber.d("Enqueued $index — autoStart will start it")
                    startObservation(index)
                }
            }
        }
    }

    private fun pause(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.pauseDownload(configs[index].url)) {
                is Failure -> {
                    Timber.e("Pause $index failed: ${result.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Pause failed: ${result.error.message}"))
                }

                is Success -> Timber.d("Paused $index")
            }
        }
    }

    private fun resume(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.resumeDownload(configs[index].url)) {
                is Failure -> {
                    Timber.e("Resume $index failed: ${result.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Resume failed: ${result.error.message}"))
                }

                is Success -> {
                    Timber.d("Resumed $index")
                    // Re-attach observation if the previous job was cancelled (e.g. app restart).
                    if (observationJobs[index]?.isActive != true) {
                        startObservation(index)
                    }
                }
            }
        }
    }

    private fun cancel(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.cancelDownload(configs[index].url)) {
                is Failure -> {
                    Timber.e("Cancel $index failed: ${result.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Cancel failed: ${result.error.message}"))
                }

                is Success -> {
                    Timber.d("Cancelled $index")
                    observationJobs.remove(index)?.cancel()
                    updateItemState(index, DownloadDisplayState.Idle)
                }
            }
        }
    }

    private fun retry(index: Int) {
        viewModelScope.launch {
            val config = configs[index]

            when (val retryResult = nimbus.retryFailedDownload(config.url)) {
                is Failure -> {
                    Timber.e("Retry $index failed: ${retryResult.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Retry failed: ${retryResult.error.message}"))
                    return@launch
                }

                is Success -> Timber.d("Task $index reset to Enqueued")
            }

            // autoStart only fires on enqueueDownload, so we start manually after retry.
            when (val startResult = nimbus.startDownload(config.url)) {
                is Failure -> {
                    Timber.e("Start after retry $index failed: ${startResult.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Start failed: ${startResult.error.message}"))
                    return@launch
                }

                is Success -> {
                    Timber.d("Started $index after retry")
                    startObservation(index)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Observation
    // -----------------------------------------------------------------------

    private fun startObservation(index: Int) {
        observationJobs.remove(index)?.cancel()
        observationJobs[index] = viewModelScope.launch {
            val config = configs[index]
            when (val result = nimbus.observeDownload(config.url)) {
                is Failure -> {
                    Timber.e("Observe $index failed: ${result.error}")
                    _uiEvent.send(MainUiEvent.ShowError("Observe failed: ${result.error.message}"))
                }

                is Success -> result.value.collect { state ->
                    Timber.d("Download $index → $state")
                    updateItemState(index, state.toDisplayState())
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun updateItemState(index: Int, displayState: DownloadDisplayState) {
        _uiState.update { current ->
            val updated = current.downloads.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(displayState = displayState)
            }
            current.copy(downloads = updated)
        }
    }

    private fun DownloadState.toDisplayState(): DownloadDisplayState = when (this) {
        DownloadState.Enqueued -> DownloadDisplayState.Enqueued
        is DownloadState.Downloading -> DownloadDisplayState.Downloading((progress / 100.0).toFloat())
        is DownloadState.Paused -> DownloadDisplayState.Paused((progress / 100.0).toFloat())
        is DownloadState.Failed -> DownloadDisplayState.Failed(error.message)
        DownloadState.Finished -> DownloadDisplayState.Finished
        DownloadState.Cancelled -> DownloadDisplayState.Idle
    }
}
