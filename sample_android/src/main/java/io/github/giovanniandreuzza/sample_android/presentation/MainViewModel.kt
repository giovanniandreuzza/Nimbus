package io.github.giovanniandreuzza.sample_android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
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
 * Nimbus is configured with [withAutoStart(true)][io.github.giovanniandreuzza.nimbus.Nimbus.Builder.withAutoStart],
 * so [MainUiAction.Enqueue] is the only action needed to kick off a download;
 * the library starts it automatically in the background.
 *
 * The whole UI is driven by a single [NimbusAPI.observeAllDownloads] collector.
 * It emits the entire catalogue on every change — including progress ticks — so
 * there is no per-task observation to attach, re-attach after a restart, or tear
 * down on cancel. A cancelled task leaves the catalogue and its row falls back to
 * [DownloadDisplayState.Idle] on its own.
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

    // -----------------------------------------------------------------------
    // Init — one collector drives every row, including persisted tasks
    // -----------------------------------------------------------------------

    init {
        viewModelScope.launch {
            nimbus.observeAllDownloads().collect { tasks -> render(tasks) }
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
            is MainUiAction.Start -> start(action.index)
            is MainUiAction.Retry -> retry(action.index)
            is MainUiAction.Verify -> verify(action.index)
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
                is Failure -> report("Enqueue", index, result.error.message)
                is Success -> Timber.d("Enqueued $index — autoStart will start it")
            }
        }
    }

    private fun pause(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.pauseDownload(configs[index].url)) {
                is Failure -> report("Pause", index, result.error.message)
                is Success -> Timber.d("Paused $index")
            }
        }
    }

    private fun resume(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.resumeDownload(configs[index].url)) {
                is Failure -> report("Resume", index, result.error.message)
                is Success -> Timber.d("Resumed $index")
            }
        }
    }

    private fun cancel(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.cancelDownload(configs[index].url)) {
                is Failure -> report("Cancel", index, result.error.message)
                is Success -> Timber.d("Cancelled $index")
            }
        }
    }

    private fun start(index: Int) {
        viewModelScope.launch {
            when (val result = nimbus.startDownload(configs[index].url)) {
                is Failure -> report("Start", index, result.error.message)
                is Success -> Timber.d("Started $index")
            }
        }
    }

    private fun retry(index: Int) {
        viewModelScope.launch {
            val config = configs[index]

            when (val result = nimbus.retryFailedDownload(config.url)) {
                is Failure -> return@launch report("Retry", index, result.error.message)
                is Success -> Timber.d("Task $index reset to Enqueued")
            }

            // autoStart only fires on enqueueDownload, so start manually after a retry.
            when (val result = nimbus.startDownload(config.url)) {
                is Failure -> report("Start after retry", index, result.error.message)
                is Success -> Timber.d("Started $index after retry")
            }
        }
    }

    /**
     * Re-derives the digest of the finished file from disk and compares it with the digest
     * Nimbus recorded when the download completed.
     *
     * This is what separates a file whose bytes changed on disk from one that is intact and
     * simply cannot be used — delete the file from the device while the app runs and this
     * reports the difference instead of silently re-downloading.
     */
    private fun verify(index: Int) {
        viewModelScope.launch {
            updateItem(index) { it.copy(verification = VerificationResult.Running) }

            when (val result = nimbus.checksum(configs[index].url)) {
                is Failure -> {
                    Timber.e("Verify $index failed: ${result.error}")
                    updateItem(index) {
                        it.copy(verification = VerificationResult.Error(result.error.message))
                    }
                }

                is Success -> {
                    val onDisk = result.value.value
                    val recorded = _uiState.value.downloads.getOrNull(index)?.checksum
                    Timber.d("Verify $index — on disk $onDisk, recorded $recorded")
                    updateItem(index) {
                        it.copy(verification = verificationOf(onDisk, recorded))
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    /** Projects the whole catalogue onto the fixed row list, matching on URL. */
    private fun render(tasks: List<DownloadTaskDTO>) {
        val byUrl = tasks.associateBy { it.fileUrl }
        _uiState.update { current ->
            current.copy(
                downloads = configs.mapIndexed { index, config ->
                    val previous = current.downloads.getOrNull(index)
                    val task = byUrl[config.url]
                    DownloadItemUiState(
                        fileName = config.fileName,
                        displayState = task?.state?.toDisplayState() ?: DownloadDisplayState.Idle,
                        checksum = task?.checksum?.value,
                        // Verification is a transient result of a user action, so the
                        // catalogue does not carry it — but it is about one file, and a row
                        // that has been cancelled or started again is no longer showing that
                        // file.
                        verification = verificationToCarry(
                            previous?.verification,
                            task?.state?.toDisplayState() ?: DownloadDisplayState.Idle
                        )
                    )
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun report(label: String, index: Int, message: String) {
        Timber.e("$label $index failed: $message")
        _uiEvent.send(MainUiEvent.ShowError("$label failed: $message"))
    }

    private fun updateItem(index: Int, transform: (DownloadItemUiState) -> DownloadItemUiState) {
        _uiState.update { current ->
            val updated = current.downloads.toMutableList()
            if (index in updated.indices) updated[index] = transform(updated[index])
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
