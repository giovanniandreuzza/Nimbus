# Nimbus

> [!TIP]
> Want to chat live with me? Join me on [Discord server](https://discord.gg/EBXRXPRD).

Nimbus is a Kotlin Multiplatform download manager for **Android, JVM and iOS**, built for transfers
that have to survive the conditions they actually run in: a link that drops halfway, a process
killed mid-download, a volume that fills up, a device that comes back a day later and should not
start from zero.

You bring the HTTP client and choose where files land. Nimbus owns the state machine, resume,
retries, the concurrency limit and persistence. Every failure comes back as a typed value you can
match on exhaustively — nothing is thrown across the public boundary.

**Contents** · [Install](#install) · [Quickstart](#quickstart) · [Using the API](#using-the-api) ·
[API reference](#api-reference) · [Configuration](#configuration) · [Errors](#handling-errors) ·
[Limitations](#what-nimbus-does-not-do)

---

## Install

<!-- x-release-please-start-version -->
```kotlin
dependencies {
    implementation("io.github.giovanniandreuzza:nimbus:2.3.0")
    // Optional but recommended — saves you implementing NimbusDownloadPort yourself:
    implementation("io.github.giovanniandreuzza:nimbus-ktor:2.3.0")
}
```
<!-- x-release-please-end -->

## Quickstart

Build once, keep the result — it is the whole API.

```kotlin
val nimbus: NimbusAPI = Nimbus.Companion.Builder()
    .withNimbusDownloadPort(KtorDownloadAdapter(httpClient))
    .withDownloadManagerPath("/path/to/metadata/store")
    .build()
    .init()
```

`build()` and `init()` do not suspend. Loading the persisted tasks starts in the background, and
the first API call waits for it if it has not finished.

Then download a file. `ensureDownloaded` is the one call that covers the whole lifecycle — it
enqueues, starts, resumes a paused task, retries a failed one, or returns immediately if the file
is already there:

```kotlin
// Inside a coroutine.
nimbus.ensureDownloaded(
    fileUrl = "https://example.com/video.mp4",
    filePath = "/path/to/downloads",
    fileName = "video.mp4"
).getOr { error ->
    return  // error is a NimbusError — see Handling errors
}.collect { state ->
    when (state) {
        DownloadState.Enqueued       -> showWaiting()
        is DownloadState.Downloading -> showProgress(state.progress)   // 0.0 – 100.0
        is DownloadState.Paused      -> showPaused(state.progress)
        is DownloadState.Failed      -> showError(state.error)
        DownloadState.Finished       -> showDone()
        DownloadState.Cancelled      -> showCancelled()
    }
}
```

The flow completes on its own when the download finishes or fails. There is nothing to cancel.

**On Android**, use `withAndroidContext(context)` instead of `withDownloadManagerPath(...)` and the
store is placed in your app's files directory for you.

## Using the API

### Drive a download step by step

`ensureDownloaded` is a convenience. When you want the steps yourself:

```kotlin
nimbus.enqueueDownload(url, filePath, fileName).getOr { return }  // registers it, fetches size
nimbus.startDownload(url).getOr { return }                        // begins the transfer
nimbus.observeDownload(url).getOr { return }.collect { state -> render(state) }
```

Every method takes the **file URL** as its identity — there are no handles or ids to keep.

### Pause and resume

```kotlin
nimbus.pauseDownload(url)
// later, even after the app restarted:
nimbus.resumeDownload(url)
```

Progress is persisted, so a resume continues from the bytes already on disk rather than starting
over — including after the process was killed.

### Cancel or remove

```kotlin
nimbus.cancelDownload(url)                                  // stops it, deletes the partial file
nimbus.removeDownload(url)                                  // forgets the task, keeps the file
nimbus.removeDownload(url, deleteAssociatedFile = true)     // forgets it and deletes the file
```

`cancelDownload` works from any state. `removeDownload` only from `Finished` or `Failed`.

### Retry something that failed

```kotlin
nimbus.retryFailedDownload(url).getOr { return }   // re-checks the remote size, resets to Enqueued
nimbus.startDownload(url)
```

Transient failures — a timeout, a reset connection, an HTTP 5xx — are retried inside the library
already, according to `withMaxRetryAttempts` and `withRetryBaseDelayMs`. This call is for a task
that has given up.

### Show a list of downloads

```kotlin
nimbus.observeAllDownloads().collect { tasks ->
    render(tasks)   // every task, re-emitted on every change including progress
}
```

This never completes, so collect it in a scope you control. For a one-off read use
`getAllDownloads()`.

### Verify what you downloaded

Opt in on the builder with `withContentDigest(DigestAlgorithm.SHA256)`. The bytes are hashed in the
pass that already writes them, so it costs the hash and no extra read.

```kotlin
// Verify the transfer against a digest your backend published.
val expected = Checksum.of(DigestAlgorithm.SHA256, shaFromYourBackend)
nimbus.ensureDownloaded(url, filePath, fileName, expectedChecksum = expected)

// Ask later whether the file still holds the bytes it arrived with.
when (val result = nimbus.checksum(url)) {
    is Success -> if (result.value == expected) {
        // Intact — whatever went wrong is not the file. Do NOT re-download.
    } else {
        nimbus.removeDownload(url, deleteAssociatedFile = true)   // the bytes really did change
    }
    is Failure -> log(result.error)
}
```

That last distinction is the point: a file whose bytes changed and a file that is intact but
unusable look identical to a caller that only sees "it did not work". Treating the second as the
first means deleting and re-downloading a perfectly good file forever.

## API reference

Every method is `suspend` and returns `KResult<T, NimbusError>` unless noted.

| Method | Does |
|---|---|
| `ensureDownloaded(url, path, name, expectedChecksum?)` | Whole lifecycle; returns `Flow<DownloadState>` |
| `enqueueDownload(url, path, name, expectedChecksum?)` | Registers a task and fetches its size |
| `startDownload(url)` | Begins the transfer |
| `pauseDownload(url)` / `resumeDownload(url)` | Pause and continue |
| `cancelDownload(url)` | Stop and delete the partial file |
| `retryFailedDownload(url)` | Reset a failed task to `Enqueued` |
| `removeDownload(url, deleteAssociatedFile = false)` | Forget a finished or failed task |
| `observeDownload(url)` | `Flow<DownloadState>`; completes on `Finished` or `Failed` |
| `observeAllDownloads()` | *Not suspend.* `Flow<List<DownloadTaskDTO>>`; never completes |
| `getDownloadTask(url)` / `getAllDownloads()` | One task, or all of them |
| `getFileSize(url)` | Remote size without downloading |
| `isDownloaded(url)` | *Returns `Boolean`.* Finished **and** the file is on disk |
| `checksum(url)` | Re-hashes the finished file from disk |

## Configuration

Everything except the download port and the store location has a working default.

| Builder option | Default | For |
|---|---|---|
| `withNimbusDownloadPort(port)` | — **required** | Your HTTP client, or `KtorDownloadAdapter` |
| `withDownloadManagerPath(path)` | — **required** | Where the metadata store lives |
| `withAndroidContext(context)` | — | Android: sets the store path for you |
| `withConcurrencyLimit(n)` | `1` | Simultaneous transfers |
| `withAutoStart(enabled)` | `false` | Start automatically on enqueue |
| `withContentDigest(algorithm)` | `null` | Hash content; `null` means no hashing at all |
| `withMaxRetryAttempts(n)` | `3` | Retries for transient failures |
| `withRetryBaseDelayMs(ms)` | `500` | Backoff base |
| `withMinReservedDiskBytes(bytes)` | `null` | Refuse to start without this much headroom |
| `withDownloadBufferSize(bytes)` | `8 KB` | Transfer buffer |
| `withDownloadNotifyEveryBytes(bytes)` | `512 KB` | How often progress is emitted |
| `withNimbusLogger(logger)` | `null` | Structured events, see `NimbusLogEvent` |
| `withNimbusStoragePort(port)` | filesystem | Replace file I/O entirely |
| `withDownloadScope(scope)` / `withIODispatcher(d)` | internal | Bring your own coroutine plumbing |

## Handling errors

Nothing throws. Every failure is a `NimbusError` with a typed cause, so a `when` over it is
exhaustive without a wildcard:

```kotlin
nimbus.startDownload(url).onFailure { error ->
    when (error) {
        is NimbusError.TemporaryError -> scheduleRetry()      // worth trying again
        is NimbusError.PermanentError -> when (val cause = error.errorCause) {
            PermanentNimbusErrorCause.DownloadNotFound     -> enqueueFirst()
            is PermanentNimbusErrorCause.InsufficientDiskSpace -> freeSpace(cause.requiredBytes)
            PermanentNimbusErrorCause.ContentDigestDisabled    -> enableDigest()
            else                                               -> report(cause)
        }
    }
}
```

A failed download carries the same detail in its state: `DownloadState.Failed(error)`, where
`error` is a `DownloadError` with its own temporary and permanent causes.

The full hierarchy — every variant and when it occurs — is in [`llms.txt`](llms.txt).

## Bring your own HTTP client

Use `KtorDownloadAdapter` from the `nimbus-ktor` artifact and skip this. Otherwise implement:

```kotlin
interface NimbusDownloadPort {
    suspend fun getFileSize(fileUrl: String): KResult<Long, GetFileSizeError>
    suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError>
}
```

Stream the body to the `Source` you are handed — do not buffer the whole response. When
`offset > 0` send `Range: bytes=offset-`, accept **206** with a matching `Content-Range`, reject a
**200** with a body (it would corrupt the file), and map **416** to
`TemporaryDownloadErrorCause.RangeNotSatisfiable`. `llms.txt` has the full contract.

## What Nimbus does not do

- **It does not work across processes.** The task store is held in memory and committed to one
  file, with no locking between processes. Two instances over the same
  `withDownloadManagerPath` — a UI and a separate downloader service on Android, say — overwrite
  each other's state. Run one instance and reach it from elsewhere through your own boundary.
- It does not create the parent directory of `filePath` for you.
- It does not enforce a process-wide singleton — your DI container's `single {}` does that.
- It does not support non-HTTP(S) URLs.
- It does not auto-start tasks that were `Enqueued` but never started in a previous session; call
  `startDownload` after `init()` if you want that.

## Working with an AI agent?

Point it at [`llms.txt`](llms.txt) — a complete single-file API reference written for coding
agents, including a section on the behaviours that are easy to assume wrongly. Contributors and
agents working *on* this repository should read [`AGENTS.md`](AGENTS.md).

## Releases

The [changelog](CHANGELOG.md) summarises every release.

## Example

The [Android sample](https://github.com/giovanniandreuzza/nimbus/tree/master/sample_android) is a
full integration: Koin for DI, an MVI ViewModel, progress, pause/resume, and content verification.
