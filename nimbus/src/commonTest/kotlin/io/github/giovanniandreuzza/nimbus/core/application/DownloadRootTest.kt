package io.github.giovanniandreuzza.nimbus.core.application

import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.infrastructure.ports.StorageAdapter
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusError
import io.github.giovanniandreuzza.nimbus.presentation.PermanentNimbusErrorCause
import io.github.giovanniandreuzza.nimbus.testing.FakeClock
import io.github.giovanniandreuzza.nimbus.testing.FakeContentDigestPort
import io.github.giovanniandreuzza.nimbus.testing.FakeDownloadTaskRepository
import io.github.giovanniandreuzza.nimbus.testing.InMemoryStorage
import io.github.giovanniandreuzza.nimbus.testing.RecordingLogger
import io.github.giovanniandreuzza.nimbus.testing.ScriptedDownloadPort
import io.github.giovanniandreuzza.nimbus.testing.UrlAsIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Where the library is allowed to write.
 *
 * On the devices this is built for, the destination is not a constant in the app: it comes
 * from the manifest the backend serves, which makes it input. The only check it ever faced was
 * "contains no `..`", so an absolute path naming the app's own database was accepted and
 * written to — a compromised backend, or a manifest over plain HTTP with someone in the
 * middle, is all that takes.
 *
 * A root is opt-in because a library cannot know where an app keeps its files. Once set, it is
 * the answer to "may I write here", and the answer is lexical: paths are normalised and
 * compared. A symlink under the root pointing elsewhere still leads elsewhere, and no check
 * that runs before the file exists can see that.
 */
class DownloadRootTest {

    @Test
    fun `a path under the root is accepted`() = runTest {
        val f = fixture(root = ROOT)

        val result = f.service.enqueueDownload(URL, "$ROOT/videos/clip.mp4", NAME)

        assertTrue(result is Success, "got $result")
    }

    @Test
    fun `a path somewhere else is refused and says where it was pointing`() = runTest {
        val f = fixture(root = ROOT)

        val result = f.service.enqueueDownload(URL, "/data/data/app/databases/app.db", NAME)

        val cause = result.causeOrFail()
        assertTrue(
            cause is PermanentNimbusErrorCause.PathOutsideDownloadRoot,
            "got $cause"
        )
        assertEquals("/data/data/app/databases/app.db", cause.filePath)
        assertEquals(ROOT, cause.downloadRoot)
    }

    @Test
    fun `a path that climbs back out is refused`() = runTest {
        // Refused twice over: `..` is not a path component this library opens at all, and the
        // normalised result is outside the root anyway.
        val f = fixture(root = ROOT)

        val result = f.service.enqueueDownload(URL, "$ROOT/../secrets/key.pem", NAME)

        assertTrue(result is Failure, "got $result")
    }

    @Test
    fun `a sibling directory whose name merely starts with the root is refused`() = runTest {
        // "/files/nimbus-backup" starts with "/files/nimbus" as a string and is not under it.
        val f = fixture(root = ROOT)

        val result = f.service.enqueueDownload(URL, "$ROOT-backup/clip.mp4", NAME)

        assertTrue(
            result.causeOrFail() is PermanentNimbusErrorCause.PathOutsideDownloadRoot,
            "prefix matching is not containment"
        )
    }

    @Test
    fun `redundant separators and dots do not sneak a path past the check`() = runTest {
        val f = fixture(root = ROOT)

        val result = f.service.enqueueDownload(URL, "$ROOT//./videos/./clip.mp4", NAME)

        assertTrue(result is Success, "the same path, spelled awkwardly: got $result")
    }

    @Test
    fun `a control character in the path is refused whether or not a root is set`() = runTest {
        // A NUL truncates the path at the platform boundary on most systems, so what was
        // checked and what is opened stop being the same string.
        val f = fixture(root = null)

        val result = f.service.enqueueDownload(URL, "/files/nimbus/clip\u0000.mp4", NAME)

        assertTrue(result.causeOrFail() is PermanentNimbusErrorCause.InvalidPath, "got $result")
    }

    @Test
    fun `with no root configured any path outside is still allowed`() = runTest {
        // The default has to stay what it was: a library that started refusing paths it used
        // to accept would break every caller on upgrade.
        val f = fixture(root = null)

        val result = f.service.enqueueDownload(URL, "/somewhere/else/clip.mp4", NAME)

        assertTrue(result is Success, "got $result")
    }

    private fun TestScope.fixture(root: String?): Fixture {
        val storage = InMemoryStorage()
        val service = DownloadService(
            idProvider = UrlAsIdProvider,
            downloadPort = ScriptedDownloadPort(remoteSize = 64L),
            repository = FakeDownloadTaskRepository(),
            storagePort = StorageAdapter(storage),
            contentDigestPort = FakeContentDigestPort(
                Success(Checksum.of(DigestAlgorithm.SHA256, "0".repeat(64)))
            ),
            clock = FakeClock(),
            downloadRoot = root,
            digestAlgorithm = null,
            minReservedDiskBytes = null,
            logger = RecordingLogger(),
            autoStart = false,
            ownsDownloadScope = false,
            downloadScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        )
        return Fixture(service)
    }

    private class Fixture(val service: DownloadService)

    private fun <T> io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult<T, NimbusError>.causeOrFail(): PermanentNimbusErrorCause =
        when (this) {
            is Failure -> (error as? NimbusError.PermanentError)?.errorCause
                ?: fail("expected a permanent error, got $error")

            is Success -> fail("expected a failure, got $value")
        }

    private companion object {
        const val ROOT = "/files/nimbus"
        const val URL = "https://example.com/clip.mp4"
        const val NAME = "clip.mp4"
    }
}
