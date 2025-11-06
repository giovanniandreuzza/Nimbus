package io.github.giovanniandreuzza.sample_android.framework.nimbus

import android.content.Context
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.nimbus.Nimbus
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import timber.log.Timber
import java.io.File

class NimbusSetup(context: Context) {

    private val nimbus: Nimbus
    lateinit var client: NimbusAPI
        private set

    init {
        val folder = File(context.filesDir, "test").also {
            it.mkdirs()
        }

        nimbus = Nimbus.Companion.Builder()
            .withDownloadManagerPath(folder.path + File.separator + "download_manager")
            .withDownloadBufferSize(8 * 1024L)
            .withDownloadNotifyEveryBytes(8 * 64 * 1024L)
            .build()
    }

    suspend fun init() {
        if (::client.isInitialized) {
            Timber.d("Nimbus already initialized")
            return
        }
        client = nimbus.init().getOr {
            Timber.e("Nimbus initialization failed: ${it.message}")
            throw Exception("Nimbus initialization failed: ${it.message}")
        }
        Timber.d("Nimbus initialized successfully")
    }
}