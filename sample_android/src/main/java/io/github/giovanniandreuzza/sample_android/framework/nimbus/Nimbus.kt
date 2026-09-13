package io.github.giovanniandreuzza.sample_android.framework.nimbus

import io.github.giovanniandreuzza.nimbus.Nimbus
import io.github.giovanniandreuzza.nimbus.ktor.KtorDownloadAdapter
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusAPI
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import io.github.giovanniandreuzza.nimbus.withAndroidContext
import io.ktor.client.HttpClient
import java.io.File
import timber.log.Timber

/**
 * Convenience factory that builds and immediately initialises [NimbusAPI].
 *
 * [Nimbus.init] is non-suspending: it kicks off background IO and returns the
 * [NimbusAPI] instance straight away. The first actual API call will suspend
 * briefly if loading is still in progress; all subsequent calls are free.
 *
 * Content digest is enabled here so the sample exercises it. It is opt-in: with
 * no algorithm configured nothing is hashed and the transfer path is unchanged.
 *
 * [withDownloadRoot][Nimbus.Builder.withDownloadRoot] is the one line worth copying
 * into anything that takes its file list from a server: without it the only check
 * on a destination is that it holds no `..`, so a path naming the app's own
 * database would be accepted and written to.
 */
fun buildNimbusApi(
    context: android.content.Context,
    httpClient: HttpClient
): NimbusAPI = Nimbus.Builder()
    .withAndroidContext(context)
    .withNimbusDownloadPort(KtorDownloadAdapter(httpClient))
    .withDownloadRoot(File(context.filesDir, "downloads").absolutePath)
    .withConcurrencyLimit(3)
    .withAutoStart(true)
    .withContentDigest(DigestAlgorithm.SHA256)
    .withNimbusLogger(NimbusLogger { event -> Timber.tag("Nimbus").d("%s", event) })
    .build()
    .init()
