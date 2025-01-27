package io.github.giovanniandreuzza.sample_android.di

import io.github.giovanniandreuzza.nimbus.Nimbus
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadRepository
import io.github.giovanniandreuzza.nimbus.core.ports.FileRepository
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.sample_android.framework.retrofit.AppEndpoint
import io.github.giovanniandreuzza.sample_android.infrastructure.LocalFileRepository
import io.github.giovanniandreuzza.sample_android.infrastructure.RemoteDownloadRepository
import io.github.giovanniandreuzza.sample_android.presentation.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit

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

    factory<DownloadRepository> { RemoteDownloadRepository(appEndpoint = get()) }

    factory<FileRepository> { LocalFileRepository() }

    single<NimbusAPI> {
        Nimbus.Companion.Builder()
            .withDownloadRepository(get())
            .withFileRepository(get())
            .withConcurrencyLimit(5)
            .build()
    }

    viewModel { MainViewModel(nimbusAPI = get()) }
}