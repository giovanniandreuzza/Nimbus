package io.github.giovanniandreuzza.nimbus.infrastructure.ports

import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Failure
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.KResult
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Success
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.getOr
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.onFailure
import io.github.giovanniandreuzza.nimbus.core.application.dtos.DownloadTaskDTO
import io.github.giovanniandreuzza.nimbus.core.application.errors.DownloadError
import io.github.giovanniandreuzza.nimbus.core.application.errors.GetFileSizeError
import io.github.giovanniandreuzza.nimbus.core.application.errors.PermanentDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryDownloadErrorCause
import io.github.giovanniandreuzza.nimbus.core.application.errors.TemporaryGetFileSizeErrorCause
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadPort
import io.github.giovanniandreuzza.nimbus.core.ports.RemoteFileInfo
import io.github.giovanniandreuzza.nimbus.core.ports.ContentDigestPort
import io.github.giovanniandreuzza.nimbus.core.ports.DownloadProgressCallback
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.ContentDigest
import io.github.giovanniandreuzza.nimbus.infrastructure.digest.readFullyInto
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.CreateFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.DeleteFileError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.errors.storage.GetFileSinkError
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.download.NimbusDownloadPort
import io.github.giovanniandreuzza.nimbus.presentation.Checksum
import io.github.giovanniandreuzza.nimbus.presentation.DigestAlgorithm
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogEvent
import io.github.giovanniandreuzza.nimbus.presentation.NimbusLogger
import io.github.giovanniandreuzza.nimbus.presentation.RetryPolicy
import io.github.giovanniandreuzza.nimbus.shared.utils.allowsAttempt
import io.github.giovanniandreuzza.nimbus.shared.utils.delayForAttempt
import io.github.giovanniandreuzza.nimbus.infrastructure.plugins.ports.storage.NimbusStoragePort
import io.github.giovanniandreuzza.nimbus.shared.utils.getDownloadProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.io.InternalIoApi
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Download Adapter.
 *
 * Drives HTTP downloads concurrently up to [concurrencyLimit] active downloads
 * at a time. Each download runs in a child coroutine of [downloadScope] and
 * reports progress via [downloadProgressCallback].
 *
 * Job registration uses [CoroutineStart.LAZY] under [jobsMutex] to avoid races
 * where a second [startDownload] observes no job before the first registers.
 *
 * @author Giovanni Andreuzza
 */
