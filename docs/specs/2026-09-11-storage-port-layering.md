# Storage port layering — design

Date: 2026-09-11
Status: implemented in 2.3.0
Target: 2.3.0 (internal only; no public API change)

Landed as `91907b5` (the two defects below) and `c4b319e` (the layering). What follows is the
design as written before implementation; **As built** at the end records where the code went
further and how the open questions were settled.

## Problem

`DownloadService` imports `infrastructure`.

```
// core/application/DownloadService.kt
L17  import ...infrastructure.plugins.errors.storage.CreateFileError
L18  import ...infrastructure.plugins.errors.storage.DeleteFileError
L19  import ...infrastructure.plugins.errors.storage.GetUsableSpaceError
L20  import ...infrastructure.plugins.ports.storage.NimbusStoragePort
```

The architecture rule is `presentation → core ← infrastructure`, dependencies inward only. These
four imports point outward. They are the only ones in the whole of `core/` that do — a grep for
`^import io.github.giovanniandreuzza.nimbus.infrastructure` under `core/` returns these four
lines and nothing else. There are no import cycles anywhere in the module. One interface leaks,
at one call site, and everything else holds.

The leak is not an oversight about where a file lives. It is a layer that was built for the
download path and never built for the storage path.

## Why the download path does not have this problem

Downloads cross the same boundary — a public plugin interface that third parties implement,
called from core — and cross it cleanly, because there are three interfaces instead of one.

```
DOWNLOAD                                       STORAGE
core/ports/DownloadPort.kt        (internal)   ── does not exist ──
    ↑ implements
infrastructure/ports/DownloadAdapter.kt        ── does not exist ──
    ↓ delegates to
plugins/ports/NimbusDownloadPort  (public)     plugins/ports/NimbusStoragePort  (public)
```

`DownloadPort` is `internal`, lives in `core/ports/`, and speaks domain language:
`getFileSizeToDownload(fileUrl)`, `startDownload(DownloadTaskDTO)`. `DownloadAdapter` implements
it and is the only thing in the codebase that touches `NimbusDownloadPort`, which speaks
transport language: `downloadFile(fileUrl, offset, onSourceOpened)`. Core never sees the plugin.
That is an anti-corruption layer, and it is why `DownloadService` can depend on downloads without
depending on HTTP.

The error types confirm this was deliberate rather than accidental. `DownloadError` and
`GetFileSizeError` are `public`, and they live in `core/application/errors/`. The reason is
written into `NimbusDownloadPort.kt:20-27`:

> Both types are `public` precisely because they are part of the implementor contract, **not
> because they are part of the public API surface.**

So the plugin depends inward on core's error types. The direction is right and the intent is
documented.

`NimbusStoragePort` had the identical need — a public interface for third-party implementors,
whose error types must therefore also be public — and resolved it the opposite way. Its eight
error types live in `infrastructure/plugins/errors/storage/`. When core needs them, core must
reach outward.

That is the whole of the defect. Not a misplaced file; a missing layer, and an error family
placed on the wrong side of it because there was no layer to place it on.

## What core actually needs

`NimbusStoragePort` has eight methods. Core uses four.

| method | `DownloadService` | `DownloadAdapter` | `StoreManager` | `DownloadRepository` |
|---|---|---|---|---|
| `size` | ✓ L128, L640 | ✓ L76 | | ✓ L89 |
| `create` | ✓ L607 | ✓ L202 | ✓ L244 | |
| `delete` | ✓ L432, L478, L598 | ✓ L226 | ✓ L209 | |
| `usableSpaceBytes` | ✓ L655 | | | |
| `exists` | | ✓ L145 | ✓ L96 | |
| `sink` | | ✓ L342 | ✓ L282 | |
| `source` | | | ✓ L315 | |
| `atomicMove` | | | ✓ L124 | |

Only `DownloadService` sits in `core/`. The other three consumers are in `infrastructure/ports/`,
`frameworks/store/` and `infrastructure/repositories/` — all outside core, all entitled to depend
on a plugin port directly. They are not part of this problem and this design does not touch them.

