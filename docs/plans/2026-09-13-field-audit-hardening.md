# Field-Audit Hardening Implementation Plan

> **Written after the fact.** The repository's other plan was written before its work and its
> boxes were never ticked; this one records the sequence that was actually executed, so its boxes
> are ticked and each task carries what it landed. It is here for the same reason the spec is:
> the order was a decision, and the reasons for it are not visible in the diff.

**Goal:** Close the twenty-two findings of the field audit against what the library claims to be
built for — a kiosk or signage player running unattended for months — and release them as 2.5.0.

**Architecture:** Seven groups of change, ordered so that each one's tests can be written against
a library the previous one left green. The transport-facing work (stall, retry, write cap) comes
first because it is what stops a device; the correctness work (transition lock, resume validator)
second because it needs the first group's tests to still pass; the storage-shape work (timestamps,
prune, validator persistence) third because it changes the schema once; the API surface last.

**Tech Stack:** Kotlin Multiplatform 2.3.20 (JVM 17, Android minSdk 21, iOS), kotlinx-coroutines,
kotlinx-io, kotlinx-serialization-protobuf, Ktor 3.4.2, Gradle 9.6.

**Spec:** `docs/specs/2026-09-13-field-audit-hardening.md`

## Global Constraints

- **`./gradlew build` green at all times** — all targets, not `:nimbus:jvmTest`. Kotlin/Native
  rejects what the JVM accepts, and only the full build finds it.
- **Kotlin/Native rejects commas in backtick test names.** `jvmTest` tolerates them; `commonTest`
  does not, and the error only appears in the native compile.
- **`commonTest` unless genuinely platform-bound.** A test that needs real threads or a blocking
  read goes to `jvmTest`, and says in its KDoc why it could not live in common.
- **Every new `Temporary`/`Permanent` download cause needs a branch in `DownloadStateStoreMappers`
  and a row in the round-trip test.** A missing branch is not a compile error: the state persists
  and comes back as something else.
- **Never bump the version by hand.** `version.txt`, `CHANGELOG.md` and the README coordinates are
  release-please's.
- **Branch prefixes** `feature/ bugfix/ hotfix/ release/ chore/`; commit subjects Conventional.
- One commit per audit group, with the measurement that justifies it in the message.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `presentation/RetryPolicy.kt` | Public retry shape; `Transport` and `AutoRetry` instances |
| `shared/utils/RetryDelay.kt` | Back-off arithmetic: doubling, ceiling, jitter, clamp |
| `shared/utils/PathUtils.kt` | Lexical path normalisation and containment |
| `core/ports/ClockPort.kt` | Wall clock as a port, so pruning is testable |
| `core/ports/RemoteFileInfo.kt` | Core's own size-plus-validator type |
| `core/application/errors/TransitionFailure.kt` | Not found / refused / not persisted |
| `di/AutoRetryScheduler.kt` | The second retry loop, with its counter and back-off |
| `infrastructure/time/SystemClock.kt` + three actuals | `currentEpochMs` per platform |

**Deleted:** `examples/KioskDownloadManager.kt` — no Gradle task compiled it, nothing referenced
it, and its exhaustive `when` had been broken since 2.3.0.

**Modified (the load-bearing ones):** `DownloadAdapter`, `DownloadService`, `DownloadRepository`,
`DownloadTask`, `DownloadTaskDTO`, `NimbusAPI`, `NimbusDownloadPort`, `KtorDownloadAdapter`,
`DownloadTaskStore` (schema 3), `Nimbus.Builder`, `di/Module`.

---

### Task 1: A transfer that stops delivering is abandoned

Audit T-1. The one failure the library could not see: the response stays open, the task stays
`Downloading`, the permit stays taken — at the default concurrency of one, the whole queue.

**Files:** `DownloadAdapter`, `Nimbus.Builder`, `di/Module`, `KtorDownloadAdapter`, sample.

