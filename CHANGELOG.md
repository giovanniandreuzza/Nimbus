# Changelog

## [2.5.0](https://github.com/giovanniandreuzza/Nimbus/compare/v2.4.0...v2.5.0) (unreleased)

A field audit of the library against what it claims to be built for — a kiosk or a signage
player running unattended for months — found twenty-two things. This release is all of them.
Most are failures that were silent: the device kept reporting health while doing nothing, or
did the wrong thing quietly enough that it would surface weeks later as a file that never
arrived.

Read the **Migration** section: this release breaks `NimbusDownloadPort`, the `Checksum`
constructor, and the store schema.

### Fixed

- **A stalled connection no longer stops a device for good.** A server that accepts the
  connection, returns its headers and then sends nothing was, to every layer here, a download
  still in progress: the task stayed `Downloading`, its concurrency permit stayed taken — at
  the default limit of one, the whole queue — and nothing was emitted or logged.
  `withStallTimeoutMs` (60 s) measures *progress*, not elapsed time, so a transfer that
  legitimately takes hours is untouched. `getFileSizeToDownload` gets the same deadline: it
  runs on the caller's coroutine, so a server that never answers suspended whoever asked to
  enqueue rather than failing a download.

  A guard inside this library can only cancel, and cancelling only unwinds a transport that
  *suspends* while it waits. Ktor's `ByteReadChannel.asSource()` waits inside `runBlocking`,
  so `KtorDownloadAdapter` now imposes a 30 s socket timeout on **every request it makes** —
  on the request, not the client, so it holds whether or not the caller installed the
  `HttpTimeout` plugin.

- **Retries have a ceiling, a wait, and jitter.** Two loops had drifted apart. Within one
  download the waits were linear with no ceiling and the budget expired in three seconds — an
  LTE reattach takes ten to thirty, so a transient failure became a failed task before the
  network had finished coming back. After a task failed, with `autoStart`, there was no wait at
  all: measured, a port answering 503 was asked two hundred times inside half a second, each
  round a HEAD and a GET. Both now use `RetryPolicy`; every wait is spread by ±20 %, so a fleet
  that lost the same backend does not come back in lockstep.

- **A 416 truncates once.** The attempt after a truncation asks from offset 0 and carries no
  `Range` header at all, so a server that answers *that* with a 416 answers every one the same
  way: measured at five hundred consecutive truncations, at full speed, deleting and recreating
  the file each time with the task still reporting `Downloading`.

- **Nothing past the declared size is written.** The size drove the progress bar, the disk
  guard and the final check, and none of the writing: a body of 4 000 bytes against a declared
  1 000 was written in full. An origin serving an error page in place of a 200 MB asset could
  spend a whole volume. A body still arriving past the declared length is now
  `BodyLongerThanDeclared`, and the partial is discarded — at that point the file is exactly
  the expected length, which is the shape `startDownload` treats as complete.

- **A resume asks whether the file is still the same file.** `getRemoteFile` reports an `ETag`
  or `Last-Modified` alongside the size; it is stored with the task and sent back as
  `If-Range`. An origin answering with the whole file means the file changed
  (`RemoteFileChanged`): the partial goes and the transfer restarts. Same length, different
  file — a re-encode at the same bitrate, a regenerated manifest — passed every previous check.

- **Every state transition happens under one lock.** A `DownloadTask` is mutable and was shared
  by the service, which serialises per task, and the progress callbacks, which run on the
  download's coroutine and took no such lock: read-mutate-save left a window on every
  transition, and a progress tick landing after `pause()` wrote `Downloading` back over it,
  persisting a task as downloading with no job behind it. `pauseDownload` also stops the
  transfer before changing the state. The entity no longer leaves the repository at all.

- **A flow watching a download ends on a permanent failure**, with `autoStart` or without. It
  used to stay open for every failure, so a 404 suspended a reconciliation loop for the life of
  the process — on the first asset the backend had removed, taking every other file with it.

- **An `expectedChecksum` nothing can check is refused instead of ignored.** With no digest
  configured the comparison was skipped and the download reported finished: the caller believed
  bytes were verified that nobody read. Now `ContentDigestDisabled`, before a task exists, and
  `ChecksumAlgorithmMismatch` when the checksum names a different algorithm than the configured
  one.

