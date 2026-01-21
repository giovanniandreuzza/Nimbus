package io.github.giovanniandreuzza.sample_android.di

import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.sample_android.framework.ktor.KtorClient
import io.github.giovanniandreuzza.sample_android.framework.nimbus.KtorNimbusAdapter
import io.github.giovanniandreuzza.sample_android.framework.nimbus.NimbusSetup
import io.github.giovanniandreuzza.sample_android.presentation.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

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

    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    single {
        KtorClient()
    }

    single<NimbusDownloadPort> {
        KtorNimbusAdapter(ktorClient = get())
    }

    single<NimbusSetup> {
        NimbusSetup(context = androidApplication(), nimbusDownloadPort = get())
    }

    viewModel { MainViewModel(nimbus = get()) }
}