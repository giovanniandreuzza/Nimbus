# Nimbus — contributor guide for Claude Code

## Build & verify

```bash
./gradlew build               # full build (all targets + sample_android + nimbus-ktor)
./gradlew :nimbus:build       # core library only
./gradlew :nimbus-ktor:build  # Ktor adapter module only
```

The build must stay green at all times. Never downgrade dependency versions to fix a build.

## Project layout

```
nimbus/src/commonMain/kotlin/…nimbus/
  Nimbus.kt                          ← public entry point (Builder + init())
  presentation/
    NimbusAPI.kt                     ← public interface (all suspend, KResult returns)
    NimbusError.kt                   ← single public sealed error type
    NimbusLogEvent.kt                ← NimbusLogEvent sealed class + NimbusLogger fun interface
  core/
    application/DownloadService.kt   ← implements NimbusAPI; all business logic lives here
    application/NimbusErrorMappers.kt ← internal extension functions mapping internal errors → NimbusError
    application/dtos/DownloadTaskDTO.kt
    application/errors/              ← internal error types (DownloadError, GetFileSizeError, …)
    application/services/DownloadProgressService.kt
    domain/entities/DownloadTask.kt  ← aggregate root; owns state transitions
    domain/states/DownloadState.kt   ← sealed: Enqueued / Downloading / Paused / Failed / Finished
    domain/value_objects/            ← DownloadId, FileName, FilePath, FileUrl, FileSize
    ports/                           ← internal interfaces (DownloadPort, DownloadTaskRepository, DownloadProgressCallback)
  infrastructure/
    ports/DownloadAdapter.kt         ← HTTP download execution, semaphore concurrency, retry
    ports/IdProviderAdapter.kt       ← SHA-256(url) → stable task ID
    repositories/DownloadRepository.kt ← in-memory (Mutex-guarded) + ProtoBuf disk store
    plugins/ports/download/NimbusDownloadPort.kt  ← public interface for HTTP client
    plugins/ports/storage/NimbusStoragePort.kt    ← public interface for file I/O
    plugins/adapters/storage/FileSystemNimbusStorageAdapter.kt  ← KMP-native SystemFileSystem impl
    plugins/adapters/storage/UsableSpace.kt       ← expect/actual for platform usable-space query
  di/Module.kt                       ← wires all internal components together
  frameworks/store/StoreManager.kt   ← generic ProtoBuf persistence layer
  shared/utils/                      ← FlowUtils (takeUntil), DownloadUtils (progress calc)

nimbus-ktor/src/commonMain/kotlin/…ktor/
  KtorDownloadAdapter.kt             ← NimbusDownloadPort backed by Ktor HttpClient

sample_android/                      ← Android demo app (Koin DI, KtorDownloadAdapter)
```

## Architecture rules

- **Hexagonal / Clean Architecture**: dependencies point inward only.
  `presentation` → `core` ← `infrastructure`. Never import infrastructure from core.
- **`NimbusAPI` is the only public surface** (besides `Nimbus`, `NimbusError`,
  `TemporaryNimbusErrorCause`, `PermanentNimbusErrorCause`, `DownloadState`, `DownloadError`,
  `TemporaryDownloadErrorCause`, `PermanentDownloadErrorCause`, `GetFileSizeError`,
  `TemporaryGetFileSizeErrorCause`, `PermanentGetFileSizeErrorCause`, `DownloadTaskDTO`,
  `NimbusDownloadPort`, `NimbusStoragePort`, `NimbusLogger`, `NimbusLogEvent`).
  Everything else is `internal`.
- **`DownloadService`** is the single application service. Do not split it into use cases.
- **`DownloadTask`** owns all state-transition logic. Call `.start()`, `.pause()`, `.resume()`,
  `.fail()`, `.finish()`, `.cancel()`, `.resetToEnqueued()`, `.resetFromFailedToEnqueued()`,
  `.updateExpectedFileSize()` on the domain entity — never mutate state directly.
- **`DownloadRepository`** is the only repository class. All map accesses must be inside
  `mutex.withLock {}`.  `observeDownloadTask` is `suspend` so it can use the mutex.
- **`DownloadAdapter`** owns concurrency control (Semaphore) and retry logic. Jobs are registered
  with `CoroutineStart.LAZY` and started after the mutex-protected map insert to avoid the
  "job starts before it's registered" race.
