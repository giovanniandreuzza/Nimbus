package io.github.giovanniandreuzza.nimbus.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic behind `withDownloadRoot`.
 *
 * It decides whether a path from a manifest may be written to, before anything exists on disk
 * to ask. Everything it gets wrong is either a download refused for no reason or a write
 * somewhere it should not be, so the awkward spellings are the point: a path is a string until
 * it is normalised, and `"/files/nimbus-backup"` starts with `"/files/nimbus"`.
 */
class PathUtilsTest {

    @Test
    fun `normalising collapses what does not change where a path points`() {
        assertEquals("/files/nimbus/clip.mp4", "/files/nimbus/clip.mp4".normalizedPath())
        assertEquals("/files/nimbus/clip.mp4", "/files//nimbus/./clip.mp4".normalizedPath())
        assertEquals("/files/nimbus", "/files/nimbus/".normalizedPath())
        assertEquals("/files/nimbus", "/files/videos/../nimbus".normalizedPath())
        assertEquals("/", "/".normalizedPath())
    }

    @Test
    fun `a windows separator is a separator`() {
        // kotlinx.io writes them on Windows, and a path that mixes both is what a config file
        // pasted between machines looks like.
        assertEquals("/files/nimbus/clip.mp4", "\\files\\nimbus\\clip.mp4".normalizedPath())
        assertEquals("/files/nimbus/clip.mp4", "/files\\nimbus/clip.mp4".normalizedPath())
    }

    @Test
    fun `an absolute path cannot climb above the root`() {
        assertEquals("/files", "/files/nimbus/..".normalizedPath())
        assertEquals("/", "/../..".normalizedPath(), "there is nothing above /")
    }

    @Test
    fun `a relative path may genuinely start above itself`() {
        assertEquals("../secrets", "../secrets".normalizedPath())
        assertEquals("../..", "../../videos/..".normalizedPath())
    }

    @Test
    fun `containment is about segments and not about prefixes`() {
        assertTrue("/files/nimbus/clip.mp4".isInside("/files/nimbus"))
        assertTrue("/files/nimbus/a/b/c.bin".isInside("/files/nimbus"))
        assertTrue("/files/nimbus".isInside("/files/nimbus"), "the root itself is inside it")
        assertTrue("/files/nimbus/clip.mp4".isInside("/files/nimbus/"), "a trailing slash is noise")

        assertFalse(
            "/files/nimbus-backup/clip.mp4".isInside("/files/nimbus"),
            "it shares a prefix and is a different directory"
        )
        assertFalse("/files/other/clip.mp4".isInside("/files/nimbus"))
        assertFalse("/files/nimbus/../other/clip.mp4".isInside("/files/nimbus"))
    }
}
