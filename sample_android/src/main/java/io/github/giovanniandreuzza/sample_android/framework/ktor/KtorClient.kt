package io.github.giovanniandreuzza.sample_android.framework.ktor

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

@IsFramework
internal class KtorClient {

    val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15000 // 15 seconds
        }
    }
}