- **A resume no longer re-reads what the digest already stands for.** The rule is unchanged —
  what the digest has consumed must be exactly the bytes on disk — but it is now checked rather
  than re-established by reading: a 20 MB asset dropping three times cost 60 MB of eMMC reads
  and hashing per cycle to re-learn what the process had just hashed itself.

- **A clock that was wrong no longer deletes a night's downloads.** Boards without a
  battery-backed clock come up at the epoch and learn the time when the network appears, so
  anything finished in that window is stamped 1970. Such stamps are repaired at the next load,
  once the clock is believable, and a finish time in the future — a clock corrected backwards —
  is never read as an age.

- A transfer loop that answered a zero-length read by going round again, against a source whose
  answer never changes. It leaves instead; the attempt then ends short of the declared length,
  which is reported as `FileIntegrityMismatch` and retried.

### Added

- **`flush()` and `close()`.** Terminal states are on disk when their call returns; the rest —
  an enqueue, a pause, a progress position — rides along with the next coalesced commit, because
  a commit rewrites every task. `flush()` asks for that commit; `close()` stops every transfer,
  commits, then releases, in that order, and cancels the coroutine scope only if Nimbus created
  it. Afterwards every call returns `PermanentNimbusErrorCause.Closed` rather than quietly doing
  nothing.
- **`pruneFinished(olderThanMs, deleteFiles)`**, and the timestamps it needs. A player cycling
  content for years kept every asset it ever fetched as a `Finished` task — a `stat` at every
  boot and a slot in every commit — because nothing recorded *when* they finished.
  `DownloadTaskDTO` gains `createdAtEpochMs` and `finishedAtEpochMs`.
- **`withDownloadRoot(dir)`**, for when destinations come from a manifest rather than from your
  own code. Without it the only check is that a path holds no `..`, so an absolute path naming
  the app's own database is accepted and written to. Anything outside the root is refused with
  `PathOutsideDownloadRoot`.
- **`RetryPolicy`**, with `withTransportRetry(policy)` and `withAutoRetry(policy)`.
  `withMaxRetryAttempts` and `withRetryBaseDelayMs` remain as shorthands for parts of the
  transport policy.
- **`withStallTimeoutMs(ms)`**, and `KtorDownloadAdapter(client, socketTimeoutMillis = …)`.
- **`NimbusError.causeCode` and `.isRetryable`.** The typed causes nest three deep, which is
  right for a caller who wants precision and more than most want: `causeCode` unwraps the two
  causes that exist only to carry another and returns the specific code.
- **`fileName` is optional**, defaulting to the last segment of `filePath`. It is a label —
  validated, stored, reported, and used by no file operation.
- New log events: `AutoRetryScheduled`, `AutoRetryExhausted`, and `Unexpected(fileUrl,
  throwable)`, which hands over the throwable itself because a code and a message reach a
  monitoring backend as "null" with nothing saying where.
- `DownloadTaskDTO.resumeValidator`, and `RemoteFile` on the transport port.

### Changed

- **`NimbusDownloadPort` has two new signatures.** `getFileSize` becomes
  `getRemoteFile(fileUrl): KResult<RemoteFile, GetFileSizeError>`, and `downloadFile` takes
  `resumeValidator: String?`. Overloads with defaults would have kept the old shape compiling,
  at the price of two ways to say everything in a port that exists to be implemented once.
- **`Checksum`'s constructor is internal**; `Checksum.of(algorithm, value)` is the only way in.
  It accepted any string, so `Checksum(SHA256, "abc")` compiled and then mismatched on every
  transfer — for ever, because a mismatch is temporary and gets retried.
- **Store schema 3.** A version 2 store migrates on load and keeps its tasks; a version 3 store
  is not readable by 2.4.0, which discards it and starts empty.
- The default transport attempt count moves from 3 to 5, sized against a real LTE reattach.
- Builder options that could not work are refused where they are written rather than at the
  first transfer: a zero buffer, a buffer past 16 MiB, a concurrency limit below 1, a
  non-positive stall timeout or notification interval.
- A hashed check of a file already on disk runs inside the download job, under the concurrency
  limit, instead of on the caller's coroutine.

### Documentation

- README gains sections on retries, shutting down, pruning, and what to do when the file list
  comes from a server — including that every log event carries the full url, which for a signed
  S3, SAS or CloudFront link is a live credential.