So the core-facing surface is four methods: `size`, `create`, `delete`, `usableSpaceBytes`. Never
`sink`, `source`, `exists` or `atomicMove`.

And the error discrimination core performs on those four is narrower still. Reading every failure
branch in `DownloadService`, it distinguishes exactly three things:

- `DeleteFileError.FileNotFound` — benign, continue (L433, L479, L599)
- `CreateFileError.FileAlreadyExists` — benign, continue (L608)
- `GetUsableSpaceError.Unsupported` — benign, skip the headroom check (L660)

Everything else becomes `PermanentNimbusErrorCause.StorageError(it)`, an opaque passthrough. A
`size` failure is not discriminated at all — `partialBytesOnDiskForPath` (L640-644) collapses it
to `0L`.

Three benign predicates and an opaque payload. That is the entire storage contract core has.

Worth stating because it bounds the blast radius: the leak stops at core.
`PermanentNimbusErrorCause.StorageError` takes `KError`, the generic base type, not a storage
error type. `PermanentNimbusErrorCause.kt` imports nothing from `infrastructure`. The public
presentation surface is already clean and stays clean under every option below.

## Design

Mirror the download path. Two new internal files, nothing public changes.

**`core/ports/StoragePort.kt`** — internal, core-owned, four methods:

```kotlin
internal interface StoragePort {
    fun size(path: String): KResult<Long, StoragePortError>
    fun create(path: String): KResult<CreateOutcome, StoragePortError>
    fun delete(path: String): KResult<DeleteOutcome, StoragePortError>
    fun usableSpaceBytes(path: String): KResult<Long?, StoragePortError>
}
```

**`infrastructure/ports/StorageAdapter.kt`** — internal, implements `StoragePort`, delegates to
`NimbusStoragePort`, translates its eight error families into `StoragePortError`. Exactly the
role `DownloadAdapter` plays for `NimbusDownloadPort`.

`NimbusStoragePort` and all eight of its error types stay precisely where they are. Third-party
implementors see no change whatsoever.

### The three benign cases become success values, not exempted errors

This is the part that is worth more than the layering fix.

Today each benign outcome is a failure the caller has to remember to exempt:

```kotlin
nimbusStoragePort.delete(path).onFailure {
    if (it !is DeleteFileError.FileNotFound) {
        return@withOperationLock Failure(...)
    }
}
```

That shape appears four times — L433, L479, L599 for `delete` and L608 for `create` —
hand-written, each time correct, each time independently forgettable. A fifth call site that
omits the exemption turns a file that was already absent into a user-visible permanent error, and
nothing in the type system objects.

Modelling the benign outcomes as successes makes the omission unrepresentable:

```kotlin
internal enum class CreateOutcome { Created, AlreadyExists }
internal enum class DeleteOutcome { Deleted, NotFound }
```

`usableSpaceBytes` returns `Long?` with `null` meaning the platform cannot answer — matching
`GetUsableSpaceError.Unsupported` at L660, which today is a failure that must be caught and
turned back into success. Every remaining `StoragePortError` is a genuine failure, and a bare
`onFailure` is then always the right handling.

`StoragePortError` itself can stay small. Core inspects nothing beyond the three predicates
above, so a sealed family carrying a `KError` cause plus enough structure for
`PermanentNimbusErrorCause.StorageError` is sufficient. Do not reproduce the eight infrastructure
families in core; they describe filesystem outcomes, and core does not reason about filesystems.

## Two defects found alongside, independent of the above

Both are self-contained and neither depends on the layering change. Both fixed in `91907b5`.

**`FileSystemNimbusStorageAdapter` mislabels its catch-all.** Every one of its eight methods ends
this way — L34, L57, L78, L96, L114, L133, L153, L181 — and every one maps the catch-all to a
permission-denied variant:

