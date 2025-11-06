package io.github.giovanniandreuzza.sample_android.di

import io.github.giovanniandreuzza.sample_android.framework.nimbus.NimbusSetup
import io.github.giovanniandreuzza.sample_android.framework.retrofit.AppEndpoint
import io.github.giovanniandreuzza.sample_android.presentation.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidApplication
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

    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    single<NimbusSetup> {
        NimbusSetup(context = androidApplication())
    }

    viewModel { MainViewModel(nimbus = get()) }
}