- `llms.txt` matches the shipped API again: the new port shape, the new DTO fields, the new
  causes and events, and eleven more entries under the guarantees that are easy to assume
  wrongly.
- The uncompiled `examples/KioskDownloadManager.kt` is gone. No Gradle task built it and nothing
  referenced it, so it rotted: its exhaustive `when` had been missing a cause since 2.3.0. The
  Android sample is the example the build keeps honest.

### Migration

1. **Transport adapters.** Rename `getFileSize` to `getRemoteFile` and return
   `RemoteFile(sizeBytes, validator)` — `validator = null` keeps today's behaviour. Add
   `resumeValidator: String?` to `downloadFile`; ignoring it also keeps today's behaviour. To
   gain the safe resume, send it as `If-Range` and report
   `TemporaryDownloadErrorCause.RemoteFileChanged` when the origin answers a resume with the
   whole file. Users of `nimbus-ktor` need no changes.
2. **`Checksum(algorithm, value)` → `Checksum.of(algorithm, value)`**, which throws
   `IllegalArgumentException` on a value that is not a digest of the right length.
3. **Exhaustive `when`s over the public sealed causes need new branches**: `RemoteFileChanged`,
   `BodyLongerThanDeclared`, `Closed`, `PathOutsideDownloadRoot`, `ChecksumAlgorithmMismatch`.
   `NimbusError.causeCode` is there for callers who would rather not.
4. **Downgrades are one-way.** 2.5.0 reads a 2.4.0 store and migrates it; 2.4.0 discards a 2.5.0
   store and starts with nothing.
5. Nothing else changes for existing callers. Every new builder option is optional and every
   default is the previous behaviour, except the two stated above: the transport attempt count,
   and configuration values that used to be accepted and now throw.

## [2.4.0](https://github.com/giovanniandreuzza/Nimbus/compare/v2.3.0...v2.4.0) (2026-09-12)

### Changed

- **`Nimbus.Builder()` no longer needs the companion object.** `Builder` was nested inside a
  `companion object` that existed only to hold it, and Kotlin reaches a nested classifier as
  `Outer.Companion.Nested` rather than `Outer.Nested` — so every caller had to write
  `Nimbus.Companion.Builder()`. The companion is gone and `Builder` is nested directly in
  `Nimbus`. No `Builder()` function was left in its place: a function and a class of the same
  name in one scope is an ambiguity, and a deprecation shim for an entry point confuses more than
  it saves.

  **This is a source and binary break, released as a minor deliberately.** Semantic versioning
  would call it a major; with 2.3.0 hours old and effectively no adopters, the maintainer chose
  the smaller number and this note over a 3.0.0. Update call sites to `Nimbus.Builder()` — the
  JVM class moves from `Nimbus$Companion$Builder` to `Nimbus$Builder`.

### Added

- **Any transport, not just HTTP.** Core used to require `http` or `https` before anything else
  ran, which made `NimbusDownloadPort` unimplementable for ftp, a local or network share, or a
  protocol of your own, however capable the adapter was: the request was refused before the port
  was ever asked. A transport decision does not belong in the layer whose design rule is that it
  knows nothing about transports.

  What is still checked is that the url carries a scheme, as RFC 3986 spells it in ASCII —
  a syntax check, not a judgement about transports. The url is also the task's identity, so a
  string that is not a URL becomes a task nobody can address. `PermanentNimbusErrorCause.InvalidUrl`
  now means *no scheme*.

  Two things a transport must still be able to do, and they are the honest limits of the model:
  report the size before the transfer, and start from a byte offset when resuming. `nimbus-ktor`
  remains the HTTP(S) adapter; see README → Transports.

### Documentation

- The README is ordered by what a reader does — install, one working download, then each
  operation with the code for it — instead of listing capabilities in prose. It also states two
  things that were previously discoverable only by getting them wrong: `filePath` is the full
  destination path of the file and is never joined with `fileName`, and progress is not persisted
  — the partial file is what makes a resume work, and the offset is read back from its length.
- `llms.txt` matches the shipped API again and gained a section on the guarantees that are easy to
  assume wrongly. `AGENTS.md` is new, and routes to `CLAUDE.md` or `llms.txt` rather than
  restating either.