```kotlin
} catch (e: IOException) {
    Failure(CreateFileError.IOError(ioError(e, "...")))
} catch (t: Throwable) {
    Failure(CreateFileError.WritePermissionDenied(writePermissionError(t)))
}
```

Anything that is not an `IOException` is reported to the caller as a permission denial, including
`Error` subtypes such as `OutOfMemoryError`. It is not an isolated slip in one method; it is the
adapter's uniform house style, which is why it reads as intentional and has survived review.

`KtorDownloadAdapter` handles the same situation correctly: it catches `CancellationException`
explicitly and rethrows (L65, L113, L167), then maps the rest to `UnexpectedError` rather than to
a specific named cause. These methods are not `suspend`, so cancellation is not the live risk
that it would be in the Ktor adapter — the defect is the label. An unexpected failure should map
to an unexpected-failure cause. The storage error families have no such variant today; adding one
is part of this fix.

**`DownloadAdapter.kt:226` discards a `KResult`.** In `truncateLocalFileAfter416`:

```kotlin
nimbusStoragePort.delete(filePath)          // result ignored
nimbusStoragePort.create(filePath).onFailure { ... }   // result handled
```

If the delete fails, the subsequent `create` returns `FileAlreadyExists`, which maps to
`TemporaryDownloadErrorCause.TruncateRace` and retries. The behaviour is therefore not wrong — it
self-corrects into a retry — but the retry is attributed to a race that did not happen, and the
actual delete failure never reaches the logger. Handle the result and map a genuine delete
failure to `PermanentDownloadErrorCause.StorageError`.

## What this does not fix

`DownloadAdapter` re-derives retryability from storage errors by hand, because the storage error
families have no `Temporary` / `Permanent` split the way `DownloadError` and `GetFileSizeError`
do. The translation is written out per call site:

- L342-346 — a `sink` failure becomes `TemporaryDownloadErrorCause.FileNotAccessible`
- L227-246 — `CreateFileError.FileAlreadyExists` becomes
  `TemporaryDownloadErrorCause.TruncateRace`; the other three `CreateFileError` variants become
  `PermanentDownloadErrorCause.StorageError`

That `when` block is a Temporary/Permanent split written longhand because the type could not
carry it. Every new storage call site in `DownloadAdapter` must write it again.

This design does not address it, deliberately. `DownloadAdapter` is infrastructure and its direct
use of `NimbusStoragePort` violates no rule; routing it through `StorageAdapter` as well is a
larger change with a different justification, and bundling the two would make each harder to
review. It is recorded as an open question below.

## What does not change

- `NimbusStoragePort` — signature, package, visibility: untouched.
- The eight storage error types — package, visibility: untouched.
- `FileSystemNimbusStorageAdapter` — still implements the plugin port directly; gains only the
  catch-all fix.
- `NimbusAPI`, `NimbusError`, `PermanentNimbusErrorCause` — untouched; already clean.
- `StoreManager`, `DownloadRepository`, `DownloadAdapter` — keep their direct plugin access.
- Public API surface — nothing added, nothing removed, nothing moved.

## Testing

- A `StoragePort` fake in `jvmTest` that `DownloadService` can be constructed against without any
  filesystem, replacing the current need to stand up a real `NimbusStoragePort`. This is the
  practical payoff of the internal port and should be demonstrated by rewriting at least one
  existing `DownloadServiceInitRetryTest` case against it.
- `StorageAdapter` translation tests: each of the eight `NimbusStoragePort` error families maps
  to the intended `StoragePortError`, and the three benign outcomes surface as
  `CreateOutcome.AlreadyExists`, `DeleteOutcome.NotFound` and `usableSpaceBytes == null` rather
  than as failures.
- A regression test that a non-`IOException` throwable from the filesystem does not surface as
  `WritePermissionDenied`.
- An architecture test asserting that no file under `core/` imports
  `io.github.giovanniandreuzza.nimbus.infrastructure`. This is the check that would have caught
  the original defect, and it is cheap — a source scan, no runtime. Without it the leak recurs
  the next time core needs something only a plugin offers.

## Release

