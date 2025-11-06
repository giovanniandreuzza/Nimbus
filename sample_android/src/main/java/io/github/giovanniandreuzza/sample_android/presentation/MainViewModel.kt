package io.github.giovanniandreuzza.sample_android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onSuccess
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.EnqueueDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.GetDownloadTaskRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.sample_android.framework.nimbus.NimbusSetup
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Main ViewModel.
 *
 * @param nimbus The Nimbus Library.
 * @author Giovanni Andreuzza
 */
class MainViewModel(
    private val nimbus: NimbusSetup
) : ViewModel() {

    private val url1 =
        "https://www.psdstack.com/wp-content/uploads/2019/08/copyright-free-images-750x420.jpg"
    private val url2 = "https://uiuiui.storage.clo.ru/files/workshop-35/35_783436035.mp4"
    private val url3 =
        "https://wallpapers.com/images/hd/non-copyrighted-retro-wave-style-s3rhyqiwlu0dlxlz.jpg"
    private val name1 = "image1.jpg"
    private val name2 = "video2.mp4"
    private val name3 = "image3.jpg"

    init {
        viewModelScope.launch {
            println("MainViewModel init")
            nimbus.init()
        }
    }

    fun enqueueAll(folder: File) {
        Timber.d("Enqueue all")
        enqueue1(folder)
        enqueue2(folder)
        enqueue3(folder)
    }

    fun startAll() {
        Timber.d("Start all")
        start1()
        start2()
        start3()
    }

    fun pauseAll() {
        Timber.d("Pause all")
        pause1()
        pause2()
        pause3()
    }

    fun resumeAll() {
        Timber.d("Resume all")
        resume1()
        resume2()
        resume3()
    }

    fun cancelAll() {
        Timber.d("Cancel all")
        cancel1()
        cancel2()
        cancel3()
    }

    fun enqueue1(folder: File) {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client
            val filePath = folder.path + File.separator + name1

            val getDownloadTaskRequest = GetDownloadTaskRequest(url1)
            nimbusAPI.getDownloadTask(getDownloadTaskRequest).onSuccess {
                Timber.e("Already enqueued 1")
                return@launch
            }

            val enqueueDownloadRequest = EnqueueDownloadRequest(url1, filePath, name1)
            nimbusAPI.enqueueDownload(enqueueDownloadRequest).getOr {
                Timber.e("Failed to enqueue download 1: $it")
                return@launch
            }
            Timber.i("Enqueued download 1")
        }
    }

    fun enqueue2(folder: File) {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client
            val filePath = folder.path + File.separator + name2

            val getDownloadTaskRequest = GetDownloadTaskRequest(url2)
            nimbusAPI.getDownloadTask(getDownloadTaskRequest).onSuccess {
                Timber.e("Already enqueued 2")
                return@launch
            }

            val enqueueDownloadRequest = EnqueueDownloadRequest(url2, filePath, name2)
            nimbusAPI.enqueueDownload(enqueueDownloadRequest).getOr {
                Timber.e("Failed to enqueue download 2: $it")
                return@launch
            }
            Timber.i("Enqueued download 2")
        }
    }

    fun enqueue3(folder: File) {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client
            val filePath = folder.path + File.separator + name3

            val getDownloadTaskRequest = GetDownloadTaskRequest(url3)
            nimbusAPI.getDownloadTask(getDownloadTaskRequest).onSuccess {
                Timber.e("Already enqueued 3")
                return@launch
            }

            val enqueueDownloadRequest = EnqueueDownloadRequest(url3, filePath, name3)
            nimbusAPI.enqueueDownload(enqueueDownloadRequest).getOr {
                Timber.e("Failed to enqueue download 3: $it")
                return@launch
            }
            Timber.i("Enqueued download 3")
        }
    }

    fun start1() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val startDownloadRequest = StartDownloadRequest(url1)

            val result = nimbusAPI.startDownload(startDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to start download 1: ${result.error}")
                return@launch
            }

            observeDownload1()
        }
    }

    fun start2() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val startDownloadRequest = StartDownloadRequest(url2)

            val result = nimbusAPI.startDownload(startDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to start download 2: ${result.error}")
                return@launch
            }

            observeDownload2()
        }
    }

    fun start3() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val startDownloadRequest = StartDownloadRequest(url3)

            val result = nimbusAPI.startDownload(startDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to start download 3: ${result.error}")
                return@launch
            }

            observeDownload3()
        }
    }

    private suspend fun observeDownload1() {
        val observeDownloadRequest = ObserveDownloadRequest(url1)

        val nimbusAPI = nimbus.client

        val flowResult = nimbusAPI.observeDownload(observeDownloadRequest)

        if (flowResult.isFailure()) {
            Timber.e("Failed to observe download 1: ${flowResult.error}")
            return
        }

        val timeTaken = measureTimeMillis {
            flowResult.value.downloadFlow.collect { downloadState ->
                when (downloadState) {
                    DownloadState.Enqueued -> {
                        Timber.d("Enqueued 1")
                    }

                    is DownloadState.Downloading -> {
                        Timber.d("Downloading 1: ${downloadState.progress}")
                    }

                    is DownloadState.Paused -> {
                        Timber.d("Paused 1: ${downloadState.progress}")
                    }

                    is DownloadState.Failed -> {
                        Timber.e("Failed 1: ${downloadState.error}")
                    }

                    DownloadState.Finished -> {
                        Timber.d("Finished 1")
                    }
                }
            }
        }

        Timber.d("Download 1 finished in $timeTaken ms")
    }

    private suspend fun observeDownload2() {
        val observeDownloadRequest = ObserveDownloadRequest(url2)

        val nimbusAPI = nimbus.client

        Timber.d("Observing download 2")

        val flowResult = nimbusAPI.observeDownload(observeDownloadRequest)

        if (flowResult.isFailure()) {
            Timber.e("Failed to observe download 2: ${flowResult.error}")
            return
        }

        Timber.d("Successfully got flow for download 2")

        val timeTaken = measureTimeMillis {
            flowResult.value.downloadFlow.collect { downloadState ->
                when (downloadState) {
                    DownloadState.Enqueued -> {
                        Timber.d("Enqueued 2")
                    }

                    is DownloadState.Downloading -> {
                        Timber.d("Downloading 2: ${downloadState.progress}")
                    }

                    is DownloadState.Paused -> {
                        Timber.d("Paused 2: ${downloadState.progress}")
                    }

                    is DownloadState.Failed -> {
                        Timber.e("Failed 2: ${downloadState.error}")
                    }

                    DownloadState.Finished -> {
                        Timber.d("Finished 2")
                    }
                }
            }
        }

        Timber.d("Download 2 finished in $timeTaken ms")
    }

    private suspend fun observeDownload3() {
        val observeDownloadRequest = ObserveDownloadRequest(url3)

        val nimbusAPI = nimbus.client

        val flowResult = nimbusAPI.observeDownload(observeDownloadRequest)

        if (flowResult.isFailure()) {
            Timber.e("Failed to observe download 3: ${flowResult.error}")
            return
        }

        val timeTaken = measureTimeMillis {
            flowResult.value.downloadFlow.collect { downloadState ->
                when (downloadState) {
                    DownloadState.Enqueued -> {
                        Timber.d("Enqueued 3")
                    }

                    is DownloadState.Downloading -> {
                        Timber.d("Downloading 3: ${downloadState.progress}")
                    }

                    is DownloadState.Paused -> {
                        Timber.d("Paused 3: ${downloadState.progress}")
                    }

                    is DownloadState.Failed -> {
                        Timber.e("Failed 3: ${downloadState.error}")
                    }

                    DownloadState.Finished -> {
                        Timber.d("Finished 3")
                    }
                }
            }
        }

        Timber.d("Download 3 finished in $timeTaken ms")
    }

    fun pause1() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val pauseDownloadRequest = PauseDownloadRequest(url1)
            val result = nimbusAPI.pauseDownload(pauseDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to pause download 1: ${result.error}")
            }
        }
    }

    fun pause2() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val pauseDownloadRequest = PauseDownloadRequest(url2)
            val result = nimbusAPI.pauseDownload(pauseDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to pause download 2: ${result.error}")
            }
        }
    }

    fun pause3() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val pauseDownloadRequest = PauseDownloadRequest(url3)
            val result = nimbusAPI.pauseDownload(pauseDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to pause download 3: ${result.error}")
            }
        }
    }

    fun resume1() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val resumeDownloadRequest = ResumeDownloadRequest(url1)
            val result = nimbusAPI.resumeDownload(resumeDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to resume download 1: ${result.error}")
            }

            observeDownload1()
        }
    }

    fun resume2() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val resumeDownloadRequest = ResumeDownloadRequest(url2)
            val result = nimbusAPI.resumeDownload(resumeDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to resume download 2: ${result.error}")
            }

            observeDownload2()
        }
    }

    fun resume3() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val resumeDownloadRequest = ResumeDownloadRequest(url3)
            val result = nimbusAPI.resumeDownload(resumeDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to resume download 3: ${result.error}")
            }

            observeDownload3()
        }
    }

    fun cancel1() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val cancelDownloadRequest = CancelDownloadRequest(url1)
            val result = nimbusAPI.cancelDownload(cancelDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to cancel download 1: ${result.error}")
            }
        }
    }

    fun cancel2() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val cancelDownloadRequest = CancelDownloadRequest(url2)
            val result = nimbusAPI.cancelDownload(cancelDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to cancel download 2: ${result.error}")
            }
        }
    }

    fun cancel3() {
        viewModelScope.launch {
            val nimbusAPI = nimbus.client

            val cancelDownloadRequest = CancelDownloadRequest(url3)
            val result = nimbusAPI.cancelDownload(cancelDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to cancel download 3: ${result.error}")
            }
        }
    }
}