- The one-process limitation is stated where an integrator will see it. The store has no locking
  between processes; two instances over one `withDownloadManagerPath` overwrite each other.

### Migration

- `Nimbus.Companion.Builder()` → `Nimbus.Builder()`.
- Nothing else changes for existing callers. `InvalidUrl` is now returned in strictly fewer cases
  than before, so no url that used to be accepted is rejected now.

## [2.3.0](https://github.com/giovanniandreuzza/Nimbus/compare/v2.2.0...v2.3.0) (2026-09-12)



### Added

- **Content digest.** Opt in with `Nimbus.Builder().withContentDigest(DigestAlgorithm.SHA256)`.
  The bytes are hashed in the pass that already writes them to disk, so a caller that would
  otherwise read the whole file back to hash it itself pays nothing beyond the hashing.
  - `DownloadTaskDTO` gains `checksum` — what the file hashed to, once it finished — and
    `expectedChecksum`.
  - `NimbusAPI.enqueueDownload` and `NimbusAPI.ensureDownloaded` take an optional
    `expectedChecksum`, verified against the transferred bytes.
  - `NimbusAPI.checksum(fileUrl)` re-derives the digest of a finished file from disk. This
    is what separates a file whose bytes have changed since download from a file that is
    intact and simply cannot be used — treating the second as the first means deleting and
    re-downloading a good file forever.
  - New public types `Checksum` and `DigestAlgorithm`.
  - Consumers that configure no algorithm see no behavioural change and no hashing.
- **A full volume is told apart from broken storage.** A write the volume refuses now asks it
  how much room is left, rather than reading the exception for the word "space" — the message
  is the platform's to phrase. `PermanentDownloadErrorCause.InsufficientDiskSpace` carries
  what was needed and what was free, and survives a restart. A write that failed for any
  other reason, and a volume that cannot answer, are unchanged.
- `NimbusLogEvent.StoreFlushFailed`, reporting a background store commit that failed.

### Fixed

- **`observeAllDownloads()` emitted nothing but task additions and removals.** The repository
  published its state as a copy of the task map, but `DownloadTask` is an `Entity` whose
  `equals` is identity on the id alone, so a map holding the same tasks in new states compared
  equal to its predecessor and `StateFlow` conflated the emission away. A list UI saw tasks
  appear and then never move: no progress, no pause, no completion.
- **The persisted schema version never reached the disk.** ProtoBuf omits a value equal to its
  declared default, and the field defaulted to the current version, so it was never encoded —
  a store written by an older build decoded as whatever the reading build's default happened
  to be. It now defaults to a value no build writes.
- **Unclassifiable storage failures were reported as permission denials.** Every method of the
  filesystem adapter ended `catch (t: Throwable) -> …PermissionDenied(…)`, so anything that
  was not an `IOException` — a `SecurityException`, a platform-specific filesystem error, an
  `OutOfMemoryError` — reached the caller as a permission problem. Each storage error family
  gains an `UnexpectedError` cause.
- **A discarded result in the HTTP 416 truncation path.** A failing delete left the following
  create to report `FileAlreadyExists`, which retried as `TruncateRace`: the download
  self-corrected, but the retry was attributed to a race that never happened and the real
  failure never reached the logger.
- An unsynchronised read-modify-write in the persistence layer, where two concurrent saves
  could both derive from the same base and the later write drop the earlier one.

- **A file already on disk was accepted on its length alone.** `startDownload` short-circuits
  when the destination already has the expected size, and that path predated the digest: no
  bytes were hashed and an `expectedChecksum` was never consulted, so a digest-enabled task
  finished with nothing recorded. It is the case the feature exists for — a player that keeps
  its assets between runs meets it on every start. The file is now hashed from disk and put
  through the same verification a transfer gets.
- **`ensureDownloaded` dropped an expected checksum when the task already existed.** It was
  forwarded only where a task was created, which is the case a caller could have handled with
  `enqueueDownload` themselves. A task still in flight now adopts the expectation; a finished
  one cannot — its file was accepted against the old expectation — so it is treated as stale
  and fetched again.
- **An unreadable `Content-Range` was treated as nothing to disagree with.** A 206 whose header
  could not be parsed had its body appended at the resume offset unchecked, producing a file of
  exactly the right length holding the wrong bytes — which the size check cannot see. It is now
  refused like a missing header.
