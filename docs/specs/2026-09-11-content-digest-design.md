# Content digest — design

Date: 2026-09-11
Status: implemented in 2.3.0
Target: 2.3.0

Landed as `c18ab95`. Both open questions below were settled as this document expected, and are
recorded there.

## Problem

A finished download tells the caller its size was right and nothing else.

`DownloadAdapter.verifyFileIntegrity` compares the bytes on disk against
`downloadTask.fileSize` and reports `TemporaryDownloadErrorCause.FileIntegrityMismatch` when
they differ. That catches a truncated transfer. It cannot catch a file whose length is
correct and whose contents are not, and it gives the caller nothing to check the file
against later.

Three capabilities are missing, and all three are the same capability:

- **The server published a checksum and Nimbus cannot honour it.** A caller with a
  content digest from its backend has no way to ask Nimbus to verify the transfer against
  it.
- **A caller cannot learn what it downloaded.** Nothing in `DownloadTaskDTO` identifies the
  content, so two URLs that resolve to the same bytes are indistinguishable to the caller.
- **A caller cannot re-verify a file later.** Once a download finishes, the only question
  Nimbus can answer about the file is whether it exists and how big it is. A consumer that
  wants to know whether a file still holds the bytes it received has to read and hash the
  file itself — a second full pass over data Nimbus already had in its hands.

That last point is what makes this a Nimbus feature rather than a caller's. Every byte
already passes through `copySourceToSink` exactly once on its way to disk. A caller
computing the same digest afterwards reads the whole file back off storage to learn
something Nimbus could have produced for free.

## Why now

The first consumer is a digital-signage SDK whose player deletes and re-downloads any asset
that fails to play, because a playback failure is the only signal it has and it cannot tell
a broken file from a file the device cannot decode. On its target hardware that costs about
2 GB of cellular data a day, indefinitely. A content digest is what separates the two cases:
bytes that changed since download, versus bytes that are intact and simply cannot be played.

The capability is not specific to that caller. Any consumer that keeps downloaded files
around and later wants to know whether they are still the files it downloaded needs the same
thing.

## Design criteria

Nimbus's demanding consumers are unattended devices: appliances and kiosks running for days
or weeks without a person present, on old hardware, over unstable links. Every choice below
is judged against that, in this order:

1. **Few wasted resources.** Storage reads and writes first, then CPU. A design that reads
   the whole file a second time to learn something it already knew is the failure mode this
   feature exists to avoid.
2. **Optimisation.** Do the work once, in the pass that is already happening.
3. **Safety.** A silently wrong digest is worse than no digest at all: a consumer that trusts
   it will conclude the file is corrupt and re-download it forever. Correctness in the
   awkward cases outranks elegance in the common one.

## API surface

Opt-in. A caller that does not ask for a digest pays nothing — no hashing, no extra state,
no behaviour change.

```kotlin
Nimbus.Builder()
    .withContentDigest(DigestAlgorithm.SHA256)
    .build()
```

**`DownloadTaskDTO` gains `checksum: Checksum?`**, populated when the download finishes and
a digest algorithm is configured. Null when the feature is off, and while the task has not
finished.

**`NimbusAPI` gains one method:**

```kotlin
/**
 * Recomputes the content digest of an already-downloaded file from disk.
 *
 * Returns [NimbusError.PermanentError] with [PermanentNimbusErrorCause.InvalidState] when
 * the task is not finished, and the configured algorithm is used. This reads the whole
 * file: callers should treat it as an explicit verification step, not a cheap accessor.
 */
public suspend fun checksum(fileUrl: String): KResult<Checksum, NimbusError>
```

**`enqueueDownload` and `ensureDownloaded` gain an optional expected checksum:**

```kotlin
public suspend fun enqueueDownload(
    fileUrl: String,
    filePath: String,
    fileName: String,
    expectedChecksum: Checksum? = null
): KResult<DownloadTaskDTO, NimbusError>
```

**Two new public types**, which must be added to the public-surface list in `CLAUDE.md`:

```kotlin
public class Checksum(
    public val algorithm: DigestAlgorithm,
    public val value: String        // lowercase hex
)

public enum class DigestAlgorithm { SHA256 }
```

`DigestAlgorithm` is an enum with one entry rather than a bare `SHA256` constant so a second
algorithm can be added without a breaking change. Only SHA-256 ships in 2.3.0:
`org.kotlincrypto.hash:sha2` is already a dependency — `IdProviderAdapter` uses it to derive
task ids — so the capability costs no new dependency for any consumer. Adding a cheaper
algorithm later is a dependency decision for every consumer and needs its own justification.

## Where the digest is computed

In `copySourceToSink`, as the bytes pass.

`DownloadAdapter` already owns the transfer, the concurrency semaphore and the retry loop.
The digest is a property of the transfer, so it belongs there and not in `DownloadService`,
which stays orchestration per the architecture rules.

### The single-buffer invariant must survive

`copySourceToSink` reads with `input.readAtMostTo(sink.buffer, bufferSize)`, straight into
the sink's own buffer. `CLAUDE.md` records why: an earlier double-buffered version
accumulated the whole file in memory. On a 2 GB appliance that is not a performance note,
it is an out-of-memory crash.

The digest must therefore be fed from the bytes already in the sink buffer — the range
appended by the current `readAtMostTo`, consumed before `sink.emit()` — and must not
introduce a second buffer sized by the file or by the transfer. An implementation that
allocates a per-chunk `ByteArray` bounded by `bufferSize` (16 KB by default) is acceptable;
one whose footprint grows with the file is not.

This is the constraint to check first in review, because a regression here is invisible
until a large file on a small device.

## The resume problem

This is the part that decides the design, and getting it wrong is worse than not shipping
the feature.

