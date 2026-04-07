package io.github.giovanniandreuzza.sample_android.di

import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.sample_android.framework.ktor.KtorClient
import io.github.giovanniandreuzza.sample_android.framework.nimbus.buildNimbusApi
import io.github.giovanniandreuzza.sample_android.presentation.MainViewModel
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
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

    single { KtorClient() }

    single<NimbusAPI> {
        buildNimbusApi(
            context = androidApplication(),
            httpClient = get<KtorClient>().client
        )
    }

    viewModel {
        val downloadFolder = File(androidApplication().filesDir, "downloads").also { it.mkdirs() }
        MainViewModel(nimbus = get(), downloadFolder = downloadFolder)
    }
}