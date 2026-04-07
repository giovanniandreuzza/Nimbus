package io.github.giovanniandreuzza.sample_android.framework.nimbus

import io.github.giovanniandreuzza.nimbus.Nimbus
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.nimbus.ktor.KtorDownloadAdapter
import io.github.giovanniandreuzza.nimbus.withAndroidContext
import io.ktor.client.HttpClient

/**
 * Convenience factory that builds and immediately initialises [NimbusAPI].
 *
 * [Nimbus.init] is non-suspending: it kicks off background IO and returns the
 * [NimbusAPI] instance straight away. The first actual API call will suspend
 * briefly if loading is still in progress; all subsequent calls are free.
 */
fun buildNimbusApi(
    context: android.content.Context,
    httpClient: HttpClient
): NimbusAPI = Nimbus.Companion.Builder()
    .withAndroidContext(context)
    .withNimbusDownloadPort(KtorDownloadAdapter(httpClient))
    .withConcurrencyLimit(3)
    .withAutoStart(true)
    .build()
    .init()
