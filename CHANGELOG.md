# Changelog

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