- **`DownloadService` owns its own loading lifecycle.** A `LAZY` `Deferred` is started by
  `internal fun startLoad()` (called from `Nimbus.init()`). Every public method that touches
  the repository begins with `awaitReady()`, which suspends until loading is done and returns
  `NimbusError.PermanentError(PermanentNimbusErrorCause.InitializationFailed(...))` if it
  failed. Do not call `repository` methods before `awaitReady()` succeeds.
- **Error mapping lives in `NimbusErrorMappers.kt`**. Internal errors (`DownloadError`,
  `GetFileSizeError`) are mapped to `NimbusError` via internal extension functions there —
  do not scatter mapping logic into `DownloadService`.

## Error handling conventions

- Public API returns `KResult<T, NimbusError>`. Never throw across the public boundary.
- Internal layers use specific sealed error types (`DownloadError`, `GetFileSizeError`, etc.).
- Map internal errors to `NimbusError` in `NimbusErrorMappers.kt` using internal extension
  functions.
- `onFailure { return Failure(...) }` is the preferred early-exit pattern inside suspend functions.

## Thread safety invariants

- `DownloadRepository.tasks` and `.stateFlows` are plain `mutableMapOf` — all reads and writes
  must happen inside `mutex.withLock {}`.
- `DownloadAdapter.downloadJobs` is protected by `jobsMutex`.
- `DownloadService` uses per-task operation locks (`withOperationLock(id)`) to serialise
  concurrent calls for the same URL.

## Key design decisions to preserve

| Decision                                                                            | Why                                                                                                                                                     |
|-------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Single `NimbusError` sealed class                                                   | One import, exhaustive `when`, no leaking internal types                                                                                                |
| SHA-256 URL → task ID                                                               | Stable across sessions, no collisions in practice                                                                                                       |
| Progress updates skip disk                                                          | Hot path; disk writes only on state changes (pause/finish/fail)                                                                                         |
| `observeDownload` auto-completes on terminal state                                  | Callers don't need to manually cancel                                                                                                                   |
| `DownloadAdapter` sink opened inside coroutine                                      | Prevents `FileOutputStream` leak on scope cancel                                                                                                        |
| Single buffer (no double `.buffered()`)                                             | Double-wrap causes full file to accumulate in memory                                                                                                    |
| Singleton removed from `Builder.build()`                                            | `synchronized` unavailable in KMP common; DI container handles it                                                                                       |
| `FileSystemNimbusStorageAdapter` uses `kotlinx.io.files.SystemFileSystem`           | KMP-native; no `java.io.File`; auto-creates parent directories on `create()`                                                                            |
| Error mappers extracted to `NimbusErrorMappers.kt`                                  | Keeps `DownloadService` focused on orchestration                                                                                                        |
| `DownloadProgressCallback.onDownloadFailed` is `suspend`                            | Allows logger call (`NimbusLogger.log`) which is also suspend                                                                                           |
| `Nimbus.init()` is non-suspend                                                      | Loading starts in background via `CoroutineStart.LAZY` deferred; `awaitReady()` inside each service method gates on completion transparently to callers |
| `autoStart` fires `startDownload` as a background `launch` inside `enqueueDownload` | Non-blocking — enqueue returns the DTO immediately; start failure is emitted as `NimbusLogEvent.AutoStartFailed` and task stays `Enqueued`              |

## DownloadTask recovery methods

These are used internally during boot (`loadDownloadTasks`) and retry flows:

| Method                                   | Allowed from                   | Purpose                                                               |
|------------------------------------------|--------------------------------|-----------------------------------------------------------------------|
| `resetToEnqueued()`                      | `Finished`                     | Boot recovery when finished file is missing from disk                 |
| `resetFromFailedToEnqueued(): Boolean`   | `Failed`                       | Used by `retryFailedDownload`; returns `false` if state wasn't Failed |
| `updateExpectedFileSize(bytes): Boolean` | `Enqueued`, `Paused`, `Failed` | Updates size after re-fetching HEAD on retry                          |

## DownloadError hierarchy (internal — but reachable via DownloadState.Failed and NimbusError cause chains)

`DownloadError` has two variants:
- `TemporaryError(errorCause: TemporaryDownloadErrorCause)` — transient; the adapter retries automatically
- `PermanentError(errorCause: PermanentDownloadErrorCause)` — non-recoverable

