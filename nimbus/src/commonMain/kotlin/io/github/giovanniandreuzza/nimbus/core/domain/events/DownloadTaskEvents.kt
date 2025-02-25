package io.github.giovanniandreuzza.nimbus.core.domain.events

import io.github.giovanniandreuzza.explicitarchitecture.domain.DomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.domain.EntityId
import io.github.giovanniandreuzza.explicitarchitecture.shared.DateUtils
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import kotlinx.datetime.LocalDateTime

/**
 * Download Task Events.
 *
 * @author Giovanni Andreuzza
 */
internal sealed class DownloadTaskEvents(
    override val occurredOn: LocalDateTime = DateUtils.now()
) : DomainEvent<DownloadId> {

    /**
     * Download Enqueued Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    data class DownloadEnqueuedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadEnqueuedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn)"
        }
    }

    /**
     * Download Started Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    data class DownloadStartedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadStartedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn)"
        }
    }

    /**
     * Download Paused Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     * @param progress Download progress.
     */
    data class DownloadPausedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
        val progress: Double
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadPausedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn, progress=$progress)"
        }
    }

    /**
     * Download Resumed Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     * @param progress Download progress.
     */
    data class DownloadResumedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
        val progress: Double
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadResumedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn, progress=$progress)"
        }
    }

    /**
     * Download Failed Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     * @param errorCode The error code.
     * @param errorMessage The error message.
     */
    data class DownloadFailedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
        val errorCode: String,
        val errorMessage: String
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadFailedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn, errorCode=$errorCode, errorMessage=$errorMessage)"
        }
    }

    /**
     * Download Canceled Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    data class DownloadCanceledEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadCanceledEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn)"
        }
    }

    /**
     * Download Finished Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    data class DownloadFinishedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadFinishedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn)"
        }
    }
}