internal class DownloadAdapter(
    concurrencyLimit: Int,
    private val downloadScope: CoroutineScope,
    private val downloadProgressCallback: DownloadProgressCallback,
    private val nimbusStoragePort: NimbusStoragePort,
    private val nimbusDownloadPort: NimbusDownloadPort,
    private val bufferSize: Long,
    private val notifyEveryBytes: Long,
    /** Retries within one download: see [RetryPolicy.Transport]. */
    private val transportRetry: RetryPolicy,
    /**
     * How long a transfer may make no progress at all before it is abandoned and retried.
     * Null disables the guard entirely. See [runWithStallWatchdog].
     */
    private val stallTimeoutMs: Long?,
    /** When non-null, every transfer is digested with it. Null means no hashing at all. */
    private val digestAlgorithm: DigestAlgorithm? = null,
    /** Hashes a file that is already on disk, for the case where nothing is transferred. */
    private val contentDigestPort: ContentDigestPort,
    /** Told about throwables this adapter did not expect, stack and all. */
    private val logger: NimbusLogger? = null,
    /** Injectable so a test can assert the back-off arithmetic instead of a range. */
    private val random: Random = Random.Default
) : DownloadPort {

    private val semaphore = Semaphore(concurrencyLimit)
    private val jobsMutex = Mutex()
    private val downloadJobs = mutableMapOf<String, Job>()

    /**
     * The size request gets the same deadline as a transfer, for the same reason.
     *
     * It is the first thing every enqueue and every retry does, and it runs on the caller's
     * coroutine — so a server that accepts the connection and then answers nothing does not
     * fail a download, it suspends whoever asked for one. On a device reconciling a manifest
     * that is the loop that keeps the whole catalogue up to date.
     */
    override suspend fun getRemoteFile(fileUrl: String): KResult<RemoteFileInfo, GetFileSizeError> {
        val timeout = stallTimeoutMs ?: return askOrigin(fileUrl)

        return withTimeoutOrNull(timeout) { askOrigin(fileUrl) }
            ?: Failure(
                GetFileSizeError.TemporaryError(
                    TemporaryGetFileSizeErrorCause.TransportFailure(
                        KError(
                            code = "size_request_stalled",
                            message = "The size request made no progress for $timeout ms."
                        )
                    )
                )
            )
    }

    private suspend fun askOrigin(fileUrl: String): KResult<RemoteFileInfo, GetFileSizeError> =
        when (val remote = nimbusDownloadPort.getRemoteFile(fileUrl)) {
            is Success -> Success(
                RemoteFileInfo(
                    sizeBytes = remote.value.sizeBytes,
                    validator = remote.value.validator
                )
            )

            is Failure -> remote
        }

    @OptIn(InternalIoApi::class)
    override suspend fun startDownload(downloadTask: DownloadTaskDTO): KResult<Unit, DownloadError> {
        val id = downloadTask.id

        val job = downloadScope.launch(
            context = createExceptionHandler(id, downloadTask.fileUrl),
            start = CoroutineStart.LAZY
        ) {
            try {
                semaphore.withPermit {
                    // The already-complete case runs here rather than on the caller's
                    // coroutine, because with a digest configured it is not a check — it reads
                    // and hashes the whole file. Two gigabytes off eMMC is twenty seconds of a
                    // caller suspended inside what looks like a start, and on a reconciliation
                    // loop that walks a manifest it is twenty seconds per asset with nothing
                    // else moving. Under the permit it also counts against the concurrency
                    // limit, which is what the limit is for.
                    val onDisk = when (val r = nimbusStoragePort.size(downloadTask.filePath)) {
                        is Success -> r.value
                        is Failure -> null
                    }
                    if (onDisk == downloadTask.fileSize) {
                        finishCompleteFileOnDisk(downloadTask, id)
                    } else {
                        runDownloadJob(downloadTask, id)
                    }
                }
            } finally {
                removeJob(id)
            }
        }

        jobsMutex.withLock {
            if (downloadJobs.containsKey(id)) {
                job.cancel()
                return Success(Unit)
            }
            downloadJobs[id] = job
        }
        job.start()
        return Success(Unit)
    }

    override suspend fun stopDownload(downloadId: String) {
        val job = removeJob(downloadId)
        job?.cancelAndJoin()
    }

    override suspend fun stopAllDownloads() {
        // Emptied under the lock, then joined outside it: a job's own `finally` deregisters
        // itself, so holding the lock while waiting for one would deadlock against it.
        val running = jobsMutex.withLock {
            val all = downloadJobs.values.toList()
            downloadJobs.clear()
            all
        }
        running.forEach { it.cancelAndJoin() }
    }

    private suspend fun runDownloadJob(
        downloadTask: DownloadTaskDTO,
        id: String
    ) {
        try {
            val bytesAlreadyDownloaded = resolvePartialBytesOnDisk(downloadTask, id) ?: return
            if (!ensureDownloadTargetReady(downloadTask.filePath, id)) return

            val transfer = downloadWithRetry(downloadTask, id, bytesAlreadyDownloaded) ?: return
            if (!verifyFileIntegrity(downloadTask, id, transfer.checksum)) return

            downloadProgressCallback.onDownloadFinished(id, transfer.checksum)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.log(NimbusLogEvent.Unexpected(downloadTask.fileUrl, e))
            val error = unexpectedDownloadError(e)
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.UnexpectedError(error))
            )
        }
    }

    /**
     * Resolves how many bytes are already on disk for resume.
     *
     * @return `null` if the situation is unreadable or invalid (failure already notified).
     */
    private suspend fun resolvePartialBytesOnDisk(
        downloadTask: DownloadTaskDTO,
        id: String
    ): Long? {
        val exists = nimbusStoragePort.exists(downloadTask.filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return null
        }
        if (!exists) return 0L

        val size = nimbusStoragePort.size(downloadTask.filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return null
        }
        return when {
            size > downloadTask.fileSize -> {
                notifyFailureAndCleanup(
                    id,
                    DownloadError.PermanentError(PermanentDownloadErrorCause.LocalFileOversized)
                )
                null
            }

            else -> size
        }
    }

    /**
     * Verifies the file on disk against what the caller expects of it.
     *
     * Size first: it is free, and a truncated transfer should report the more specific
     * cause rather than the digest mismatch that follows from it.
     *
     * @param checksum what the transferred bytes hashed to, when a digest was computed.
     */
    private suspend fun verifyFileIntegrity(
        downloadTask: DownloadTaskDTO,
        id: String,
        checksum: Checksum?
    ): Boolean {
        val actualSize = when (val r = nimbusStoragePort.size(downloadTask.filePath)) {
            is Success -> r.value
            is Failure -> 0L
        }
        if (actualSize != downloadTask.fileSize) {
            notifyFailureAndCleanup(
                id,
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileIntegrityMismatch)
            )
            return false
        }

        val expected = downloadTask.expectedChecksum
        if (expected != null && checksum != null && expected != checksum) {
            // Temporary, never permanent. A mismatch is a statement about this transfer —
            // a corrupted proxy response, a truncated body a correct Content-Length hid, a
            // cache serving something stale — not about the file at the origin. Calling it
            // permanent would mark the download unrecoverable and leave the caller to
            // delete and refetch, which is the pattern this feature exists to end.
            notifyFailureAndCleanup(
                id,
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.ChecksumMismatch)
            )
            return false
        }
        return true
    }

    private suspend fun ensureDownloadTargetReady(filePath: String, id: String): Boolean {
        val exists = nimbusStoragePort.exists(filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return false
        }
        if (exists) return true

        nimbusStoragePort.create(filePath).onFailure { error ->
            val mappedError = when (error) {
                CreateFileError.FileAlreadyExists -> return true
                is CreateFileError.IOError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(
                        error
                    )
                )

                is CreateFileError.ReadPermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is CreateFileError.WritePermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is CreateFileError.UnexpectedError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return false
        }
        return true
    }

    private suspend fun truncateLocalFile(filePath: String, id: String): Boolean {
        // A delete that genuinely failed must not be mistaken for the race below: the
        // create that follows would report FileAlreadyExists, the download would retry as
        // if two writers had collided, and the real reason would never reach the logger.
        nimbusStoragePort.delete(filePath).onFailure { error ->
            if (error !is DeleteFileError.FileNotFound) {
                notifyFailureAndCleanup(
                    id,
                    DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
                )
                return false
            }
        }
        nimbusStoragePort.create(filePath).onFailure { error ->
            val mappedError = when (error) {
                is CreateFileError.IOError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(
                        error
                    )
                )

                is CreateFileError.ReadPermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is CreateFileError.WritePermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                CreateFileError.FileAlreadyExists -> DownloadError.TemporaryError(
                    TemporaryDownloadErrorCause.TruncateRace
                )

                is CreateFileError.UnexpectedError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return false
        }
        return true
    }

    /**
     * Executes the HTTP download with transport-level retry for transient errors.
     *
     * ## Two-layer retry strategy
     *
     * **Layer 1 — transport retry (this function):** handles transient HTTP/network failures
     * ([DownloadError.TemporaryError]) with exponential back-off up to [maxRetryAttempts].
     * The task stays `Downloading` throughout; no state change is visible to callers.
     * HTTP 416 (Range Not Satisfiable) is handled inline by truncating the local file
     * and restarting from byte 0.
     *
     * **Layer 2 — library-level auto-retry ([io.github.giovanniandreuzza.nimbus.di]):**
     * fires only when `autoStart = true` and a download reaches [DownloadError] after all
     * transport retries are exhausted. It transitions the task through
     * `Failed → Enqueued → Downloading`, re-fetching the remote file size before re-queuing.
     * This layer is designed for long-lived processes (e.g. kiosk apps) where a file must
     * eventually be downloaded despite repeated server-side failures.
     *
     * The two layers are complementary: Layer 1 is fast (milliseconds of back-off, stays in
     * memory), while Layer 2 is a full round-trip that resets all state and can recover from
     * errors that are permanent within a single session (e.g. a server restart changing the
     * file size).
     */
    @OptIn(InternalIoApi::class)
    private suspend fun downloadWithRetry(
        downloadTask: DownloadTaskDTO,
        id: String,
        initialProgressBytes: Long
    ): TransferOutcome? {
        var progressBytes = initialProgressBytes
        val totalFileSize = downloadTask.fileSize
        var retryAttempt = 0
        var truncations = 0
        var digest: ContentDigest? = null

        downloadLoop@ while (currentCoroutineContext().isActive) {
            // The digest covers the bytes on disk, always, and the offset comes from the file.
            //
            // The resume offset is the length of the partial and nothing about it is held in
            // memory, so a resume survives a process restart. A digest accumulated only
            // across a streaming session would therefore cover only what that session
            // transferred: after a restart-and-resume it would hash the tail alone and
            // produce a value that is well-formed, plausible and wrong. The consumer would
            // compare it later, conclude the file is corrupt, and delete and re-download it
            // on every pass, forever.
            //
            // Which is why the digest is checked against the file below rather than assumed
            // to match it.
            //
            // Holding it in memory instead looks equivalent and is not: the assignment that
            // records it only runs when the transfer returns, so an attempt whose body stopped
            // arriving leaves the variable at whatever it was before — while the bytes it did
            // write are on disk. The next attempt then asks to resume from a stale offset and
            // appends the same stretch twice. Reading the length back is also the only source
            // that survives the process dying mid-transfer, which is the case this all exists
            // for.
            progressBytes = resolvePartialBytesOnDisk(downloadTask, id) ?: return null

            val bytesBeforeAttempt = progressBytes

            val algorithm = digestAlgorithm
            if (algorithm != null) {
                // Read the partial back only when the digest does not already stand for it.
                //
                // The rule that matters is unchanged: what the digest has consumed must be
                // exactly the bytes on disk, because the resume offset comes from the file's
                // length and a digest covering anything else produces a value that is
                // well-formed, plausible and wrong. What changes is how often that has to be
                // established by re-reading. Within one job, an attempt that ends after
                // writing 15 MB leaves a digest that has consumed those same 15 MB, and
                // re-reading them to learn what is already known cost O(partial × attempts) —
                // 60 MB of eMMC reads and hashing for a 20 MB asset that drops three times.
                //
                // The comparison is what makes it safe, and it is the file that is asked.
                // The byte counter advances before the buffered sink flushes, so an attempt
                // that died mid-write can leave the digest ahead of the disk; the lengths then
                // disagree and the prime happens. So it does after a 416 truncation, after a
                // restart, and on the first attempt — every case where the two could differ.
                val carried = digest
                if (carried == null || carried.consumedBytes != progressBytes) {
                    val primed = ContentDigest(algorithm)
                    if (!primeFromDisk(downloadTask.filePath, id, primed)) return null
                    digest = primed
                }
            }

            // The file is already whole, so there is nothing left to ask for. Reached when an
            // attempt delivered every byte and then failed on its way out — a port that hangs
            // after the last chunk, a connection closed without a clean end — and without this
            // the next attempt would ask to resume from the end of a complete file, which a
            // server answers with a 416 and this adapter answers by truncating everything it
            // just spent the bandwidth on. The digest was primed from the same file a moment
            // ago, so what it reports describes the whole of it.
            if (progressBytes == totalFileSize) break@downloadLoop

            val sink = openSink(downloadTask.filePath, id) ?: return null

            // Nothing this callback does may escape into the port implementation's `try`.
            //
            // An implementor wraps the whole transfer, callback included, because that is the
            // only way to hold the response open while the body is read. It therefore cannot
            // tell a dead socket from a full disk — on every platform both are
            // `kotlinx.io.IOException` — and every implementor would have to solve that
            // separately to avoid retrying a disk that will never have room. The failure is
            // classified here instead, once, by the side that knows which call threw: a read
            // from the body is the transport, anything else is this adapter's own storage.
            var transferFailure: DownloadError? = null

            // One per attempt: the watchdog below reads it, the copy loop writes to it, and
            // neither of them outlives the attempt.
            val progressTicks = Channel<Unit>(Channel.CONFLATED)

            val result = runWithStallWatchdog(progressTicks) {
                nimbusDownloadPort.downloadFile(
                    fileUrl = downloadTask.fileUrl,
                    offset = progressBytes,
                    resumeValidator = downloadTask.resumeValidator
                ) { source ->
                    try {
                        sink.use { output ->
                            val copied = copySourceToSink(
                                source = source,
                                sink = output,
                                id = id,
                                totalFileSize = totalFileSize,
                                initialProgressBytes = progressBytes,
                                digest = digest,
                                progressTicks = progressTicks
                            )
                            progressBytes = copied.bytes
                            if (copied.bodyLongerThanDeclared) {
                                transferFailure = DownloadError.PermanentError(
                                    PermanentDownloadErrorCause.BodyLongerThanDeclared(
                                        totalFileSize
                                    )
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: BodyReadFailure) {
                        transferFailure = DownloadError.TemporaryError(
                            TemporaryDownloadErrorCause.TransportFailure(unexpectedKError(e.cause))
                        )
                    } catch (e: Throwable) {
                        transferFailure = DownloadError.PermanentError(
                            storageFailureCause(
                                filePath = downloadTask.filePath,
                                totalFileSize = totalFileSize,
                                cause = e
                            )
                        )
                    }
                }
            }

            // What happened inside the callback wins: the implementation returned whatever it
            // made of a body that stopped early, and this adapter knows why it stopped.
            val outcome: KResult<Unit, DownloadError> =
                transferFailure?.let { Failure(it) } ?: result

            when (outcome) {
                is Success -> {
                    retryAttempt = 0
                    break@downloadLoop
                }

                is Failure -> {
                    val downloadError = outcome.error

                    // Bytes did arrive this time, so whatever happened next is a fresh
                    // situation and the truncation budget starts over.
                    if (progressBytes > bytesBeforeAttempt) truncations = 0

                    // Two ways to be told the local bytes are worthless: the origin refused
                    // the range, or it no longer recognises the file they came from. The
                    // recovery is the same and so is the budget for it.
                    val cause = (downloadError as? DownloadError.TemporaryError)?.errorCause
                    val startOver = cause is TemporaryDownloadErrorCause.RangeNotSatisfiable ||
                            cause is TemporaryDownloadErrorCause.RemoteFileChanged

                    when {
                        // A 416 means the server rejected the range this adapter asked for, so
                        // the local bytes are worthless and the file is fetched again from the
                        // start — outside the retry budget, because nothing was wrong with the
                        // link. Once, though. The truncation puts the request back at offset 0,
                        // which carries no Range header at all, and a server that answers
                        // *that* with a 416 will answer the next one the same way: a second
                        // consecutive refusal was an endless loop at full speed, deleting and
                        // recreating the file on every pass. From here it is an ordinary
                        // temporary failure, which is budgeted, backed off, and eventually
                        // reported.
                        startOver && truncations < MAX_CONSECUTIVE_TRUNCATIONS -> {
                            if (!truncateLocalFile(downloadTask.filePath, id)) return null
                            truncations += 1
                            progressBytes = 0L
                            retryAttempt = 0
                        }

                        // The file is now exactly as long as it was supposed to be and holds
                        // bytes a server told two stories about — the shape `startDownload`
                        // short-circuits on, so leaving it there would have the next start
                        // report a corrupt file as complete. Nothing written under a
                        // contradiction is worth keeping.
                        downloadError is DownloadError.PermanentError &&
                                downloadError.errorCause is PermanentDownloadErrorCause.BodyLongerThanDeclared -> {
                            if (!truncateLocalFile(downloadTask.filePath, id)) return null
                            notifyFailureAndCleanup(id, downloadError)
                            return null
                        }

                        else -> {
                            val nextAttempt = retryAttempt + 1
                            val shouldRetry = shouldRetry(
                                error = downloadError,
                                nextAttempt = nextAttempt,
                                isStillActive = currentCoroutineContext().isActive
                            )
                            if (!shouldRetry) {
                                notifyFailureAndCleanup(id, downloadError)
                                return null
                            }
                            retryAttempt = nextAttempt
                            delay(transportRetry.delayForAttempt(retryAttempt, random))
                        }
                    }
                }
            }
        }

        return TransferOutcome(checksum = digest?.finish())
    }

    /**
     * The result of a transfer that completed. A type rather than a nullable [Checksum]
     * because null there would mean both "the transfer failed" and "no digest was
     * configured", which are not the same thing and lead to opposite handling.
     */
    private class TransferOutcome(val checksum: Checksum?)

    /**
     * Feeds [digest] the bytes already on disk for this download.
     *
     * @return false when the file could not be read (failure already notified).
     */
    private suspend fun primeFromDisk(
        filePath: String,
        id: String,
        digest: ContentDigest
    ): Boolean {
        val exists = nimbusStoragePort.exists(filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return false
        }
        if (!exists) return true

        val source = nimbusStoragePort.source(filePath).getOr { error ->
            notifyFailureAndCleanup(
                id,
                DownloadError.PermanentError(PermanentDownloadErrorCause.StorageError(error))
            )
            return false
        }

        return try {
            source.use { it.readFullyInto(ByteArray(bufferSize.toInt()), digest) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            notifyFailureAndCleanup(
                id,
                DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileNotAccessible)
            )
            false
        }
    }

    private suspend fun openSink(filePath: String, id: String): Sink? {
        return nimbusStoragePort.sink(path = filePath, hasToAppend = true).getOr { error ->
            val mappedError = when (error) {
                GetFileSinkError.FileNotFound -> DownloadError.TemporaryError(
                    TemporaryDownloadErrorCause.FileNotAccessible
                )

                is GetFileSinkError.ReadPermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is GetFileSinkError.WritePermissionDenied -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )

                is GetFileSinkError.UnexpectedError -> DownloadError.PermanentError(
                    PermanentDownloadErrorCause.StorageError(error)
                )
            }
            notifyFailureAndCleanup(id, mappedError)
            return null
        }
    }

    /** A read from the response body failed. Raised only by [readingBody]. */
    private class BodyReadFailure(override val cause: Throwable) : Exception(cause)

    /**
     * Runs a read of the response body, marking anything it throws.
     *
     * The mark is what separates the two failures that reach the same `catch`: a body that
     * stopped arriving, which is transient and resumes from what is already on disk, and a
     * sink that could not be written, which retrying will not fix.
     */
    private inline fun <T> readingBody(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw BodyReadFailure(e)
        }

    /**
     * Classifies a write that the volume refused.
     *
     * The volume is asked how much room is left rather than the exception being read for
     * words like "space": the message is the platform's to phrase and a caller should not
     * have to grep it. A shortage is reported as one, so it can be told apart from storage
     * that is genuinely broken — the distinction the caller can act on, and the same answer
     * the headroom check gives when it catches the shortage before the transfer starts.
     *
     * Anything else, including a volume that cannot say, stays a storage error.
     */
    private fun storageFailureCause(
        filePath: String,
        totalFileSize: Long,
        cause: Throwable
    ): PermanentDownloadErrorCause {
        val available = when (val space = nimbusStoragePort.usableSpaceBytes(filePath)) {
            is Success -> space.value
            is Failure -> return PermanentDownloadErrorCause.StorageError(unexpectedKError(cause))
        }

        // What is left to write comes from the file, not from the byte counter. The counter
        // is advanced before the buffered sink flushes, so the write that fails is often the
        // flush of bytes the counter has already counted — leaving nothing outstanding by
        // its reckoning, and a shortage that could never be recognised. It is the same
        // lesson the resume offset had to learn.
        val onDisk = when (val size = nimbusStoragePort.size(filePath)) {
            is Success -> size.value
            is Failure -> return PermanentDownloadErrorCause.StorageError(unexpectedKError(cause))
        }
        val outstanding = (totalFileSize - onDisk).coerceAtLeast(1L)

        if (available < outstanding) {
            return PermanentDownloadErrorCause.InsufficientDiskSpace(
                KError(
                    code = "insufficient_disk_space",
                    message = "Writing $filePath needed $outstanding more bytes, " +
                            "the volume had $available.",
                    cause = unexpectedKError(cause)
                )
            )
        }
        return PermanentDownloadErrorCause.StorageError(unexpectedKError(cause))
    }

    private fun unexpectedKError(cause: Throwable?): KError = KError(
        code = "transfer_failed",
        message = cause?.message ?: "The transfer failed."
    )

    /**
     * Runs [block] and abandons it when it stops making progress.
     *
     * A transfer that fails announces itself. A transfer that *stalls* does not: a server that
     * accepts the connection, returns its headers and then sends nothing is, to every layer
     * below this one, a download still in progress. It holds its permit — with the default
     * concurrency of one, that is the whole queue — and no state changes, so nothing is
     * emitted, nothing is logged, and a device nobody is watching simply stops updating. It is
     * the one failure mode in this library that is silent by construction, which on an
     * unattended appliance is worse than a loud one.
     *
     * Progress is measured, not elapsed time: only [copySourceToSink] can say whether bytes
     * are arriving, and it reports each read that delivered any. A transfer that takes hours
     * is fine; a transfer that delivers nothing for [stallTimeoutMs] is not.
     *
     * **What this can and cannot interrupt.** Cancelling the transfer unwinds an
     * implementation that suspends while it waits. An implementation that *blocks a thread*
     * instead cannot be interrupted by anyone — `ByteReadChannel.asSource()` in Ktor reads
     * through `runBlocking`, so the read is not a suspension point and the cancellation stays
     * pending until the socket itself gives up. That is why `KtorDownloadAdapter` imposes a
     * socket timeout on every request it makes: this watchdog names the condition, and the
     * transport's own timeout is what ends it.
     */
    private suspend fun runWithStallWatchdog(
        progressTicks: Channel<Unit>,
        block: suspend () -> KResult<Unit, DownloadError>
    ): KResult<Unit, DownloadError> {
        val timeout = stallTimeoutMs ?: return block()

        return coroutineScope {
            var stalled = false

            val transfer = async { block() }

            // Cancelling a child cancels only that child, so the failure below is reported
            // from a scope that is still very much alive.
            val watchdog = launch {
                while (true) {
                    val tick = withTimeoutOrNull(timeout) { progressTicks.receive() }
                    if (tick == null) {
                        // The flag, not the cancellation cause, is what the catch below reads:
                        // in common code `Job.cancel` wants kotlinx's CancellationException and
                        // an unwinding transfer may wrap whatever it is given, so identity of
                        // the cause is not something to depend on.
                        stalled = true
                        transfer.cancel()
                        return@launch
                    }
                }
            }

            try {
                transfer.await()
            } catch (e: CancellationException) {
                // Ours, or the caller's? A pause or a cancelDownload arrives the same way and
                // must keep unwinding; only a stall becomes a failure to report.
                if (!stalled || !currentCoroutineContext().isActive) throw e
                Failure(
                    DownloadError.TemporaryError(
                        TemporaryDownloadErrorCause.TransportFailure(
                            KError(
                                code = "transfer_stalled",
                                message = "The transfer delivered no bytes for $timeout ms."
                            )
                        )
                    )
                )
            } finally {
                // Cancelled, not closed: cancellation is not synchronous, so a watchdog already
                // suspended in `receive()` would lose the race against a close and throw
                // ClosedReceiveChannelException — a child failing, which cancels this scope and
                // surfaces as an unexpected error on a transfer that had just succeeded. The
                // channel is local to one attempt and needs no closing.
                watchdog.cancel()
            }
        }
    }

    /**
     * @param digest fed every byte that passes, when a digest is being computed.
     * @param progressTicks told about every read that delivered bytes, so
     * [runWithStallWatchdog] can tell a slow transfer from a dead one.
     */
    @OptIn(InternalIoApi::class)
    private suspend fun copySourceToSink(
        source: Source,
        sink: Sink,
        id: String,
        totalFileSize: Long,
        initialProgressBytes: Long,
        digest: ContentDigest?,
        progressTicks: Channel<Unit>? = null
    ): CopyOutcome {
        var progressBytes = initialProgressBytes
        var bodyLongerThanDeclared = false
        source.use { input ->
            var bytesSinceLastProgressUpdate = 0L

            // Chosen once per transfer, not per chunk. With no digest configured this is
            // the 2.2.0 path byte for byte: the read goes straight into the sink's own
            // buffer and nothing else touches the bytes. Hashing needs the bytes in a
            // ByteArray the digest can read, so it reads into one — reused for every chunk,
            // so the memory is the buffer and never the file. An earlier double-buffered
            // version of this loop accumulated the whole file in memory, which on a 2 GB
            // appliance is not a performance note but a crash.
            val chunk = digest?.let { ByteArray(bufferSize.toInt()) }

            while (readingBody { !input.exhausted() } && currentCoroutineContext().isActive) {
                // Nothing past the declared size is written. `exhausted()` just said more bytes
                // are waiting, so reaching this with none of them owed means the server is
                // sending more than it announced — and a write loop that simply followed the
                // body would fill the volume with it. A 200 MB asset whose origin serves an
                // HTML error page in a loop, or a file replaced between the HEAD and the GET,
                // is the whole of eight gigabytes on an appliance that has no more.
                val outstanding = totalFileSize - progressBytes
                if (outstanding <= 0L) {
                    bodyLongerThanDeclared = true
                    break
                }

                val bytesRead = if (chunk == null) {
                    val target = sink.buffer
                    readingBody { input.readAtMostTo(target, minOf(bufferSize, outstanding)) }
                } else {
                    val room = minOf(chunk.size.toLong(), outstanding).toInt()
                    val read = readingBody { input.readAtMostTo(chunk, 0, room) }
                    if (read > 0) {
                        digest.update(chunk, 0, read)
                        sink.write(chunk, 0, read)
                    }
                    read.toLong()
                }
                // `exhausted()` said bytes were waiting, so a read that returns nothing is a
                // source that will not produce any: continuing here spins the loop against
                // it forever. Leaving instead ends the attempt with fewer bytes than the
                // declared length, which the size check turns into FileIntegrityMismatch —
                // temporary, and retried.
                if (bytesRead <= 0) break

                progressBytes += bytesRead
                bytesSinceLastProgressUpdate += bytesRead
                sink.emit()

                // The only evidence the watchdog gets. Sent per read rather than per progress
                // notification, because a link crawling along below the notification threshold
                // is slow, not dead, and must not be killed as if it were. `trySend` on a
                // conflated channel never suspends and never fails, so the hot path pays one
                // atomic swap for it.
                progressTicks?.trySend(Unit)

                if (bytesSinceLastProgressUpdate >= notifyEveryBytes) {
                    val progress = getDownloadProgress(progressBytes, totalFileSize)
                    downloadProgressCallback.onDownloadProgress(id, progress)
                    yield()
                    bytesSinceLastProgressUpdate = 0L
                }
            }
        }
        return CopyOutcome(
            bytes = progressBytes,
            bodyLongerThanDeclared = bodyLongerThanDeclared
        )
    }

    /**
     * What one pass over the body did.
     *
     * The byte count alone cannot say why the loop stopped, and the two reasons lead opposite
     * ways: a body that ended is a transfer to verify, a body that kept going past the declared
     * length is a server contradicting itself and a download to stop.
     */
    private class CopyOutcome(val bytes: Long, val bodyLongerThanDeclared: Boolean)

    private fun shouldRetry(
        error: DownloadError,
        nextAttempt: Int,
        isStillActive: Boolean
    ): Boolean {
        return error is DownloadError.TemporaryError &&
                transportRetry.allowsAttempt(nextAttempt) &&
                isStillActive
    }

    private fun createExceptionHandler(
        id: String,
        fileUrl: String
    ): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            val error = unexpectedDownloadError(throwable)
            downloadScope.launch {
                logger?.log(NimbusLogEvent.Unexpected(fileUrl, throwable))
                notifyFailureAndCleanup(
                    id,
                    DownloadError.PermanentError(PermanentDownloadErrorCause.UnexpectedError(error))
                )
            }
        }
    }

    private fun unexpectedDownloadError(throwable: Throwable): KError {
        return KError(
            code = "download_unexpected_error",
            message = throwable.message ?: "An unexpected error occurred during download."
        )
    }

    /**
     * Reports a file that is already on disk at the expected length as finished.
     *
     * Nothing is transferred here, and for a long time nothing was checked either: the file
     * was accepted on its length alone. That is the one case the digest exists for. A player
     * that keeps its assets between runs meets this path on every start, so a digest that
     * cannot answer here answers only where nobody was asking — and a caller's
     * [DownloadTaskDTO.expectedChecksum] was silently never consulted.
     *
     * The file is therefore hashed from disk and put through the same verification a
     * streamed transfer gets. With no algorithm configured nothing is hashed, exactly as
     * during a transfer.
     */
    private suspend fun finishCompleteFileOnDisk(
        downloadTask: DownloadTaskDTO,
        id: String
    ) {
        val algorithm = digestAlgorithm
        if (algorithm == null) {
            downloadProgressCallback.onDownloadFinished(id)
            return
        }

        val checksum = when (val r = contentDigestPort.digestOf(downloadTask.filePath, algorithm)) {
            is Success -> r.value
            is Failure -> {
                // The bytes are there but could not be read. Temporary: the same file may
                // read cleanly on the next attempt, and calling it permanent would strand a
                // download that has already fully transferred.
                notifyFailureAndCleanup(
                    id,
                    DownloadError.TemporaryError(TemporaryDownloadErrorCause.FileNotAccessible)
                )
                return
            }
        }

        if (!verifyFileIntegrity(downloadTask, id, checksum)) return

        downloadProgressCallback.onDownloadFinished(id, checksum)
    }

    private suspend fun notifyFailureAndCleanup(id: String, error: DownloadError) {
        // Remove the job before notifying so that an auto-retry triggered inside
        // onDownloadFailed can register a fresh job without seeing the stale one.
        removeJob(id)
        downloadProgressCallback.onDownloadFailed(id, error)
    }

    private suspend fun removeJob(id: String): Job? = jobsMutex.withLock { downloadJobs.remove(id) }

    private companion object {
        /**
         * One 416 is a server disagreeing about a range; two in a row is a server that will
         * never agree, and each one costs a delete and a recreate of the file.
         */
        const val MAX_CONSECUTIVE_TRUNCATIONS = 1
    }
}