**`TemporaryDownloadErrorCause` variants:**

| Variant                 | When                                                                           |
|-------------------------|--------------------------------------------------------------------------------|
| `ServerError(status)`   | HTTP 5xx                                                                       |
| `RangeNotSatisfiable`   | HTTP 416 — adapter truncates local file and retries from byte 0                |
| `FileIntegrityMismatch` | Downloaded size ≠ expected size; will retry                                    |
| `FileNotAccessible`     | Local file could not be opened for writing; will retry                         |
| `TruncateRace`          | Could not recreate file after 416 truncation; will retry                       |

**`PermanentDownloadErrorCause` variants:**

| Variant                            | When                                                              |
|------------------------------------|-------------------------------------------------------------------|
| `ResourceNotFound`                 | HTTP 404                                                          |
| `ClientError(statusCode)`          | HTTP 4xx (excluding 404) — non-recoverable client error           |
| `InconsistentRangeResponse(reason)`| Server returned 200 with body when 206 was expected on resume     |
| `LocalFileOversized`               | Local file is larger than the expected download size              |
| `StorageError(cause)`              | I/O or permission failure on local storage                        |
| `UnexpectedError(cause?)`          | Unhandled exception                                               |

## GetFileSizeError hierarchy (internal — used by NimbusDownloadPort)

`GetFileSizeError` has two variants:
- `TemporaryError(errorCause: TemporaryGetFileSizeErrorCause)` — transient
- `PermanentError(errorCause: PermanentGetFileSizeErrorCause)` — non-recoverable

**`TemporaryGetFileSizeErrorCause` variants:**

| Variant               | When     |
|-----------------------|----------|
| `ServerError(status)` | HTTP 5xx |

**`PermanentGetFileSizeErrorCause` variants:**

| Variant                | When                                               |
|------------------------|----------------------------------------------------|
| `ResourceNotFound`     | HTTP 404                                           |
| `ClientError(status)`  | HTTP 4xx (excluding 404)                           |
| `FileSizeUnavailable`  | No `Content-Length` in HEAD or Range probe         |
| `UnexpectedError`      | Unhandled exception                                |

## NimbusStoragePort additions

Beyond basic file I/O, the port also exposes:

- `usableSpaceBytes(path): KResult<Long, GetUsableSpaceError>` — free bytes on the volume
- `atomicMove(sourcePath, destinationPath): KResult<Unit, MoveFileError>` — atomic rename/move

`FileSystemNimbusStorageAdapter` implements both using `kotlinx.io.files.SystemFileSystem` and
platform `expect/actual` for usable-space queries (`UsableSpace.kt`).

## Platform targets

| Target                                      | Notes                                                                       |
|---------------------------------------------|-----------------------------------------------------------------------------|
| Android                                     | minSdk 21; AGP 9.x — Android config lives inside `kotlin { android { … } }` |
| JVM                                         | JVM 17                                                                      |
| iOS (iosArm64 / iosSimulatorArm64 / iosX64) | `FileSystemNimbusStorageAdapter` uses KMP-native `SystemFileSystem`         |

## Dependencies (key ones)

- `kotlinx-coroutines` — `Mutex`, `Semaphore`, `StateFlow`, `CoroutineStart.LAZY`
- `kotlinx-io` — `Source`/`Sink` streaming; **do not wrap an already-buffered sink in `.buffered()`
  again**
- `kotlinx-io-files` — `SystemFileSystem`, `Path` (replaces `java.io.File`)
- `kotlinx-serialization-protobuf` — disk persistence in `StoreManager`
- `org.kotlincrypto.hash:sha2` — SHA-256 for ID generation

## Sample app (sample_android)

Uses Koin for DI. `AppModule` calls `buildNimbusApi()` (a thin factory in
`framework/nimbus/Nimbus.kt`) which calls `Nimbus.Builder()…build().init()` and provides
`NimbusAPI` as a Koin singleton — no `runBlocking`, no `Deferred`, no wrapper class.
`MainViewModel` takes `NimbusAPI` directly (injected).
The `KtorDownloadAdapter` from `nimbus-ktor` is used as the `NimbusDownloadPort`.
The ViewModel is the canonical usage example — keep it simple and direct.
