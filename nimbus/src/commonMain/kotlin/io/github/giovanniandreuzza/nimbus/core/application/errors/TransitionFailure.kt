package io.github.giovanniandreuzza.nimbus.core.application.errors

import io.github.giovanniandreuzza.explicitarchitecture.core.application.errors.IsApplicationError
import io.github.giovanniandreuzza.explicitarchitecture.shared.errors.KError
import io.github.giovanniandreuzza.nimbus.core.domain.states.DownloadState

/**
 * Why a state transition did not happen.
 *
 * Three outcomes, because a caller acts differently on each: there is no such task, the task
 * refused the transition from the state it is in, or the transition happened in memory and
 * could not be written to disk.
 *
 * @author Giovanni Andreuzza
 */
@IsApplicationError
internal sealed class TransitionFailure(
    code: String,
    message: String,
    cause: KError? = null
) : KError(code, message, cause) {

    internal data object NotFound : TransitionFailure(
        code = "download_task_not_found",
        message = "Download Task Not Found."
    )

    /**
     * The entity refused the transition. It owns the rules about which state follows which,
     * so the refusal comes from it and this carries the state it refused from.
     */
    internal data class Refused(val currentState: DownloadState) : TransitionFailure(
        code = "transition_refused",
        message = "The task is $currentState and refused the transition."
    )

    /** The task changed in memory; the disk did not take it. */
    internal data class NotPersisted(override val cause: KError) : TransitionFailure(
        code = "transition_not_persisted",
        message = "The transition was applied in memory but could not be persisted.",
        cause = cause
    )
}
