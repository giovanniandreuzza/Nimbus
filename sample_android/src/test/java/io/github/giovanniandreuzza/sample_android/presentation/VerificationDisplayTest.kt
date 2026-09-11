package io.github.giovanniandreuzza.sample_android.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the row shows after the user asks it to verify a file.
 *
 * Two ways to say something untrue. Reporting a mismatch when there is simply nothing to
 * compare against — a file downloaded before digests existed, or finished without one
 * recorded — accuses an intact file of having changed, which is exactly the conclusion this
 * feature exists to stop a caller reaching. And keeping a result on a row that has since been
 * cancelled or restarted describes a file the row no longer represents.
 */
class VerificationDisplayTest {

    @Test
    fun `nothing recorded means the comparison could not be made, not a mismatch`() {
        val result = verificationOf(onDisk = "abc", recorded = null)

        assertEquals(VerificationResult.Unavailable, result)
    }

    @Test
    fun `matching digests report a match`() {
        assertEquals(VerificationResult.Match, verificationOf(onDisk = "abc", recorded = "abc"))
    }

    @Test
    fun `differing digests report a mismatch carrying what is on disk`() {
        assertEquals(
            VerificationResult.Mismatch("abc"),
            verificationOf(onDisk = "abc", recorded = "def")
        )
    }

    @Test
    fun `a verification result survives while the row is still finished`() {
        val carried = verificationToCarry(VerificationResult.Match, DownloadDisplayState.Finished)

        assertEquals(VerificationResult.Match, carried)
    }

    @Test
    fun `a verification result is dropped once the row is no longer finished`() {
        assertNull(verificationToCarry(VerificationResult.Match, DownloadDisplayState.Idle))
        assertNull(verificationToCarry(VerificationResult.Match, DownloadDisplayState.Enqueued))
        assertNull(
            verificationToCarry(VerificationResult.Match, DownloadDisplayState.Downloading(0.5f))
        )
    }

    @Test
    fun `a verification still running is kept whatever the row is doing`() {
        assertEquals(
            VerificationResult.Running,
            verificationToCarry(VerificationResult.Running, DownloadDisplayState.Idle)
        )
    }
}
