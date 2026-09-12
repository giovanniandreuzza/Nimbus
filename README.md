# Nimbus

> [!TIP]
> Want to chat live with me? Join me on [Discord server](https://discord.gg/EBXRXPRD).

Nimbus is a Kotlin Multiplatform download manager for Android, JVM and iOS — built for transfers
that have to survive the conditions they actually run in.

It is designed around the awkward cases rather than the happy path: a link that drops halfway, a
process killed mid-download, a volume that fills up, a device that comes back a day later and
should not start from zero. Downloads resume from the bytes already on disk, every failure arrives
as a typed value you can match on exhaustively — nothing is thrown across the public boundary —
and an optional content digest lets you tell a file whose bytes changed from one that is intact
and simply cannot be used.

You bring the HTTP client and you choose where files land. Nimbus owns the state machine,
the retries, the concurrency limit and the persistence.

```kotlin
val nimbus = Nimbus.Builder()
    .withNimbusDownloadPort(KtorDownloadAdapter(httpClient))
    .withDownloadManagerPath(storePath)
    .createAndInit()

nimbus.ensureDownloaded(url, directory, fileName)
    .getOr { return }
    .collect { state -> render(state) }
```

`ensureDownloaded` covers the whole lifecycle: enqueue, start, resume a paused task, retry a failed
one, or return immediately if the file is already there.

> [!NOTE]
> **Working with an AI agent?** Point it at [`llms.txt`](llms.txt) — a complete, single-file API
> reference written for coding agents, including the behaviours that are easy to assume wrongly.

Get started with
our [📚 installation guide](#installation)
and [example project](#example),


Table of contents
=================

<!--ts-->

* [Features](#features)
* [Releases](#releases)
* [Installation](#installation)
* [Example](#example)

<!--te-->

## Features

**Nimbus APIs**: The library provides a fully typed, suspend-based interface (`NimbusAPI`) to manage download operations. All errors are returned as typed values — no exceptions thrown across the public boundary.

**Typed Error Hierarchy**: Every failure carries a typed cause (`TemporaryNimbusErrorCause` / `PermanentNimbusErrorCause`) enabling exhaustive `when` matching without wildcards.

**Concurrency Limit**: Set a custom concurrency limit to control the number of downloads that can
occur simultaneously.

**Progress Tracking**: Track the progress of each download operation with real-time `Flow<DownloadState>` emissions.

**Pause / Resume**: Pause a running download and resume it later — progress is preserved across sessions via ProtoBuf persistence.

**Cancel**: Cancel a download operation from any state; partial files are cleaned up automatically.

**Retry**: Retry a failed download with `retryFailedDownload` — re-fetches remote size and resets state.

**Auto-start**: Optionally fire `startDownload` automatically after `enqueueDownload` with `withAutoStart(true)`.

**Content Digest**: Opt in with `withContentDigest(DigestAlgorithm.SHA256)` and every download is
hashed as it is written, so learning what you downloaded costs nothing beyond the hashing. Pass an
`expectedChecksum` to have a transfer verified against a digest your backend published, and call
`checksum(fileUrl)` later to ask whether a file still holds the bytes it arrived with — the
difference between a file that has changed and a file that is intact but unusable.

```kotlin
val nimbus = Nimbus.Builder()
    .withNimbusDownloadPort(myDownloadPort)
    .withDownloadManagerPath(path)
    .withContentDigest(DigestAlgorithm.SHA256)
    .build()
```

**Pluggable HTTP Client**: Implement `NimbusDownloadPort` yourself, or use the ready-made `KtorDownloadAdapter` from the `nimbus-ktor` artifact.

**KMP-native Storage**: File I/O via `kotlinx.io.files.SystemFileSystem` — no `java.io.File`, works on Android, JVM, and iOS.

> [!IMPORTANT]
> **One process only.** Nimbus keeps its task store in memory and commits it to a single file;
> there is no locking between processes. Two instances over the same
> `withDownloadManagerPath` — a UI and a separate downloader service on Android, say — will
> overwrite each other's state. Run one instance and reach it from elsewhere through your own
> boundary. See `docs/specs/2026-09-11-multi-process-topology.md` for what supporting a second
> topology would require.

## Releases

* The [changelog](CHANGELOG.md) provides a summary of changes in each release.

## Installation

Add `nimbus` to your `build.gradle` dependencies. Optionally add `nimbus-ktor` for the ready-made Ktor HTTP adapter.

<!-- x-release-please-start-version -->
```kotlin
dependencies {
    implementation("io.github.giovanniandreuzza:nimbus:2.3.0")
    // Optional — recommended Ktor HTTP adapter:
    implementation("io.github.giovanniandreuzza:nimbus-ktor:2.3.0")
}
```
<!-- x-release-please-end -->

### Example

The [SampleAndroid Project](https://github.com/giovanniandreuzza/nimbus/tree/master/sample_android)
demonstrates how to integrate and use Nimbus in an Android project.