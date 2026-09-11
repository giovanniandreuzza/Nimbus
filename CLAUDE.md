# Nimbus — contributor guide for Claude Code

## Build & verify

```bash
./gradlew build               # full build (all targets + sample_android + nimbus-ktor)
./gradlew :nimbus:build       # core library only
./gradlew :nimbus-ktor:build  # Ktor adapter module only
```

The build must stay green at all times. Never downgrade dependency versions to fix a build.

Tests live in two source sets. `commonTest` is the default and runs on JVM, iOS and Android host;
put a test there unless it genuinely cannot run on all three. `jvmTest` is for what is
platform-bound: the architecture source scan, the real-filesystem adapter, the concurrency test
that wants real threads, and the digest test that checks the library's SHA-256 against the JVM's
own (an independent oracle is the point of that one). `testing/Fakes.kt` and
`testing/InMemoryStorage.kt` are what let a test avoid a temp directory.

## Project layout

```
nimbus/src/commonMain/kotlin/…nimbus/
  Nimbus.kt                          ← public entry point (Builder + init())
  presentation/
    NimbusAPI.kt                     ← public interface (all suspend, KResult returns)
    NimbusError.kt                   ← single public sealed error type
    NimbusLogEvent.kt                ← NimbusLogEvent sealed class + NimbusLogger fun interface
    Checksum.kt                      ← public Checksum + DigestAlgorithm
  core/
    application/DownloadService.kt   ← implements NimbusAPI; all business logic lives here
    application/NimbusErrorMappers.kt ← internal extension functions mapping internal errors → NimbusError
    application/dtos/DownloadTaskDTO.kt
    application/errors/              ← internal error types (DownloadError, GetFileSizeError, …)
    application/services/DownloadProgressService.kt
    domain/entities/DownloadTask.kt  ← aggregate root; owns state transitions
    domain/states/DownloadState.kt   ← sealed: Enqueued / Downloading / Paused / Failed / Finished
    domain/value_objects/            ← DownloadId, FileName, FilePath, FileUrl, FileSize
    ports/                           ← internal interfaces core depends on:
      DownloadPort.kt                ←   download execution (impl: DownloadAdapter)
      StoragePort.kt                 ←   size/create/delete/usableSpaceBytes (impl: StorageAdapter)
      StoragePortError.kt            ←   core-side storage error family + CreateOutcome/DeleteOutcome
      ContentDigestPort.kt           ←   digest accumulation (impl: ContentDigestAdapter)
      DownloadTaskRepository.kt, DownloadProgressCallback.kt, IdProviderPort.kt
  infrastructure/
    ports/DownloadAdapter.kt         ← HTTP download execution, semaphore concurrency, retry
    ports/StorageAdapter.kt          ← implements StoragePort over NimbusStoragePort
    ports/ContentDigestAdapter.kt    ← implements ContentDigestPort over ContentDigest
    ports/IdProviderAdapter.kt       ← SHA-256(url) → stable task ID
    digest/ContentDigest.kt          ← streaming digest accumulator
    repositories/DownloadRepository.kt ← in-memory (Mutex-guarded) + ProtoBuf disk store
    plugins/ports/download/NimbusDownloadPort.kt  ← public interface for HTTP client
    plugins/ports/storage/NimbusStoragePort.kt    ← public interface for file I/O
    plugins/adapters/storage/FileSystemNimbusStorageAdapter.kt  ← KMP-native impl
    plugins/adapters/storage/NimbusFileSystem.kt  ← internal seam over SystemFileSystem (testability)
    plugins/adapters/storage/UsableSpace.kt       ← expect/actual for platform usable-space query
  di/Module.kt                       ← wires all internal components together
  frameworks/store/StoreManager.kt   ← generic ProtoBuf persistence layer
  shared/utils/                      ← FlowUtils (takeUntil), DownloadUtils (progress calc)

nimbus/src/commonTest/kotlin/…nimbus/   ← runs on JVM, iOS and Android host
  testing/Fakes.kt                   ← fake ports (no filesystem, no network)
  testing/InMemoryStorage.kt         ← in-memory NimbusStoragePort

nimbus/src/jvmTest/kotlin/…nimbus/      ← only what is genuinely platform-bound
  ArchitectureTest.kt                ← source scan: no core/ import of infrastructure
  FileSystemNimbusStorageAdapterTest.kt, ContentDigestResumeTest.kt,
  DownloadRepository{Observability,Persistence}Test.kt, DownloadStoreWriteAmplificationTest.kt

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
  `NimbusDownloadPort`, `NimbusStoragePort`, `NimbusLogger`, `NimbusLogEvent`,
  `Checksum`, `DigestAlgorithm`).
  Everything else is `internal`.
- **`DownloadService`** is the single application service. Do not split it into use cases.
- **`DownloadTask`** owns all state-transition logic. Call `.start()`, `.pause()`, `.resume()`,
  `.fail()`, `.finish()`, `.cancel()`, `.resetToEnqueued()`, `.resetFromFailedToEnqueued()`,
  `.updateExpectedFileSize()` on the domain entity — never mutate state directly.
- **`DownloadRepository`** is the only repository class. All map accesses must be inside
  `mutex.withLock {}`.  `observeDownloadTask` is `suspend` so it can use the mutex.
  The hot path publishes a monotonic `revision` counter and nothing else — never a copy of
  the task map. `DownloadTask` is an `Entity` whose `equals` is identity on the id, so a
  `StateFlow` holding tasks conflates every state change away; and copying the map on each
  progress tick allocates proportionally to the catalogue whether or not anyone observes.
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
- **Core never imports infrastructure.** `core/ports/StoragePort` (four methods: `size`,
  `create`, `delete`, `usableSpaceBytes`) and `core/ports/ContentDigestPort` are what core
  depends on; `StorageAdapter` and `ContentDigestAdapter` translate to `NimbusStoragePort`.
  `ArchitectureTest` enforces this. Components outside core (`DownloadAdapter`,
  `StoreManager`, `DownloadRepository`) use the plugin port directly, which is fine.
- **Benign storage outcomes are success values**, not errors to exempt: `CreateOutcome`,
  `DeleteOutcome`, and a null `usableSpaceBytes` for a platform that cannot answer.

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
- `StoreManager.data` is `private set`. Subclasses may read it, but deriving a new value from it
  and persisting that must go through `update()` (durable) or `mutate()` + `flush()` (coalesced) —
  all three do the read-modify-write under the store lock. Reading `data`, deriving, then calling
  a separate write is the shape that let two concurrent saves drop each other's work.

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
| `FileSystemNimbusStorageAdapter` goes through the `NimbusFileSystem` seam, never `SystemFileSystem` directly | `kotlinx.io`'s `FileSystem` is sealed and cannot be substituted in a test, which is how every catch-all in the adapter came to report unrelated failures as permission denials and survived review |
| Storage catch-alls map to each family's `UnexpectedError`, never to a named cause  | A caller acting on "permission denied" for a `SecurityException` or an `OutOfMemoryError` acts on a false diagnosis                                     |
| `DownloadAdapter` classifies its own transfer failures and lets none escape into the port implementation | The callback runs while the response is open, so it sits inside the implementation's `try`, where a dead socket and a full disk are the same type. Settling it in the library means every `NimbusDownloadPort` gets it right without solving it again — and `KtorDownloadAdapter` can map every `IOException` to `TemporaryError` safely |
| `retryFailedDownload` keeps the partial only for a transport failure at unchanged remote size | Discarding it restarts a large transfer from zero on every drop. The whitelist is deliberate: a `ChecksumMismatch` leaves a file of the right length and the wrong content, and resuming into it never converges |
| A new `Temporary`/`Permanent` cause needs a branch in `DownloadStateStoreMappers` | The store flattens a cause to its code and rebuilds it by switching on that code. A missing branch is not a compile error: the state persists and comes back as something else (the `else` is `FileNotAccessible`, which the adapter retries) |
| Error mappers extracted to `NimbusErrorMappers.kt`                                  | Keeps `DownloadService` focused on orchestration                                                                                                        |
| `DownloadProgressCallback.onDownloadFailed` is `suspend`                            | Allows logger call (`NimbusLogger.log`) which is also suspend                                                                                           |
| `Nimbus.init()` is non-suspend                                                      | Loading starts in background via `CoroutineStart.LAZY` deferred; `awaitReady()` inside each service method gates on completion transparently to callers |
| `autoStart` fires `startDownload` as a background `launch` inside `enqueueDownload` | Non-blocking — enqueue returns the DTO immediately; start failure is emitted as `NimbusLogEvent.AutoStartFailed` and task stays `Enqueued`              |
| Store commits coalesce for non-terminal states                                      | A commit rewrites every task; committing each transition makes one download's cost grow with the catalogue. Terminal states stay durable before the save returns |
| A failed coalesced flush is reported as `NimbusLogEvent.StoreFlushFailed`           | The flush runs in the background, so its caller is already gone and there is no `KResult` to return it in. A terminal save reports to the caller and must *not* also log |
| `DownloadRepository` publishes a revision counter, not a copy of the task map       | `DownloadTask` is an `Entity` whose `equals` is identity on the id, so a map of the same tasks in new states compares equal and `StateFlow` conflates the emission away — `observeAllDownloads()` emitted only additions and removals |
| Digest primed from disk at the start of every streaming attempt                     | The resume offset comes from the file's length, so a session-only digest would hash the tail alone after a restart and produce a plausible wrong value  |
| `schemaVersion` defaults to a value no build writes                                 | ProtoBuf omits values equal to their default, so a stamp defaulting to "current" never reaches the disk and every old store claims to be current        |
| `TemporaryDownloadErrorCause.ChecksumMismatch` is temporary, never permanent        | A mismatch describes the transfer, not the file at the origin; marking it permanent sends the caller back to delete-and-refetch                          |

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
| `ChecksumMismatch`      | Transferred bytes did not match the caller's `expectedChecksum`; will retry     |
| `TransportFailure(cause)` | Timeout, connection reset, DNS or TLS failure; will retry                       |
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

| Variant                 | When                                   |
|-------------------------|----------------------------------------|
| `ServerError(status)`   | HTTP 5xx                               |
| `TransportFailure(cause)` | Timeout, reset, DNS or TLS failure     |

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