- [x] **Step 1: Establish that a guard here is not enough.** Read `ByteReadChannel.asSource()` in
      the Ktor sources rather than assuming: it waits inside `runBlocking`, so the read is not a
      suspension point and cancellation cannot end it.
- [x] **Step 2: Check where the engine reads its timeout from.** `OkHttpEngine` takes
      `HttpTimeoutCapability` off the request — so the adapter can impose one without the caller
      installing the plugin.
- [x] **Step 3: `withStallTimeoutMs`, measuring progress.** A conflated tick channel fed by each
      read that delivered bytes; a watchdog cancelling the attempt when a window passes with none.
- [x] **Step 4: The same deadline on the size request**, which runs on the caller's coroutine.
- [x] **Step 5: Socket timeout on every Ktor request**, 30 s under the library's 60 s.
- [x] **Step 6: Tests.** Stall detected and retried; partial kept; cost bounded to the timeout; a
      pause during a quiet transfer is still a pause; the size request stalls; and — in `jvmTest`,
      because the wait has to happen inside a read — a slow link that is not a stalled one.

**Verification:** `./gradlew build`. Found two more defects on the way: a port that delivered
everything and then hung was asked to resume from the end of a complete file (416 → truncation of
everything it had paid for), and calling `onSourceOpened` twice writes to a closed sink.

---

### Task 2: One retry policy, with a ceiling and jitter

Audit T-2, T-3, T-9. Measured first: 201 requests in 500 ms of virtual time, and 501 consecutive
416 truncations at full speed.

**Files:** `RetryPolicy`, `RetryDelay`, `AutoRetryScheduler`, `DownloadAdapter`, `Nimbus.Builder`,
`di/Module`, `NimbusLogEvent`, `DownloadProgressService`.

- [x] **Step 1: Probe both loops** and record the numbers; delete the probes.
- [x] **Step 2: `RetryPolicy` with two instances**, and `withMaxRetryAttempts` /
      `withRetryBaseDelayMs` kept as shorthands so existing callers inherit the ceiling.
- [x] **Step 3: `AutoRetryScheduler`** — the DI lambda promoted to a class with a per-url counter,
      reset when a download for that url finishes.
- [x] **Step 4: Cap consecutive 416 truncations at one.**
- [x] **Step 5: `AutoRetryScheduled` and `AutoRetryExhausted`.**
- [x] **Step 6: Tests**, including a deterministic `MidJitter` so the arithmetic can be asserted
      in milliseconds rather than as a range.

**Verification:** `./gradlew build`. One flake caught here: asserting that each wait exceeds the
previous fails at the ceiling, where two capped values are ordered by the dice. Assert each
against its own nominal value instead.

---

### Task 3: Nothing is written past the declared size

Audit S-1. Measured: 4 000 bytes written against a declared 1 000.

**Files:** `DownloadAdapter`, `PermanentDownloadErrorCause`, `DownloadStateStoreMappers`.

- [x] **Step 1: Bound each read by what is still owed.**
- [x] **Step 2: `BodyLongerThanDeclared(declaredBytes)`**, plus its mapper branch and a
      round-trip test — the number has to survive being flattened into a message.
- [x] **Step 3: Discard the partial**, because at that point its length is the shape
      `startDownload` treats as complete.
- [x] **Step 4: Tests**, asserting on what was *read* rather than what is on disk: once the
      partial is discarded the disk cannot show the cap.

---

### Task 4: Transitions under one lock, and flows that end

Audit T-4, T-5.

**Files:** `DownloadTaskRepository`, `DownloadRepository`, `DownloadService`,
`DownloadProgressService`, `TransitionFailure`, `NimbusErrorMappers`, fakes.

- [x] **Step 1: `transitionDownloadTask` and `readDownloadTask`** on the port, both taking a block
      that runs under the repository's lock.
- [x] **Step 2: Convert all ten mutation sites**, computing DTOs inside the block.
- [x] **Step 3: Stop handing the entity out at all** — bulk reads return DTO snapshots.
- [x] **Step 4: `pauseDownload` stops before it transitions**, with the state still read first so
      a task with nothing running is refused without being stopped.
