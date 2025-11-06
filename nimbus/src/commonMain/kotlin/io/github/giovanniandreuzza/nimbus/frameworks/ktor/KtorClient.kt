package io.github.giovanniandreuzza.nimbus.frameworks.ktor

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.IsFramework
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

@IsFramework
internal class KtorClient {
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15000 // 15 seconds
        }
    }
}