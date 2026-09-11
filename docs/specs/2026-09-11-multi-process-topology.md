# Running Nimbus in a separate process — design notes

Date: 2026-09-11
Status: deferred — recorded for a future release, not scheduled
Target: unscheduled

Nimbus runs in the app's process today and that is the only topology it supports. The goal is to
let the integrator choose where the instance lives — typically a dedicated downloader process on
Android, so a crash in the UI does not take the transfers down with it. This document records
what that requires and, more usefully, what it must **not** be built as. It is analysis, not a
commitment.

## Two topologies, and only one of them is safe

**A — one Nimbus, one process, everyone else over IPC.** The instance lives in exactly one
process, typically a bound Service. Other processes send it commands and observe its state. The
store is only ever touched by one process.

**B — one Nimbus per process, all on the same store path.** Each process constructs its own
`Nimbus.Builder()…init()` pointing at the same `withDownloadManagerPath`.

The choice worth giving the integrator is **where the single instance lives**, not how many there
are. B must be made to fail, not supported.

## Why B cannot be fixed with a lock

A file lock around the store is the obvious answer and it is the wrong one. Four reasons, in
increasing order of how fatal they are:

1. **The store is a single blob.** `StoreManager` serialises the whole `DownloadStore` and
   commits it with an atomic move. Two processes do not interleave at task granularity — the
   later
   writer replaces the entire catalogue of the earlier one.
2. **Two processes download to the same `filePath`.** Nothing coordinates the sink.
3. **The concurrency limit stops meaning what it says.** `DownloadAdapter.kt:71` is
   `Semaphore(concurrencyLimit)`, constructed per instance. `withConcurrencyLimit(3)` across N
   processes is 3×N concurrent transfers. On a metered link — the case the option exists for —
   that is the opposite of the requested behaviour. The same applies to
   `DownloadService.withOperationLock`, which serialises calls per URL *within one instance*.
4. **There is no cache invalidation, and no place to put one.** This is the one that closes the
   question. Every read of the catalogue is served from memory: `getAllDownloadTask()` is
   `mutex.withLock { tasks.toMap() }` (`DownloadRepository.kt:159-161`) and never reads the disk.
   `StoreManager.mutex` (`StoreManager.kt:55`) is a `kotlinx.coroutines.sync.Mutex` — in-process
   by construction. So even with perfect cross-process file locking, process A's in-memory
   catalogue is stale the instant B commits, and **nothing exists that could tell it.** Adding
   that means a change-notification channel between processes — which is the IPC layer of
   topology
   A, built anyway and now in addition to the locking.

Nimbus is designed around a single instance holding the truth in memory and treating the disk as
a cache it can re-derive from. That is a good design and it is why B is not a missing feature but
a contradiction.

## What topology A needs

Nothing in `StoreManager`. One process writes, the existing `Mutex` is sufficient, and the
coalesced ProtoBuf blob stays the right shape for the workload. The storage layer is not what
stands between Nimbus and multi-process support.

What stands between them is **marshalling**. Crossing a process boundary means moving
`DownloadTaskDTO` and `DownloadState` across it, and today none of the public types can be moved:
`DownloadTaskDTO`, `DownloadState`, `Checksum` and `DownloadError` carry no `@Serializable`.

Adding the annotation does not fix it. `DownloadState.Failed` carries a `DownloadError`, which
carries a `KError`, and `KError` is a `public open class` in the external `explicitarchitecture`
dependency with no serialization support. The typed error chain cannot be serialized as it
stands.

### Nimbus already solved this once, for the disk

`DownloadStateStore.Failed` does not persist the typed error. It flattens it:

```kotlin
val errorCode: String
val errorMessage: String
val errorCause: Failed? = null   // recursive
```

and `DownloadStateStoreMappers` rebuilds the typed cause with a `when` over the string code:

```kotlin
"server_error" -> TemporaryDownloadErrorCause.ServerError(this.errorMessage.parseStatusCode())
```

where `parseStatusCode()` is `trimEnd('.').split(" ").lastOrNull()?.toIntOrNull() ?: 0` — it
takes the last whitespace-separated token of the human-readable message. Reword `"Server error:
503"` to `"Server 503 error"` and every persisted status code silently becomes `0`. Nothing in
the type system objects, and the message is prose written for a log.

This works, it is tested, and it is `internal`. An integrator building IPC has to reinvent it and
will not reinvent it better. It also means the error taxonomy is already encoded twice — once as
sealed classes, once as string codes plus message parsing.

### So the work is a public wire form, not an annotation

Promote what the store models already do into a public, versioned wire representation with
`toWire()` / `fromWire()` mappers, and let both the disk store and IPC use it. That removes the
duplicate taxonomy rather than adding a third copy, and it is the piece an integrator cannot
write for themselves without guessing at internals.

It should carry a status code as a field. Reconstructing an `Int` by parsing English out of a log
message is a defect the persistence layer can absorb — a wrong status code on a restored `Failed`
state is cosmetic — but across an IPC boundary it becomes the value a caller branches on.

### And a guard, so the wrong topology fails loudly

A lock file beside the store carrying an instance id, checked at `init()`, turning B from silent
catalogue loss into a clear initialisation failure. Small, and it is what makes "choose your
topology" safe by construction rather than by documentation.

## What must not be built

An Android `Service` + AIDL layer inside Nimbus. It is a Kotlin Multiplatform library and that is
Android-only, consumer-side code. The shape of the IPC — AIDL, `Messenger`, a bound Service, a
local socket — depends on the app and is the integrator's decision. Nimbus's job is to make the
boundary **crossable**, not to cross it on their behalf.

## Open questions

Both need answering before any of the above is worth building.

- **Observe-only, or bidirectional command?** If the other process only needs to watch state, the
  wire form is the whole feature and nothing else is required. If it needs to *drive* Nimbus —
  enqueue, pause, cancel — then the command path needs designing, and that is where the
  interesting failure mode lives: what happens when the Nimbus process is killed with a command
  in flight. Android will kill a bound Service under memory pressure, on a kiosk that has been up
  for days, with no user present to retry. A command that is neither applied nor reported is the
  failure this design has to answer for, and it has no answer yet.
- **Is a separate process what the integrators actually want?** The motivation is usually
  surviving a UI crash. A foreground Service in the same process, with the download scope
  outliving the Activity, achieves that without any of this. Worth confirming against the two
  kiosk deployments before building an IPC surface — the cheaper answer may already be the right
  one.

## What this does not change

- `StoreManager`, its ProtoBuf blob, the coalescing, and the two-phase commit: all unchanged. The
  storage layer is correctly sized for the workload and is not the obstacle here.
- The public API surface: nothing added or moved by this document.
- The single-process topology, which remains supported and is what every consumer runs today.
