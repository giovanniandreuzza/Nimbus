package io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadStateStoreMappers.toState
import io.github.giovanniandreuzza.nimbus.infrastructure.repositories.mappers.DownloadStateStoreMappers.toStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A `Failed` state is not persisted as its typed cause. It is flattened to an error code and
 * a human-readable message and rebuilt by switching on the code, so every cause needs a branch
 * in that switch or it silently comes back as something else — and the `else` here is
 * `FileNotAccessible`, a cause the adapter retries. A cause added to the sealed class without
 * a branch is therefore not a compile error and not a visible one either: it survives the
 * write and is wrong on the way back.
 */
class DownloadStateStoreMappersTest {

    @Test
    fun `every temporary cause survives a round trip through the store`() {
        val causes = listOf(
            TemporaryDownloadErrorCause.ServerError(503),
            TemporaryDownloadErrorCause.RangeNotSatisfiable,
            TemporaryDownloadErrorCause.FileIntegrityMismatch,
            TemporaryDownloadErrorCause.FileNotAccessible,
            TemporaryDownloadErrorCause.TruncateRace,
            TemporaryDownloadErrorCause.ChecksumMismatch,
            TemporaryDownloadErrorCause.TransportFailure(
                TemporaryDownloadErrorCause.FileNotAccessible
            )
        )

        for (cause in causes) {
            val restored = DownloadState.Failed(DownloadError.TemporaryError(cause))
                .toStore()
                .toState()

            val failed = restored as? DownloadState.Failed
                ?: fail("expected Failed for ${cause.code}, got $restored")
            val temporary = failed.error as? DownloadError.TemporaryError
                ?: fail("${cause.code} came back as ${failed.error}")

            assertEquals(
                cause.code,
                temporary.errorCause.code,
                "${cause.code} was rebuilt as ${temporary.errorCause.code}"
            )
        }
    }

    @Test
    fun `every permanent cause survives a round trip through the store`() {
        // The temporary family had this and the permanent one did not, which is how a new
        // cause reached a pull request with no branch in the mapper: it came back as
        // UnexpectedError, so a task that failed for a reason the caller could act on was
        // rebuilt after a restart as one they could not.
        val causes = listOf(
            PermanentDownloadErrorCause.ResourceNotFound,
            PermanentDownloadErrorCause.ClientError(404),
            PermanentDownloadErrorCause.InconsistentRangeResponse("no Content-Range"),
            PermanentDownloadErrorCause.LocalFileOversized,
            PermanentDownloadErrorCause.InsufficientDiskSpace(
                KError("insufficient_disk_space", "needed 10 more bytes, the volume had 0")
            ),
            PermanentDownloadErrorCause.StorageError(KError("io", "the disk is on fire")),
            PermanentDownloadErrorCause.UnexpectedError(KError("boom", "something"))
        )

        for (cause in causes) {
            val restored = DownloadState.Failed(DownloadError.PermanentError(cause))
                .toStore()
                .toState()

            val failed = restored as? DownloadState.Failed
                ?: fail("expected Failed for ${cause.code}, got $restored")
            val permanent = failed.error as? DownloadError.PermanentError
                ?: fail("${cause.code} came back as ${failed.error}")

            assertEquals(
                cause.code,
                permanent.errorCause.code,
                "${cause.code} was rebuilt as ${permanent.errorCause.code}"
            )
        }
    }

    @Test
    fun `a server error keeps its status code across the round trip`() {
        val restored = DownloadState.Failed(
            DownloadError.TemporaryError(TemporaryDownloadErrorCause.ServerError(503))
        ).toStore().toState()

        val cause = ((restored as DownloadState.Failed).error as DownloadError.TemporaryError)
            .errorCause
        assertTrue(
            cause is TemporaryDownloadErrorCause.ServerError && cause.statusCode == 503,
            "the status code is parsed back out of the message text, got $cause"
        )
    }
}
