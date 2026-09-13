# Field-audit hardening — design

Date: 2026-09-13
Status: implemented in 2.5.0
Target: 2.5.0 (breaks `NimbusDownloadPort`, `Checksum`'s constructor, and the store schema)

Landed as `19b0f81` (the checksum gate, which the audit found first) and `6f5727c` (everything
else). What follows is the reasoning; **As built** at the end records where the code went past
the design and how the open questions were settled.

## Problem

The library says what it is for: a kiosk or a signage player, downloading unattended for months,
on hardware nobody will visit. Every design decision in `CLAUDE.md` is argued against that
device. Nothing had ever audited the code against the same claim.

Twenty-two findings. Not a list of bugs — the interesting part is that they fall into three
shapes, and each shape is a way of being wrong that the existing tests could not see.

**A wait with no bound.** A stalled connection, an auto-retry loop with no delay, a 416 that
truncates for ever. Each is a state the library can enter and never leave, and none of them
looks like a failure from inside: the task is `Downloading`, the code is running, nothing throws.

**A fact asserted instead of checked.** The size from a `HEAD` drove the progress bar and the
disk guard and the final integrity check — and never bounded the writing. A resume assumed the
file at the origin was still the file the partial came from. An `expectedChecksum` was accepted
with no digest configured and then never compared. In each case the library states something it
has not established, and the failure surfaces somewhere far from the assumption.

**A failure that only exists in memory.** No way to commit before shutdown, no way to stop, no
way to know when a task finished, so a catalogue that only grows. All of it recoverable in the
sense that nothing is lost — and all of it invisible to the operator until a device is full or
stops updating.

The measurements that mattered, taken with throwaway probes before any code changed:

| Probe | Setup | Result |
|---|---|---|
| Auto-retry | port answers 503; `autoStart` on | 201 requests in 500 ms of virtual time |
| 416 loop | port answers 416 to every request | 501 truncations, 0 ms elapsed, no cap |
| Write cap | 1 000 bytes declared, 4 000 served | 4 000 written to disk |

The 500 ms in the first row is not back-off. It is two store-coalescing windows; the loop itself
waits for nothing.

## Decisions

### The stall guard measures progress, not elapsed time

A deadline on a transfer's duration is wrong for this library: a 2 GB asset over a congested
cell legitimately takes hours, and killing it is the failure, not the fix. What is never
legitimate is *no bytes at all* for a minute. So the copy loop reports each read that delivered
something, and the watchdog measures the gaps.

**A guard here can only cancel, and cancelling only unwinds a transport that suspends.** Ktor's
`ByteReadChannel.asSource()` waits inside `runBlocking`; the read is not a suspension point, so
a wedged socket outlives cancellation, `pauseDownload`, and the watchdog alike. That is why
`KtorDownloadAdapter` imposes a socket timeout of its own — 30 s, under the library's 60 s, so
the socket lets go first — and why it sets it **on the request** rather than on the client:
`OkHttpEngine` reads `HttpTimeoutCapability` from the request, so it holds whether or not the
caller installed the `HttpTimeout` plugin. A client without it is the default, and would have
been a silent single point of failure on every device.

### One retry policy, two instances, and a ceiling instead of a cap

The two loops answer different questions — seconds inside a download that stays `Downloading`,
minutes after it has failed — and had drifted into three unrelated shapes. `RetryPolicy` is the
one type; `Transport` and `AutoRetry` are the two instances.

Auto-retry is **unbounded by default**. A cap looks like the safe choice and is not: on an
unattended device, giving up permanently is what a technician's visit looks like. The fix for a
flood is the wait, not the surrender. A cap is available for callers who want one, and reports
`AutoRetryExhausted` when it is reached.

**Jitter is ±20 % and is not configurable.** The only thing a knob there would be used for is
switching it off, and a fleet that lost the same backend must not come back in lockstep. It is
clamped after the cap as well as before it, so a five-minute ceiling cannot produce six minutes.

### A 416 truncates once

The first 416 is not a failing transfer — it is a transfer that must start from a different
offset — so it truncates and restarts outside the retry budget. The attempt after that asks from
offset 0 and carries no `Range` header at all: a server that answers *that* with a 416 will
answer every one the same way. From the second consecutive refusal it is an ordinary temporary
failure, budgeted and backed off. The same branch now also handles `RemoteFileChanged`, whose
recovery is identical.

### Nothing is written past the declared size, and the partial is discarded

Each read is bounded by what is still owed. A body still arriving after the last owed byte ends
the download with `BodyLongerThanDeclared` — permanent, because the next attempt asks the same
question and each one would cost the whole oversized body again.

Discarding the partial matters more than the cap. By that point the file is exactly as long as
it was supposed to be, and that is precisely the shape `startDownload` treats as already
complete: a server answering a resume from byte zero would otherwise leave the wrong bytes at
the right length, and the next start would report them finished.

### Every state transition happens inside the repository

`DownloadTask` is a mutable entity shared by two sides that did not share a lock: the service,
which serialises per task, and the progress callbacks, which run on the download's own
coroutine. Read-mutate-save is three steps and every transition had a window.

`transitionDownloadTask(id, persist) { task -> … }` owns all of them. It returns whatever the
block produced, so a caller builds its DTO *inside* the lock rather than reading the entity back
afterwards, and `readDownloadTask` does the same for reads. The invariant is then structural
rather than remembered: **the entity never leaves the repository** — the port hands out
snapshots.

Ordering is the other half: `pauseDownload` stops the transfer before changing the state,
because `stopDownload` joins the job and after it returns nothing else can touch the task.

### A resume asks whether the file is still the same file

The size cannot tell a resumed file from a replaced one of the same length — a re-encode at the
same bitrate, a regenerated manifest — and appending the tail of the new file to the prefix of
the old produces exactly the right length and bytes that were never a file. Only a digest could
see it, and the digest is opt-in.

So the origin is asked what identifies *this version*: `getRemoteFile` reports an `ETag` or
`Last-Modified` alongside the size, it is stored with the task, and it goes back as `If-Range`.
A whole file in answer is `RemoteFileChanged`.

**This breaks the port, deliberately.** Overloads with defaults would have kept the old shape
compiling, at the price of two ways to say everything in an interface that exists to be
implemented once — the same trade the maintainer made for `Nimbus.Builder()` in 2.4.0. One
adapter ships with the library and it is updated here.

### Timestamps are stamped, never inferred

`pruneFinished` needs to know when a task finished, and two populations have no answer: tasks
from a store written before the field existed, and tasks finished on a device whose clock was
not set. Both decode as a number near zero, which reads as 1970 — and a first
`pruneFinished(30 days)` would take that as older than anything and delete every file the device
had.

A stamp from before this field could plausibly have been written is therefore **the absence of a
date, not an old one**, and is replaced at load with the first moment the clock is believable.
If the clock is not believable either, nothing is touched and the next boot tries again. In the
other direction a finish time in the future is a clock corrected backwards, and is never read as
an age.

### The rest, briefly

- **`withDownloadRoot` is opt-in and lexical.** A library cannot know where an app keeps its
  files, so it cannot default to a root; but on a kiosk the destination comes from a manifest,
  and the only check it faced was "no `..`". The check normalises and compares strings; a
  symlink under the root pointing elsewhere is not caught and the KDoc says so.
- **`Checksum`'s constructor is internal.** It accepted any string, so `Checksum(SHA256, "abc")`
  compiled and mismatched on every transfer for ever — a mismatch is temporary, so it retries.
- **`close()` stops, commits, releases**, in that order, and returns `Unit`: there is nothing a
  caller can do with a failure while the process is going away, and a close that can fail is one
  people wrap in a `try` and get wrong.
- **`causeCode`, not `code`.** Every `KError` already has `code`, and here it says which variant
  this is, not why. The new accessor unwraps the two causes that exist only to carry another.

## Rejected

- **"Only temporary failures should reach the auto-retry loop"** (review). Several permanent
  causes describe the *local* state — `LocalFileOversized`, `BodyLongerThanDeclared`,
  `InconsistentRangeResponse` — and the reset that loop performs is exactly what clears them.
  Skipping them would strand tasks that today heal themselves. A cause that really is permanent
  costs one round-trip and stops, because the preparation gets the same answer and does not
  reschedule.
- **Replacing `KResult` with a Nimbus-owned result type** (audit A-5). A consumer cannot use this
  library without depending on `explicitarchitecture` and following its versions. Real friction,
  and a 3.0 decision, not a hardening one.
- **A `NimbusLogEvent` for every swallowed throwable.** Only the two places inside a download job
  where a `Throwable` would otherwise be flattened to a message emit `Unexpected`. The storage
  plugin's catch-alls do not: they have no logger, and giving them one to report conditions that
  are already typed is noise.

## As built

Five places where the implementation went past the design, all discovered by writing the test
rather than by reading the code:

- **The zero-read spin was worse than documented.** Restoring `continue` to prove the regression
  test worked showed that the loop does not merely spin: it reaches no suspension point, so
  neither a test timeout nor cancellation can interrupt it. A CI job times out; a device burns a
  core. The Gradle daemon had to be killed to get out of it.
- **`close()` needed an admission gate, not a flag** (found by review). Reading `isClosed` and
  then proceeding is not the same as being admitted: a call that read it a moment earlier
  carried on past the flush and the scope cancellation. Admission and the in-flight count now
  move under one lock, and `close` drains what is inside — bounded at 5 s, because one of those
  calls may be waiting on a server that never answers.
- **The clock note became code.** It was on the list as documentation. Writing it made the
  scenario concrete: a board with no battery-backed clock stamps a whole night's downloads 1970,
  and the first prune after the sync deletes them. Repair at load, and never read a future stamp
  as an age.
- **The path index had to count, not collect.** A set answers "does *a* task use this path"; the
  scan it replaced answered "does *any*". Two tasks cannot normally share a destination, but a
  store can be older than that rule or corrupt, and with a set the first delete frees a path the
  second is still writing to.
- **L-1 was solved rather than measured.** The audit left the cost of re-priming the digest as a
  soak measurement. `ContentDigest` counting what it has consumed makes the question decidable
  without measuring: re-read only when the count and the file's length disagree.

The two review rounds contributed thirteen findings, twelve accepted. The one rejected is above.

## Open questions

- **A symlink under `withDownloadRoot`.** Closing it needs a `realpath` per platform, and the
  destination usually does not exist when the check runs. Left as a documented limit.
- **The drain residual in `close()`.** After five seconds a straggler can still write after the
  flush. Making the timeout configurable adds a knob nobody can tune; leaving it is a bounded
  window in a shutdown path.
- **`ChecksumAlgorithmMismatch` is unreachable** while `DigestAlgorithm` has one entry. A
  tripwire test on the entry count fails the day a second lands, saying what it now owes.
- **Multi-process** is unchanged and still unsupported; see
  `2026-09-11-multi-process-topology.md`.
