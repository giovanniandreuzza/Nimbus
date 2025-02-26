package io.github.giovanniandreuzza.sample_android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.asSuccess
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isFailure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.isSuccess
import io.github.giovanniandreuzza.nimbus.Nimbus
import io.github.giovanniandreuzza.nimbus.api.NimbusAPI
import io.github.giovanniandreuzza.nimbus.core.application.dtos.CancelDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ObserveDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.PauseDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.ResumeDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.StartDownloadRequest
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
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
    private val nimbus: Nimbus
) : ViewModel() {

    private val url = "https://uiuiui.storage.clo.ru/files/workshop-35/35_783436035.mp4"
    private val name1 = "video1.mp4"
    private val name2 = "video2.mp4"
    private val name3 = "video3.mp4"

    private var id1: String? = null
    private var id2: String? = null
    private var id3: String? = null

    private suspend fun getNimbusAPI(): NimbusAPI {
        val result = nimbus.init()
        if (result.isFailure()) {
            throw IllegalStateException("Failed to init nimbus: ${result.error}")
        }
        return result.value
    }

    private suspend fun loadDownloadTasks() {
        val nimbusAPI = getNimbusAPI()

        val downloads = nimbusAPI.getAllDownloads().asSuccess().value.downloads

        downloads.forEach {
            when (it.value.fileName) {
                name1 -> {
                    id1 = it.key
                }

                name2 -> {
                    id2 = it.key
                }

                name3 -> {
                    id3 = it.key
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            println("MainViewModel init")
            loadDownloadTasks()
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
            val nimbusAPI = getNimbusAPI()

            val filePath1 = folder.path + File.separator + "video1.mp4"
            val downloadRequest1 = DownloadRequest(url, filePath1, name1)

            val downloadTaskResult = nimbusAPI.getDownloadTask(downloadRequest1)

            if (downloadTaskResult.isSuccess()) {
                Timber.e("Already enqueued 1")
                return@launch
            }

            val result = nimbusAPI.enqueueDownload(downloadRequest1)

            if (result.isFailure()) {
                Timber.e("Failed to enqueue download 1: ${result.error}")
                return@launch
            }

            id1 = result.value.id
        }
    }

    fun enqueue2(folder: File) {
        viewModelScope.launch {
            val nimbusAPI = getNimbusAPI()

            val filePath = folder.path + File.separator + "video2.mp4"
            val downloadRequest = DownloadRequest(url, filePath, name2)

            val downloadTaskResult = nimbusAPI.getDownloadTask(downloadRequest)

            if (downloadTaskResult.isSuccess()) {
                Timber.d("Already enqueued 2")
                return@launch
            }

            val result = nimbusAPI.enqueueDownload(downloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to enqueue download 2: ${result.error}")
                return@launch
            }

            id2 = result.value.id
        }
    }

    fun enqueue3(folder: File) {
        viewModelScope.launch {
            val nimbusAPI = getNimbusAPI()

            val filePath = folder.path + File.separator + "video3.mp4"
            val downloadRequest = DownloadRequest(url, filePath, name3)

            val downloadTaskResult = nimbusAPI.getDownloadTask(downloadRequest)

            if (downloadTaskResult.isSuccess()) {
                Timber.d("Already enqueued 3")
                return@launch
            }

            val result = nimbusAPI.enqueueDownload(downloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to enqueue download 3: ${result.error}")
                return@launch
            }

            id3 = result.value.id
        }
    }

    fun start1() {
        viewModelScope.launch {
            if (id1 == null) {
                return@launch
            }

            val nimbusAPI = getNimbusAPI()

            val startDownloadRequest = StartDownloadRequest(id1!!)

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
            if (id2 == null) {
                return@launch
            }

            val nimbusAPI = getNimbusAPI()

            val startDownloadRequest = StartDownloadRequest(id2!!)

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
            if (id3 == null) {
                return@launch
            }

            val nimbusAPI = getNimbusAPI()

            val startDownloadRequest = StartDownloadRequest(id3!!)

            val result = nimbusAPI.startDownload(startDownloadRequest)

            if (result.isFailure()) {
                Timber.e("Failed to start download 3: ${result.error}")
                return@launch
            }

            observeDownload3()
        }
    }

    private suspend fun observeDownload1() {
        id1?.let {
            val observeDownloadRequest = ObserveDownloadRequest(it)

            val nimbusAPI = getNimbusAPI()

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
                            Timber.e("Failed 1: ${downloadState.errorCode}")
                        }

                        DownloadState.Finished -> {
                            Timber.d("Finished 1")
                        }
                    }
                }
            }

            Timber.d("Download 1 finished in $timeTaken ms")
        }
    }

    private suspend fun observeDownload2() {
        id2?.let {
            val observeDownloadRequest = ObserveDownloadRequest(it)

            val nimbusAPI = getNimbusAPI()

            val flowResult = nimbusAPI.observeDownload(observeDownloadRequest)

            if (flowResult.isFailure()) {
                Timber.e("Failed to observe download 2: ${flowResult.error}")
                return
            }

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
                            Timber.e("Failed 2: ${downloadState.errorCode}")
                        }

                        DownloadState.Finished -> {
                            Timber.d("Finished 2")
                        }
                    }
                }
            }

            Timber.d("Download 2 finished in $timeTaken ms")
        }
    }

    private suspend fun observeDownload3() {
        id3?.let {
            val observeDownloadRequest = ObserveDownloadRequest(it)

            val nimbusAPI = getNimbusAPI()

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
                            Timber.e("Failed 3: ${downloadState.errorCode}")
                        }

                        DownloadState.Finished -> {
                            Timber.d("Finished 3")
                        }
                    }
                }
            }

            Timber.d("Download 3 finished in $timeTaken ms")
        }
    }

    fun pause1() {
        viewModelScope.launch {
            if (id1 == null) {
                loadDownloadTasks()
            }

            id1?.let {
                val nimbusAPI = getNimbusAPI()

                val pauseDownloadRequest = PauseDownloadRequest(it)
                val result = nimbusAPI.pauseDownload(pauseDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to pause download 1: ${result.error}")
                }
            }
        }
    }

    fun pause2() {
        viewModelScope.launch {
            if (id2 == null) {
                loadDownloadTasks()
            }

            id2?.let {
                val nimbusAPI = getNimbusAPI()

                val pauseDownloadRequest = PauseDownloadRequest(it)
                val result = nimbusAPI.pauseDownload(pauseDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to pause download 2: ${result.error}")
                }
            }
        }
    }

    fun pause3() {
        viewModelScope.launch {
            if (id3 == null) {
                loadDownloadTasks()
            }

            id3?.let {
                val nimbusAPI = getNimbusAPI()

                val pauseDownloadRequest = PauseDownloadRequest(it)
                val result = nimbusAPI.pauseDownload(pauseDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to pause download 3: ${result.error}")
                }
            }
        }
    }

    fun resume1() {
        viewModelScope.launch {
            if (id1 == null) {
                loadDownloadTasks()
            }

            id1?.let {
                val nimbusAPI = getNimbusAPI()

                val resumeDownloadRequest = ResumeDownloadRequest(it)
                val result = nimbusAPI.resumeDownload(resumeDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to resume download 1: ${result.error}")
                }

                observeDownload1()
            }
        }
    }

    fun resume2() {
        viewModelScope.launch {
            if (id2 == null) {
                loadDownloadTasks()
            }

            id2?.let {
                val nimbusAPI = getNimbusAPI()

                val resumeDownloadRequest = ResumeDownloadRequest(it)
                val result = nimbusAPI.resumeDownload(resumeDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to resume download 2: ${result.error}")
                }

                observeDownload2()
            }
        }
    }

    fun resume3() {
        viewModelScope.launch {
            if (id3 == null) {
                loadDownloadTasks()
            }

            id3?.let {
                val nimbusAPI = getNimbusAPI()

                val resumeDownloadRequest = ResumeDownloadRequest(it)
                val result = nimbusAPI.resumeDownload(resumeDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to resume download 3: ${result.error}")
                }

                observeDownload3()
            }
        }
    }

    fun cancel1() {
        viewModelScope.launch {
            if (id1 == null) {
                loadDownloadTasks()
            }

            id1?.let {
                val nimbusAPI = getNimbusAPI()

                val cancelDownloadRequest = CancelDownloadRequest(it)
                val result = nimbusAPI.cancelDownload(cancelDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to cancel download 1: ${result.error}")
                }

                id1 = null
            }
        }
    }

    fun cancel2() {
        viewModelScope.launch {
            if (id2 == null) {
                loadDownloadTasks()
            }

            id2?.let {
                val nimbusAPI = getNimbusAPI()

                val cancelDownloadRequest = CancelDownloadRequest(it)
                val result = nimbusAPI.cancelDownload(cancelDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to cancel download 2: ${result.error}")
                }

                id2 = null
            }
        }
    }

    fun cancel3() {
        viewModelScope.launch {
            if (id3 == null) {
                loadDownloadTasks()
            }

            id3?.let {
                val nimbusAPI = getNimbusAPI()

                val cancelDownloadRequest = CancelDownloadRequest(it)
                val result = nimbusAPI.cancelDownload(cancelDownloadRequest)

                if (result.isFailure()) {
                    Timber.e("Failed to cancel download 3: ${result.error}")
                }

                id3 = null
            }
        }
    }
}