2.3.0, alongside the content digest work, or any release — it is invisible from outside the
module. Purely internal: two new `internal` files, one `internal` constructor parameter change on
`DownloadService`, one wiring change in `di/Module.kt`, and two localised bug fixes. No public
type is added, removed, or moved, so there is nothing for consumers to migrate and nothing for
the release notes beyond a line noting the internal restructuring.

## Open questions

Neither blocks implementation. Both were settled in the implementation — see *The open questions,
as settled* below.

- **Whether `DownloadAdapter` should also consume `StoragePort`.** It would centralise the
  hand-written retryability mapping described above, and would let `StoragePortError` carry a
  `Temporary` / `Permanent` split the way every other error family in the module does. Against
  it: `DownloadAdapter` needs `sink` and `exists`, which core does not, so `StoragePort` would
  grow past the four methods core actually uses and stop being a core-shaped interface. The
  honest resolution may be two internal ports with different shapes rather than one shared one.
  Decide after the core port exists and its real shape is visible, not before.
- **Whether the eight public storage error types should eventually move into
  `core/application/errors/`,** matching `DownloadError` and `GetFileSizeError` and making the
  storage plugin depend inward exactly as the download plugin does. That is the fully symmetric
  end state. It is not in this design because it is a package change on public types — source-
  and binary-breaking for any third-party `NimbusStoragePort` implementor — and the
  anti-corruption layer proposed here removes the architectural motivation for doing it. Revisit
  only if a second reason appears; do not break implementors for symmetry alone.

## As built

The implementation follows this design. Three things are worth recording because the code decided
them and this document could not.

**`StoragePortError` is one case, not a sealed family.** The design said "a sealed family
carrying a `KError` cause plus enough structure for `PermanentNimbusErrorCause.StorageError`".
The implementation went further and made it a single `data class` wrapping the plugin's own
error, on the reasoning that core draws no distinction at all once the three benign outcomes are
success values — every remaining failure reached `PermanentNimbusErrorCause.StorageError` unread.
Anything more would describe filesystem outcomes to a layer with no use for them, and nothing is
lost because `cause` carries the plugin error out to the caller intact.

**The catch-all fix needed a seam this design did not anticipate.** The Testing section below
asks for "a regression test that a non-`IOException` throwable from the filesystem does not
surface as `WritePermissionDenied`". That test could not be written: `kotlinx.io`'s `FileSystem`
is sealed, so it cannot be implemented outside its own module and cannot be substituted. The
adapter now works through an internal `NimbusFileSystem` interface (`SystemNimbusFileSystem` in
production), which is what makes the branch reachable at all. That the branch was untestable is
the best available explanation for how the same mislabelling reached all eight methods and
survived review.

Also found while fixing it: `CreateStoreError` was `public` by accident — it is not in the public
surface list and never escapes `frameworks/store`. Now `internal`.

### The open questions, as settled

- **Should `DownloadAdapter` consume `StoragePort`?** No, and it still does not — it uses
  `NimbusStoragePort` directly, as do `StoreManager` and `DownloadRepository`. The longhand
  retryability mapping described under *What this does not fix* is therefore still there, by the
  argument given in that section: `DownloadAdapter` needs `sink` and `exists`, which core does
  not, so serving it would have grown `StoragePort` past the four operations core performs and
  stopped it being a core-shaped interface.
- **Should the eight public storage error types move into `core/application/errors/`?** No. They
  remain in `infrastructure/plugins/errors/storage/`, all eight, unmoved. The anti-corruption
  layer removed the motivation, and moving them would have been a package change on public types
  — source- and binary-breaking for any third-party implementor, for symmetry alone.

### The architecture test

`nimbus/src/jvmTest/.../ArchitectureTest.kt` asserts no file under `core/` carries an `import
io.github.giovanniandreuzza.nimbus.infrastructure`. It was red before `c4b319e`, naming exactly
the four imports this document opens with. It is a source scan rather than a runtime check,
because the rule is about what the code is allowed to name.