- [x] **Step 5: A flow ends on a permanent failure** even with `autoStart`.
- [x] **Step 6: Tests**, including the ordering observed from inside the port's `stopDownload`.

---

### Task 5: Lifecycle, then schema 3

Audit A-1, T-6, S-2. Split in two commits; the schema changes are batched so the store version
moves once.

**Files:** `NimbusAPI`, `DownloadService`, `DownloadAdapter`, `DownloadPort`, `ClockPort`,
`SystemClock` ×4, `DownloadTask`, `DownloadTaskDTO`, `DownloadTaskStore`, `DownloadStore`,
mappers, `NimbusDownloadPort`, `KtorDownloadAdapter`.

- [x] **Step 1: `flush()` and `close()`**, and `stopAllDownloads` on the port. `close` returns
      `Unit`; the scope is cancelled only if Nimbus made it.
- [x] **Step 2: `ClockPort` with an expect/actual per platform**, following `UsableSpace`.
- [x] **Step 3: Timestamps on the task and the store, schema 3**, with the migration stamping
      what a v2 blob cannot know.
- [x] **Step 4: `pruneFinished`**, with its two safety rules: a task that never finished is never
      pruned, and a task with no finish time is left alone.
- [x] **Step 5: The resume validator end to end** — port shape, storage, `If-Range`,
      `RemoteFileChanged`, and `retryFailedDownload` comparing it before keeping a partial.
- [x] **Step 6: Tests** across all of it, plus the migration cases.

**Verification:** `./gradlew build`. The port change ripples through fourteen test fakes; do it
mechanically and let the compiler find them.

---

### Task 6: Hardening the surface

Audit S-3, S-4, S-5, T-8.

- [x] **Step 1: `withDownloadRoot`** with `PathUtils` and `PathOutsideDownloadRoot`.
- [x] **Step 2: Control characters refused in `filePath`**, as they already were in `fileName`.
- [x] **Step 3: `Checksum`'s constructor internal**, with `@ConsistentCopyVisibility`.
- [x] **Step 4: Builder options validated where they are written.**
- [x] **Step 5: Document what a logger forwards** — a signed URL's query string is a credential.

---

### Task 7: Ergonomics, then the leftovers

Audit A-2, A-3, A-4, then T-7, L-1, L-2, L-3, L-4.

- [x] **Step 1: `causeCode` and `isRetryable`.**
- [x] **Step 2: `fileName` optional**, defaulting to the last path segment.
- [x] **Step 3: Hash a complete file inside the job**, under the semaphore.
- [x] **Step 4: L-1 solved rather than measured** — `ContentDigest` counts what it consumed, and
      the partial is re-read only when that disagrees with the file's length.
- [x] **Step 5: The path index** (T-7), counted rather than collected.
- [x] **Step 6: `NimbusLogEvent.Unexpected`** (L-4), `isDownloaded`'s contract (L-3), and the cost
      of a blocking read (L-2).

---

### Task 8: Review, documents, release

- [x] **Step 1: Two review rounds absorbed.** Thirteen findings, twelve accepted; the rejection
      is argued in the spec and in `AutoRetryScheduler`'s KDoc.
- [x] **Step 2: Five edge cases that had no test** — the transition lock under real threads, the
      clock actuals, `RemoteFileChanged` end to end, pruning a file that is already gone, and the
      path arithmetic directly.
- [x] **Step 3: Bring the reference documents back in line.** `CLAUDE.md`'s structural sections
      had drifted while its decision table was kept current; `llms.txt` described a port that no
      longer exists. `AGENTS.md` needed nothing — it holds no rules by design.
- [x] **Step 4: Hand-written changelog**, folded into release-please's generated section on the
      release pull request.
- [x] **Step 5: Merge, tag, publish.** `v2.5.0`, both release jobs green.

**Verification:** 227 tests on JVM, 204 on iOS simulator and Android host, 53 on `nimbus-ktor`.
