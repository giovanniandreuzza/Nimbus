package io.github.giovanniandreuzza.nimbus.infrastructure.plugins.adapters.storage

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DoesFileExistError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSourceError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.LocalFileSizeError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.MoveFileError
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * A failure the adapter cannot classify must be reported as unclassified.
 *
 * Every method used to end `catch (t: Throwable) -> …PermissionDenied(…)`, so anything that
 * was not an `IOException` reached the caller as a permission denial — a `SecurityException`,
 * a platform-specific filesystem error, an `OutOfMemoryError`. A caller acting on that
 * diagnosis acts on a false one.
 */
class FileSystemNimbusStorageAdapterTest {

    @Test
    fun `an unclassifiable failure is not reported as a permission denial`() {
        val adapter = FileSystemNimbusStorageAdapter(AlwaysThrowingFileSystem)

        assertIs<DoesFileExistError.UnexpectedError>(adapter.exists(PATH).errorOrFail())
        assertIs<MoveFileError.UnexpectedError>(adapter.atomicMove(PATH, OTHER_PATH).errorOrFail())
    }

    @Test
    fun `failures behind a successful existence check are reported as unclassified`() {
        // These methods consult exists() first, so the fake answers that honestly and fails
        // only the operation under test.
        val present = FileSystemNimbusStorageAdapter(ThrowingButPresentFileSystem)

        assertIs<LocalFileSizeError.UnexpectedError>(present.size(PATH).errorOrFail())
        assertIs<GetFileSourceError.UnexpectedError>(present.source(PATH).errorOrFail())
        assertIs<DeleteFileError.UnexpectedError>(present.delete(PATH).errorOrFail())
        assertIs<GetFileSinkError.UnexpectedError>(
            present.sink(PATH, hasToAppend = false).errorOrFail()
        )

        // create() is the mirror image: it requires the file to be absent.
        val absent = FileSystemNimbusStorageAdapter(ThrowingButAbsentFileSystem)
        assertIs<CreateFileError.UnexpectedError>(absent.create(PATH).errorOrFail())
    }

    private fun <T, E : KError> KResult<T, E>.errorOrFail(): E {
        assertIs<Failure<E>>(this)
        return error
    }

    private companion object {
        const val PATH = "/tmp/nimbus-adapter/file"
        const val OTHER_PATH = "/tmp/nimbus-adapter/other"
    }
}

private fun boom(): Nothing = throw IllegalStateException("filesystem unavailable")

/** Fails every call with something that is not a [kotlinx.io.IOException]. */
private object AlwaysThrowingFileSystem : NimbusFileSystem {
    override fun exists(path: Path): Boolean = boom()
    override fun delete(path: Path, mustExist: Boolean): Unit = boom()
    override fun createDirectories(path: Path): Unit = boom()
    override fun atomicMove(source: Path, destination: Path): Unit = boom()
    override fun source(path: Path): RawSource = boom()
    override fun sink(path: Path, append: Boolean): RawSink = boom()
    override fun metadataOrNull(path: Path): FileMetadata = boom()
}

/** Reports the file as present, then fails whatever is done with it. */
private object ThrowingButPresentFileSystem : NimbusFileSystem by AlwaysThrowingFileSystem {
    override fun exists(path: Path): Boolean = true
}

/** Reports the file as absent, then fails whatever is done with it. */
private object ThrowingButAbsentFileSystem : NimbusFileSystem by AlwaysThrowingFileSystem {
    override fun exists(path: Path): Boolean = false
}
