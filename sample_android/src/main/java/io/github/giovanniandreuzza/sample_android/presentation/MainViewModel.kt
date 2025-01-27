package io.github.giovanniandreuzza.sample_android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadRequest
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadState
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Main ViewModel.
 *
 * @param nimbusAPI The Nimbus API.
 * @author Giovanni Andreuzza
 */
class MainViewModel(
    private val nimbusAPI: NimbusAPI
) : ViewModel() {

    private val url = "https://uiuiui.storage.clo.ru/files/workshop-35/35_783436035.mp4"
    private val name = "video.mp4"

    private var id1: Long? = null
    private var id2: Long? = null
    private var id3: Long? = null

    fun start1(folder: File) {
        viewModelScope.launch {
            val filePath1 = folder.path + File.separator + "video1.mp4"
            val downloadRequest1 = DownloadRequest(url, filePath1, name)

            id1 = nimbusAPI.downloadFile(downloadRequest1)
            nimbusAPI.observeDownload(id1!!)
                .collect { downloadState ->
                    when (downloadState) {
                        is DownloadState.Downloading -> {
                            Timber.d("Downloading 1: ${downloadState.progress}")
                        }

                        is DownloadState.Failed -> {
                            Timber.e("Failed 1: ${downloadState.error}")
                        }

                        DownloadState.Finished -> {
                            Timber.d("Finished 1")
                        }

                        DownloadState.Idle -> {
                            Timber.d("Idle 1")
                        }
                    }
                }
        }
    }

    fun start2(folder: File) {
        viewModelScope.launch {
            val filePath2 = folder.path + File.separator + "video2.mp4"
            val downloadRequest2 = DownloadRequest(url, filePath2, name)

            id2 = nimbusAPI.downloadFile(downloadRequest2)
            nimbusAPI.observeDownload(id2!!).collect { downloadState ->
                when (downloadState) {
                    is DownloadState.Downloading -> {
                        Timber.d("Downloading 2: ${downloadState.progress}")
                    }

                    is DownloadState.Failed -> {
                        Timber.e("Failed 2: ${downloadState.error}")
                    }

                    DownloadState.Finished -> {
                        Timber.d("Finished 2")
                    }

                    DownloadState.Idle -> {
                        Timber.d("Idle 2")
                    }
                }
            }
        }
    }

    fun start3(folder: File) {
        viewModelScope.launch {
            val filePath3 = folder.path + File.separator + "video3.mp4"
            val downloadRequest3 = DownloadRequest(url, filePath3, name)

            id3 = nimbusAPI.downloadFile(downloadRequest3)
            nimbusAPI.observeDownload(id3!!).collect { downloadState ->
                when (downloadState) {
                    is DownloadState.Downloading -> {
                        Timber.d("Downloading 3: ${downloadState.progress}")
                    }

                    is DownloadState.Failed -> {
                        Timber.e("Failed 3: ${downloadState.error}")
                    }

                    DownloadState.Finished -> {
                        Timber.d("Finished 3")
                    }

                    DownloadState.Idle -> {
                        Timber.d("Idle 3")
                    }
                }
            }
        }
    }

    fun pause1() {
        id1?.let { nimbusAPI.pauseDownload(it) }
    }

    fun resume1() {
        id1?.let { nimbusAPI.resumeDownload(it) }
    }

    fun cancel1() {
        id1?.let { nimbusAPI.cancelDownload(it) }
    }

    fun pause2() {
        id2?.let { nimbusAPI.pauseDownload(it) }
    }

    fun resume2() {
        id2?.let { nimbusAPI.resumeDownload(it) }
    }

    fun cancel2() {
        id2?.let { nimbusAPI.cancelDownload(it) }
    }

    fun pause3() {
        id3?.let { nimbusAPI.pauseDownload(it) }
    }

    fun resume3() {
        id3?.let { nimbusAPI.resumeDownload(it) }
    }

    fun cancel3() {
        id3?.let { nimbusAPI.cancelDownload(it) }
    }
}