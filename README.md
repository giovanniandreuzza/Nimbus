# Nimbus

> [!TIP]
> Want to chat live with me? Join me on [Discord server](https://discord.gg/EBXRXPRD).

Nimbus is a Kotlin Multiplatform download manager for **Android, JVM and iOS**, built for transfers
that have to survive the conditions they actually run in: a link that drops halfway, a process
killed mid-download, a volume that fills up, a device that comes back a day later and should not
start from zero.

**Nimbus does not speak any transport itself.** It owns the state machine, resume, retries, the
concurrency limit and persistence; moving the bytes is an adapter you plug in. HTTP(S) ships ready
to use as [`nimbus-ktor`](#install) — anything else, `ftp://`, a local or network share, a device
protocol of your own, is an adapter you write against one small interface. Nothing in the library
decides which schemes exist.

You also choose where files land. Every failure comes back as a typed value you can match on
exhaustively — nothing is thrown across the public boundary.

**Contents** · [Install](#install) · [Quickstart](#quickstart) · [Using the API](#using-the-api) ·
[API reference](#api-reference) · [Configuration](#configuration) · [Errors](#handling-errors) ·
[Transports](#transports) · [Limitations](#what-nimbus-does-not-do)

---

## Install

<!-- x-release-please-start-version -->
```kotlin
dependencies {
    implementation("io.github.giovanniandreuzza:nimbus:2.4.0")
    // The HTTP(S) transport adapter. Optional: leave it out if you are writing your own
    // adapter for a different transport.
    implementation("io.github.giovanniandreuzza:nimbus-ktor:2.4.0")
}
```
<!-- x-release-please-end -->

## Quickstart

Build once, keep the result — it is the whole API.

```kotlin
val nimbus: NimbusAPI = Nimbus.Builder()
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
    filePath = "/path/to/downloads/video.mp4",   // where the bytes go — the file, not its folder
    fileName = "video.mp4"                       // metadata: what to call it in your UI
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

Two parameters that are easy to mix up: `filePath` is the **full destination path of the file**,
and `fileName` is metadata for your UI. Nimbus does not join them, and does not create the parent
directory.

### Pause and resume

```kotlin
nimbus.pauseDownload(url)
// later, even after the app restarted:
nimbus.resumeDownload(url)
```

A resume continues from the bytes already on disk rather than starting over, including after the
process was killed. It is the partial file that makes that work, not a saved progress number:
progress ticks stay in memory and deliberately never touch the disk, so the offset is read back
from the file's own length.

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
already, according to `withTransportRetry`. This call is for a task
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
| `pruneFinished(olderThanMs, deleteFiles = false)` | Forgets old finished tasks; returns their urls |
| `flush()` | Commits state that was waiting for a coalesced write |
| `close()` | *Returns `Unit`.* Stops transfers, commits, releases the scope |

### Keeping the catalogue from growing forever

A finished task stays until something removes it — a `stat` at every boot, a slot in every
commit. On a player that cycles content for years that is a cost with no ceiling, so tasks record
when they finished and `pruneFinished` uses it:

```kotlin
nimbus.pruneFinished(olderThanMs = 30.days.inWholeMilliseconds, deleteFiles = true)
```

Tasks stored before 2.5.0 are stamped at the upgrade, so their age is measured from there rather
than from 1970 — the first call after an update does not empty the device.

### Shutting down

Terminal states — finished, failed, cancelled — are on disk before their call returns. The rest
(an enqueue, a pause, a progress position) rides along with the next coalesced commit, because a
commit rewrites every task and paying that per transition makes one download's cost grow with the
whole catalogue.

When the process is about to end on purpose, say so:

```kotlin
override fun onDestroy() {           // or a SIGTERM handler, or before a provisioning reboot
    scope.launch { nimbus.close() }  // stops transfers, commits, releases Nimbus's own scope
}
```

`flush()` is the commit on its own, for when the instance keeps living. After `close()` every call
returns `PermanentNimbusErrorCause.Closed` rather than quietly doing nothing, and a scope you
supplied with `withDownloadScope` is left running — it is yours.

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
| `withTransportRetry(policy)` | 5 tries, 0.5 s → 60 s | Retries inside one download; the task stays `Downloading` |
| `withAutoRetry(policy)` | forever, 2 s → 5 min | Retries a task that already **failed** (needs `withAutoStart`) |
| `withMaxRetryAttempts(n)` | `5` | Shorthand for the transport policy's attempt count |
| `withRetryBaseDelayMs(ms)` | `500` | Shorthand for the transport policy's first wait |
| `withStallTimeoutMs(ms)` | `60_000` | Abandon a transfer that delivers nothing for this long; `null` disables |
| `withDownloadRoot(dir)` | `null` | Refuse any destination outside this directory |
| `withMinReservedDiskBytes(bytes)` | `null` | Refuse to start without this much headroom |
| `withDownloadBufferSize(bytes)` | `8 KB` | Transfer buffer |
| `withDownloadNotifyEveryBytes(bytes)` | `512 KB` | How often progress is emitted |
| `withNimbusLogger(logger)` | `null` | Structured events, see `NimbusLogEvent` |
| `withNimbusStoragePort(port)` | filesystem | Replace file I/O entirely |
| `withDownloadScope(scope)` / `withIODispatcher(d)` | internal | Bring your own coroutine plumbing |

## Retries

Two loops, because the two questions are different.

**Inside one download** — a dropped connection, a 5xx, a link that went quiet — Nimbus retries on
its own and the task never leaves `Downloading`. Waits double from 0.5 s and stop at a minute,
five attempts by default (`withTransportRetry`). The budget is sized against real links: an LTE
reattach takes ten to thirty seconds, so a budget that expires in three is spent before the
network has finished coming back.

**After a download has failed** — the budget above is gone and the task is `Failed` — the
`withAutoStart` loop brings it back: re-fetch the size, reset the task, start again. Waits double
from 2 s and stop at 5 minutes, and by default it never gives up, because on an unattended device
giving up permanently is what a technician's visit looks like (`withAutoRetry`).

Every wait is spread by ±20 %. That is not configurable, and it is the difference between a fleet
that recovers and a fleet that comes back in lockstep and takes turns knocking the server over.

```kotlin
Nimbus.Builder()
    .withTransportRetry(RetryPolicy(maxAttempts = 8, baseDelayMs = 500, maxDelayMs = 30_000))
    .withAutoRetry(RetryPolicy(maxAttempts = null, baseDelayMs = 5_000, maxDelayMs = 600_000))
```

`NimbusLogEvent.AutoRetryScheduled` reports each wait as it is decided, and
`AutoRetryExhausted` fires only if you set a cap.

## When the file list comes from a server

On a kiosk it usually does, and then `filePath` and `fileUrl` are input rather than constants.
Two things are worth doing:

**Confine the writes.** Without a root the only check on a destination is that it holds no `..`,
so an absolute path naming the app's own database is accepted and written to:

```kotlin
Nimbus.Builder().withDownloadRoot(File(context.filesDir, "nimbus").absolutePath)
```

A destination outside it is refused with `PermanentNimbusErrorCause.PathOutsideDownloadRoot`. The
check is lexical — paths are normalised and compared — so a symlink under the root pointing
elsewhere still leads elsewhere.

**Watch what your logger forwards.** Every `NimbusLogEvent` carries the full `fileUrl`, and assets
are often served from signed URLs (S3 presigned, Azure SAS, CloudFront signed) whose signature
lives in the query string and is valid for hours. A logger that ships events to a monitoring
backend ships those credentials with them:

```kotlin
NimbusLogger { event -> Timber.tag("Nimbus").d("%s", event.redactingQueryStrings()) }
```

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

## Transports

Nimbus moves no bytes. A **transport adapter** does, and the library talks to it through one
interface:

```kotlin
interface NimbusDownloadPort {
    suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFile, GetFileSizeError>
    suspend fun downloadFile(
        fileUrl: String,
        offset: Long,
        resumeValidator: String?,
        onSourceOpened: suspend (Source) -> Unit
    ): KResult<Unit, DownloadError>
}

data class RemoteFile(val sizeBytes: Long, val validator: String? = null)
```

A URL, a byte offset, and a stream of bytes. There is no HTTP in that shape, and core does not
judge the scheme either — it only checks the url *has* one, so `ftp://`, `file://`, `s3://` or a
scheme you invent reaches your adapter instead of being refused before it is asked.

### What ships with Nimbus

| Transport | Adapter | Artifact |
|---|---|---|
| HTTP(S) | `KtorDownloadAdapter` | `io.github.giovanniandreuzza:nimbus-ktor` |
| anything else | yours | — |

```kotlin
Nimbus.Builder()
    .withNimbusDownloadPort(KtorDownloadAdapter(httpClient))   // or your own adapter
```

More first-party adapters may follow. Nothing stops you shipping one in the meantime — the
interface is public and stable.

### What your transport has to be able to do

Two requirements, and they are the honest limits of the design:

1. **Report the size before the transfer.** `getFileSize` is called at enqueue time, and the size
   drives progress, the disk-headroom check and the integrity check at the end.
2. **Start from a byte offset.** `downloadFile` is called with `offset > 0` to resume, and the
   bytes you supply are appended to what is already on disk. A transport that cannot seek will
   corrupt the file if it silently restarts from zero — fail instead, and Nimbus will handle it.

FTP satisfies both (`SIZE` and `REST`), as do local and network filesystems. A protocol that
streams without announcing a length, or cannot resume mid-file, does not fit this model today.

### Writing the adapter

Stream the body into the `Source` you are handed — never buffer the whole response.

Failures are reported with an error vocabulary that is deliberately HTTP-shaped
(`ServerError(statusCode)`, `ResourceNotFound`, `RangeNotSatisfiable`). It is precise and widely
understood, so an adapter for another transport maps its own failures onto it — the same way it
maps its own wire format onto a `Source`.

For HTTP specifically: when `offset > 0` send `Range: bytes=offset-`, accept **206** with a
matching `Content-Range`, reject a **200** with a body (it would corrupt the file), and map **416**
to `TemporaryDownloadErrorCause.RangeNotSatisfiable`. [`llms.txt`](llms.txt) has the full contract.

Two rules that are easy to miss, and expensive to miss:

- **Call `onSourceOpened` exactly once**, with one `Source` for the whole body. Nimbus opens the
  destination file around that call and closes it when the call returns.
- **Report a validator, and honour it on a resume.** `getRemoteFile` returns the size *and* an
  opaque token identifying that version of the file (over HTTP: `ETag`, else `Last-Modified`);
  `downloadFile` gets it back as `resumeValidator` and sends it as `If-Range`. If the origin
  answers with the whole file instead of the range, report
  `TemporaryDownloadErrorCause.RemoteFileChanged` — appending the tail of a new file to the prefix
  of an old one produces a file of exactly the right length that was never a file, which only a
  digest would catch. Return `null` for the validator if your transport has no such notion and
  resumes behave as they always did.
- **Give the transport its own deadline for inactivity.** Nimbus abandons a transfer that delivers
  nothing for `withStallTimeoutMs`, but abandoning it means cancelling your call — which only
  unwinds an adapter that *suspends* while it waits. `KtorDownloadAdapter` blocks a thread inside
  `runBlocking` when it reads the body, so it sets a socket timeout on every request instead
  (30 s by default, `KtorDownloadAdapter(client, socketTimeoutMillis = …)`). Without one, a server
  that answers with headers and then nothing wedges the transfer, holds its concurrency permit,
  and `pauseDownload` hangs with it.

## What Nimbus does not do

- **It does not work across processes.** The task store is held in memory and committed to one
  file, with no locking between processes. Two instances over the same
  `withDownloadManagerPath` — a UI and a separate downloader service on Android, say — overwrite
  each other's state. Run one instance and reach it from elsewhere through your own boundary.
- It does not create the parent directory of `filePath` for you.
- It does not enforce a process-wide singleton — your DI container's `single {}` does that.
- It does not speak any transport itself — see [Transports](#transports). A transport that cannot
  report a size up front, or cannot resume from a byte offset, does not fit the model today.
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
