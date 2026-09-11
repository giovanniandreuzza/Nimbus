package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.nimbus.core.ports.CreateOutcome
import io.github.giovanniandreuzza.nimbus.core.ports.DeleteOutcome
import io.github.giovanniandreuzza.nimbus.core.ports.StoragePortError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetUsableSpaceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.LocalFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The adapter's whole job is translation: plugin vocabulary in, the four operations and
 * three outcomes core reasons about out.
 *
 * The outcomes matter most. Each one used to be a failure every call site had to remember
 * to exempt — `if (it !is DeleteFileError.FileNotFound) return Failure(...)` — written by
 * hand four times, correct four times, and one omission away from turning a file that was
 * already absent into a user-visible permanent error.
 */
class StorageAdapterTest {

    @Test
    fun `a file that already exists is a successful create`() {
        val adapter = StorageAdapter(
            FakeStoragePort(create = Failure(CreateFileError.FileAlreadyExists))
        )

        val result = adapter.create(PATH)

        assertIs<Success<CreateOutcome>>(result)
        assertEquals(CreateOutcome.AlreadyExists, result.value)
    }

    @Test
    fun `a file that was already gone is a successful delete`() {
        val adapter = StorageAdapter(FakeStoragePort(delete = Failure(DeleteFileError.FileNotFound)))

        val result = adapter.delete(PATH)

        assertIs<Success<DeleteOutcome>>(result)
        assertEquals(DeleteOutcome.NotFound, result.value)
    }

    @Test
    fun `a platform that cannot report free space answers null rather than failing`() {
        val adapter = StorageAdapter(
            FakeStoragePort(usableSpace = Failure(GetUsableSpaceError.Unsupported))
        )

        val result = adapter.usableSpaceBytes(PATH)

        assertIs<Success<Long?>>(result)
        assertNull(result.value, "Unsupported means unknown, not zero and not a failure")
    }

    @Test
    fun `every other create failure is a failure`() {
        val cause = KError("disk", "disk on fire")
        val adapter = StorageAdapter(
            FakeStoragePort(create = Failure(CreateFileError.IOError(cause)))
        )

        val result = adapter.create(PATH)

        assertIs<Failure<StoragePortError>>(result)
        assertEquals(cause, result.error.cause.cause, "the plugin's own error must survive")
    }

    @Test
    fun `every other delete failure is a failure`() {
        val adapter = StorageAdapter(
            FakeStoragePort(delete = Failure(DeleteFileError.DeleteFailed))
        )

        assertIs<Failure<StoragePortError>>(adapter.delete(PATH))
    }

    @Test
    fun `a usable space failure that is not Unsupported is a failure`() {
        val adapter = StorageAdapter(
            FakeStoragePort(
                usableSpace = Failure(GetUsableSpaceError.IoFailed(KError("io", "no")))
            )
        )

        assertIs<Failure<StoragePortError>>(adapter.usableSpaceBytes(PATH))
    }

    @Test
    fun `a size failure is a failure and a size is passed through`() {
        val passed = StorageAdapter(FakeStoragePort(size = Success(42L))).size(PATH)
        assertIs<Success<Long>>(passed)
        assertEquals(42L, passed.value)

        val failing = StorageAdapter(FakeStoragePort(size = Failure(LocalFileSizeError.FileNotFound)))
        assertIs<Failure<StoragePortError>>(failing.size(PATH))
    }

    private companion object {
        const val PATH = "/tmp/nimbus/file"
    }
}

private class FakeStoragePort(
    private val size: KResult<Long, LocalFileSizeError> = Success(0L),
    private val create: KResult<Unit, CreateFileError> = Success(Unit),
    private val delete: KResult<Unit, DeleteFileError> = Success(Unit),
    private val usableSpace: KResult<Long, GetUsableSpaceError> = Success(0L)
) : NimbusStoragePort {

    override fun size(path: String): KResult<Long, LocalFileSizeError> = size
    override fun create(path: String): KResult<Unit, CreateFileError> = create
    override fun delete(path: String): KResult<Unit, DeleteFileError> = delete

    override fun usableSpaceBytes(path: String): KResult<Long, GetUsableSpaceError> = usableSpace

    override fun exists(path: String): KResult<Boolean, DoesFileExistError> = Success(true)

    override fun source(path: String): KResult<Source, GetFileSourceError> =
        Failure(GetFileSourceError.FileNotFound)

    override fun sink(path: String, hasToAppend: Boolean): KResult<Sink, GetFileSinkError> =
        Failure(GetFileSinkError.FileNotFound)

    override fun atomicMove(
        sourcePath: String,
        destinationPath: String
    ): KResult<Unit, MoveFileError> = Failure(MoveFileError.FileNotFound)
}