- **The store preferred a destination that a crash may have half-written.** Saving encodes the
  whole store into a temp file and only then commits it with an atomic move, so a temp left
  behind holds the newer complete state — but loading read the destination first and fell back
  to the temp only if that failed. Where the move has to fall back to copying, because the
  filesystem has no atomic rename, a partially copied destination that happened to decode won
  over the complete copy beside it. The preference is now the other way round.

- **A dropped connection was reported as a completed transfer.** A body that stopped arriving
  part-way read as an ordinary end of stream — measured at 38 attempts out of 40 against a
  server that promises a length, sends half and hangs up — and the transfer was reported as
  having delivered nothing at all, successfully. The size check upstream caught the short file
  either way, so downloads still recovered; what was lost was a retry attributed to an
  integrity mismatch and a dead link that never appeared in the log. The adapter now counts
  what it pulls off the connection and holds it against the length that was promised.

- **No network failure was ever retried.** Every request path in `KtorDownloadAdapter` ended
  by mapping whatever it caught to a permanent error, and only temporary errors are retried,
  so a socket timeout failed the download on its first stalled read and left it failed —
  `withMaxRetryAttempts` and `withRetryBaseDelayMs` had nothing to act on. Timeouts,
  connection resets and failed name resolution are now temporary, which is what they are: a
  connection reset and a name that would not resolve are both more likely than a timeout the
  longer a transfer runs, and both were reported as permanent.
- **A resumed transfer could append the same stretch twice.** The resume offset was read from
  a counter that only advances when a transfer returns, so an attempt whose body stopped
  arriving left it describing bytes that were already on disk. It is read back from the file
  now, which is also the only source that survives the process dying mid-transfer — the case
  the resume exists for.

### Changed

- **The store is committed once per burst rather than once per state change.** A commit
  rewrites every task the device holds, so committing each transition made the cost of one
  download's lifecycle grow with the size of the whole catalogue — on appliance flash that is
  wear, not just latency. `Enqueued`, `Downloading` and `Paused` now ride along with the next
  coalesced commit; `Finished`, `Failed` and `Cancelled` are still durable before the save
  returns. Measured on a 50-task catalogue: 100 non-terminal saves went from 100 commits to 0
  during the burst and one afterwards.
- Progress updates no longer copy the task map. They did so on every tick whether or not
  anyone was observing.
- Boot recovery commits once rather than once per recovered task.
- Persisted store schema is now version 2. A version 1 store is read and migrated, not
  discarded: its tasks are kept.

### Internal

- Core no longer imports infrastructure. `DownloadService` worked against `NimbusStoragePort`
  and three of its error families — the only outward imports anywhere under `core/`. A
  core-owned `StoragePort` and a `StorageAdapter` now mirror what `DownloadPort` and
  `DownloadAdapter` already did for downloads. `NimbusStoragePort` is untouched: signature,
  package, visibility. Third-party implementors see no change.
- The three benign storage outcomes are success values rather than errors each call site had
  to remember to exempt.
- An architecture test asserts the boundary holds.
- Telling a dead socket from a full disk is settled inside the library rather than left to
  each implementor. A `NimbusDownloadPort` has to invoke the callback while the response is
  open, so a sink that could not be written arrives at the same `catch` as a dropped
  connection — on every platform both are `kotlinx.io.IOException`. Anyone writing a port
  against OkHttp, CIO or a platform stack would have had to notice that and solve it, or far
  more likely write `catch (e: Exception) -> PermanentError` and reintroduce exactly this.

- `CreateStoreError` was public by accident and is now internal. It never escaped
  `frameworks/store` and was not in the public surface list.

### Migration

Source-compatible except for exhaustive `when`, which gains a branch in three places:

- `TemporaryDownloadErrorCause` gains `ChecksumMismatch`.
- `NimbusLogEvent` gains `StoreFlushFailed` and `StoreReset`.
- `PermanentNimbusErrorCause` gains `ContentDigestDisabled`, returned by `checksum(fileUrl)`
  when no digest algorithm is configured. It previously arrived as `UnexpectedError`, which
  the documentation did not say and a caller could not usefully branch on.
- `PermanentDownloadErrorCause` gains `InsufficientDiskSpace`.
