package io.github.giovanniandreuzza.nimbus.infrastructure.models

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
internal data class DownloadStore(
    @ProtoNumber(1)
    val downloads: MutableMap<String, DownloadTaskStore> = mutableMapOf()
)