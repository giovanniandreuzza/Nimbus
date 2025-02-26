package io.github.giovanniandreuzza.sample_android.di

import io.github.giovanniandreuzza.nimbus.Nimbus
import io.github.giovanniandreuzza.nimbus.frameworks.downloadmanager.ports.NimbusDownloadRepository
import io.github.giovanniandreuzza.nimbus.frameworks.filemanager.ports.NimbusFileRepository
import io.github.giovanniandreuzza.sample_android.framework.retrofit.AppEndpoint
import io.github.giovanniandreuzza.sample_android.infrastructure.LocalNimbusFileRepository
import io.github.giovanniandreuzza.sample_android.infrastructure.RemoteDownloadRepository
import io.github.giovanniandreuzza.sample_android.presentation.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File

/**
 * App Module Dependency Injection.
 *
 * @author Giovanni Andreuzza
 */
val appModule = module {

    single<Json> {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            useAlternativeNames = false
        }
    }

    single<AppEndpoint> {
        Retrofit.Builder().baseUrl("https://www.google.com").build().create(AppEndpoint::class.java)
    }

    factory<NimbusDownloadRepository> { RemoteDownloadRepository(appEndpoint = get()) }

    factory<NimbusFileRepository> { LocalNimbusFileRepository() }

    single<Nimbus> {
        val folder = File(androidApplication().filesDir, "test").also {
            it.mkdirs()
        }

        Nimbus.Companion.Builder()
            .withDomainEventBusScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            .withDomainEventBusOnError {
                Timber.e("EventBus error: $it")
            }
            .withDownloadScope(CoroutineScope(SupervisorJob() + Dispatchers.IO))
            .withIODispatcher(Dispatchers.IO)
            .withConcurrencyLimit(5)
            .withNimbusDownloadRepository(get())
            .withNimbusFileRepository(get())
            .withDownloadManagerPath(folder.path + File.separator + "download_manager")
            .build()
    }

    viewModel { MainViewModel(nimbus = get()) }
}