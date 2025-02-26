package io.github.giovanniandreuzza.nimbus.core.domain.events

import io.github.giovanniandreuzza.explicitarchitecture.core.domain.entities.EntityId
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.events.DomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.core.domain.events.IsDomainEvent
import io.github.giovanniandreuzza.explicitarchitecture.shared.utilities.Dates
import io.github.giovanniandreuzza.nimbus.core.domain.value_objects.DownloadId
import kotlinx.datetime.LocalDateTime

/**
 * Download Task Events.
 *
 * @author Giovanni Andreuzza
 */
@IsDomainEvent
internal sealed class DownloadTaskEvents(
    override val occurredOn: LocalDateTime = Dates.now()
) : DomainEvent<DownloadId> {

    /**
     * Download Enqueued Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    @IsDomainEvent
    data class DownloadEnqueuedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadEnqueuedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn)"
        }
    }

    /**
     * Download Progress Updated Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    @IsDomainEvent
    data class DownloadProgressUpdatedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
        val progress: Double
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadProgressUpdatedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn, progress=$progress)"
        }
    }

    /**
     * Download Started Event.
     *
     * @param aggregateId The Aggregate Id.
     * @param version The version.
     */
    @IsDomainEvent
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
    @IsDomainEvent
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
    @IsDomainEvent
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
    @IsDomainEvent
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
    @IsDomainEvent
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
    @IsDomainEvent
    data class DownloadFinishedEvent(
        override val aggregateId: EntityId<DownloadId>,
        override val version: Int,
    ) : DownloadTaskEvents() {
        override fun toString(): String {
            return "DownloadFinishedEvent(aggregateId=$aggregateId, version=$version, occurredOn=$occurredOn)"
        }
    }
}