package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage

import io.github.giovanniandreuzza.explicitarchitecture.frameworks.models.IsFrameworkDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Download Store.
 *
 * @param downloads Download tasks.
 * @author Giovanni Andreuzza
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@IsFrameworkDto
internal data class DownloadStore(
    @ProtoNumber(1)
    val downloads: MutableMap<String, DownloadTaskStore> = mutableMapOf()
)