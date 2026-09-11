package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.models.storage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The persisted blob must not depend on where the classes live: sealed-class
 * discriminators default to the fully-qualified class name, so a package move
 * silently invalidates every store already written on a device.
 */
@OptIn(ExperimentalSerializationApi::class)
class DownloadStoreSchemaTest {

    @Test
    fun `state discriminators are stable names, not class names`() {
        val encoded = ProtoBuf.encodeToByteArray(storeWithPausedTask())
        val text = encoded.decodeToString()

        assertTrue(
            text.contains("nimbus.state.paused"),
            "expected a stable discriminator in the encoded store"
        )
        assertFalse(
            text.contains("io.github.giovanniandreuzza"),
            "encoded store must not embed class names"
        )
    }

    @Test
    fun `store round-trips through protobuf`() {
        val store = storeWithPausedTask()

        val decoded = ProtoBuf.decodeFromByteArray<DownloadStore>(
            ProtoBuf.encodeToByteArray(store)
        )

        assertEquals(store, decoded)
    }

    private fun storeWithPausedTask() = DownloadStore(
        downloads = mapOf(
            "task-1" to DownloadTaskStore(
                id = "task-1",
                fileName = "screens.apk",
                fileUrl = "https://example.com/screens.apk",
                filePath = "/files/installer/screens.apk",
                fileSize = 1_024L,
                state = DownloadStateStore.Paused(progress = 12.5)
            )
        )
    )
}
