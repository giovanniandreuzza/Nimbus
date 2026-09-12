package io.github.giovanniandreuzza.sample_android.framework.ktor

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

@IsFramework
internal class KtorClient {

    val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000 // 15 seconds
            // KtorDownloadAdapter imposes one of these on every request it makes, so Nimbus
            // is covered either way. It is set here as well because it is the right default
            // for every other call an app makes through the same client: a read with no
            // deadline is a thread that never comes back.
            socketTimeoutMillis = 30_000 // 30 seconds without a single packet
        }
    }
}