`resolvePartialBytesOnDisk` establishes the resume offset by reading the **size of the file
on disk**. Nothing about it is held in memory, so a resume survives a process restart: the
partial file is on disk, the task is in the ProtoBuf store, and the next attempt continues
from wherever the bytes stop.

A digest accumulated only across a streaming session therefore covers only the bytes that
session transferred. After a restart-and-resume it would digest the tail alone and produce a
value that is well-formed, plausible, and wrong.

A wrong digest is not a cosmetic defect here. The consumer that asked for this feature will
compare it later, find a mismatch, conclude the file is corrupt, and delete and re-download
it — on every pass, forever. Shipping a digest that is wrong after a resume would manufacture
exactly the failure this feature was built to remove, and would do it silently.

### The rule

> At the start of every streaming attempt, prime the digest from the bytes already on disk,
> then feed it the bytes streamed after that.

- **Fresh download.** Nothing is on disk, the prime reads zero bytes, and the digest costs
  nothing beyond the hashing itself. This is the common case and the reason for computing in
  the loop at all.
- **Resume.** The prime re-reads the prefix already fetched — a cost paid only when a
  transfer was already interrupted, which is to say when far more has already been wasted.
- **Retry inside `downloadLoop`.** Re-priming at the top of each attempt is what makes the
  rule hold there too: the digest always reflects the file as it exists at the moment
  streaming begins.

One code path, no conditional, correct in every case.

### The alternative that was rejected

Stream-hash when the offset is zero and fall back to reading the file when it is not. It
saves the prime read in the resume case and costs a branch that is rare in development and
common in the field — unstable links are the environment this library is for. A wrong digest
produced only on the rarely-exercised path is the worst available outcome, so the branch is
not worth its saving.

## Verification and error semantics

When the caller supplied an `expectedChecksum`, `verifyFileIntegrity` compares it after the
existing size check. Size first: it is free, and a truncated file should report the more
specific cause.

A mismatch reports a **new temporary cause**:

```kotlin
TemporaryDownloadErrorCause.ChecksumMismatch
```

Temporary, never permanent. A checksum mismatch is a statement about the transfer, not about
the file at the origin: a corrupted proxy response, a truncated body a correct
`Content-Length` hid, a cache serving something stale. The adapter retries these, which is
the right response. Classifying it permanent would mark the download non-recoverable and
leave the caller to delete and refetch — the pattern this library's consumers have already
been burned by when a transport failure was reported as a fact about the file.

It joins `FileIntegrityMismatch` in the temporary family, which is where the analogous
size failure already lives.

`PermanentDownloadErrorCause` gains nothing. There is no checksum failure that is a property
of the URL rather than of the attempt.

## What does not change

- `DownloadService` stays the single application service and gains no logic beyond passing
  the expected checksum through and exposing `checksum(fileUrl)`.
- `DownloadTask` keeps ownership of state transitions. The digest is data carried alongside
  the task, not a new state: there is no `Verifying` state and no new transition.
- Error mapping stays in `NimbusErrorMappers.kt`.
- Progress reporting is untouched. Hashing happens between the read and the emit and must
  not change how often `onDownloadProgress` fires.
- Consumers that do not configure an algorithm see no behavioural difference at all.

## Testing

The test that carries the design:

> the digest of a download that ran start to finish is identical to the digest of the same
> content downloaded with an interruption at N bytes and a resume

Without it, prime-from-disk is an assertion rather than a property. It needs to cover a
resume within the session and a resume after the task has been reloaded from the store,
since only the second exercises the case where no in-memory state could have survived.

Also required:

- A supplied checksum that does not match reports `TemporaryDownloadErrorCause.ChecksumMismatch`,
  and the adapter retries rather than failing the task permanently.
- A supplied checksum that matches finishes normally, and the reported checksum equals the
  supplied one.
- Size mismatch and checksum mismatch together report the size cause, not the checksum cause.
- No configured algorithm means no hashing: `DownloadTaskDTO.checksum` is null and the
  transfer path is byte-identical to 2.2.0.
- `checksum(fileUrl)` on an unfinished task returns `InvalidState`.
- Memory footprint during a large download does not scale with file size — the single-buffer
  invariant, asserted rather than assumed.

## Release

2.3.0. Additive: new optional parameters with defaults, one new method, two new public types,
one new error cause in an existing sealed family.

Consumers that exhaustively `when` over `TemporaryDownloadErrorCause` will need a branch for
`ChecksumMismatch`. That is a source-compatible break for exhaustive matches and belongs in
the release notes.

`CHANGELOG.md` is currently empty and `README.md` still advertises 2.1.1 while 2.2.0 is
published. Both want correcting in this release regardless of this feature.

## Open questions

Neither blocks implementation.

- **Whether `checksum(fileUrl)` should cache its result.** It reads the whole file each call.
  A caller verifying on every playback failure would pay that read each time. Caching moves
  the invalidation problem into Nimbus, which has no way to know the file changed underneath
  it — the very thing the caller is asking about. Left uncached deliberately; revisit only
  if a consumer demonstrates the read is a real cost in its access pattern.
  *Settled: no.* `DownloadService.checksum` calls `contentDigestPort.digestOf` on every
  invocation and holds nothing.
- **Whether a cheaper algorithm is worth adding.** For detecting bit rot and truncation
  rather than an adversary, CRC32 is adequate and far cheaper on old ARM cores. It is not in
  2.3.0 because it is a new dependency for every consumer, and because in `commonMain` a
  Kotlin implementation gets no hardware acceleration, so the saving is smaller than it looks.
  Decide with a measurement on real target hardware, not from the instruction set on paper.
  *Settled: not in 2.3.0.* `DigestAlgorithm` ships with `SHA256` as its only entry.
