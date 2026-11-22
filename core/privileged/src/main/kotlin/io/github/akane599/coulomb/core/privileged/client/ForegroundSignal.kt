// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** When one of Coulomb's activities last paused and last resumed, on the controller's clock (null if never). */
internal data class Foreground(
    val pausedAt: Long? = null,
    val resumedAt: Long? = null,
)

/**
 * Coulomb's activities pausing and resuming.
 *
 * The manager's authorization dialog is an activity of another app on top of Coulomb, so Coulomb's
 * activity pauses while it is shown. A request with no answer after 5 s is a hang (SPEC 4.5) only
 * if that never happened: on the phone, Shevery's dialog waits up to 20 s for the user, and calling
 * that "your Shizuku version can't authorize apps" would be wrong. On the API 37 emulator with
 * official Shizuku 13.6.0 no dialog appears and Coulomb keeps focus. Coulomb also pauses when the
 * user leaves it; its resuming tells the request's check to look again (audit BUG-022).
 */
internal fun interface ForegroundSignal {
    fun state(): StateFlow<Foreground>
}

internal class ActivityPauseTracker(
    application: Application,
    private val clock: () -> Long,
) : ForegroundSignal,
    Application.ActivityLifecycleCallbacks {
    private val seen = MutableStateFlow(Foreground())

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun state(): StateFlow<Foreground> = seen

    override fun onActivityPaused(activity: Activity) {
        val at = clock()
        seen.update { it.copy(pausedAt = at) }
    }

    override fun onActivityResumed(activity: Activity) {
        val at = clock()
        seen.update { it.copy(resumedAt = at) }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
