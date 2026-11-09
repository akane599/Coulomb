// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.ui.R

/** One thing Coulomb can see, in the words of SPEC 14.1's summary. */
public enum class Sight(
    @param:StringRes internal val words: Int,
) {
    PER_APP_DRAIN(R.string.sight_per_app_drain),
    WAKELOCKS(R.string.sight_wakelocks),
    KERNEL_WAKEUPS(R.string.sight_kernel_wakeups),
    SCREEN_TIME(R.string.sight_screen_time),
    LEVEL(R.string.sight_level),
    CURRENT(R.string.sight_current),
}

/**
 * What the stored Capability Matrix lets Coulomb see (SPEC 14.1): the deep sources when any work,
 * else the basic ones, and whether the phone's power rails are exposed (SPEC 6: measured energy per
 * component, else Android's power model). A degraded source still counts: it works with less detail.
 */
public data class CapabilitySummary(
    val sights: List<Sight>,
    /** Some per-app or kernel source works, not only what every app can read. */
    val deep: Boolean,
    val railsExposed: Boolean,
)

public fun capabilitySummary(capabilities: List<Capability>): CapabilitySummary {
    val works = capabilities.filter { it.state != CapabilityState.UNAVAILABLE }.map { it.id }.toSet()

    fun any(vararg ids: CapabilityId) = ids.any { it in works }
    val deep =
        buildList {
            if (any(CapabilityId.BATTERYSTATS_USAGE, CapabilityId.BATTERYSTATS_PROTO, CapabilityId.BATTERYSTATS_CHECKIN)) {
                add(Sight.PER_APP_DRAIN)
            }
            if (any(CapabilityId.BATTERYSTATS_HISTORY, CapabilityId.POWER_DUMP)) add(Sight.WAKELOCKS)
            if (any(CapabilityId.KERNEL_WAKELOCKS, CapabilityId.WAKEUP_SOURCES, CapabilityId.WAKEUP_REASONS)) {
                add(Sight.KERNEL_WAKEUPS)
            }
        }
    val sights =
        deep.ifEmpty {
            buildList {
                if (any(CapabilityId.BATTERY_CHANGED)) add(Sight.LEVEL)
                if (any(CapabilityId.CURRENT_NOW)) add(Sight.CURRENT)
                if (any(CapabilityId.USAGE_STATS)) add(Sight.SCREEN_TIME)
            }
        }
    return CapabilitySummary(sights, deep = deep.isNotEmpty(), railsExposed = any(CapabilityId.POWER_MONITORS))
}

/**
 * "It can see per-app drain, wakelocks and kernel wakeups." in the app's language; null when
 * nothing is seen. The list is joined by the app's own strings rather than ICU, whose English adds
 * a serial comma the rest of Coulomb's copy does not use.
 */
@Composable
public fun sightSentence(sights: List<Sight>): String? {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(sights, resources, locale) {
        if (sights.isEmpty()) return@remember null
        val words = sights.map { resources.getString(it.words) }
        val list =
            if (words.size == 1) {
                words.single()
            } else {
                resources.getString(
                    R.string.sight_list_last,
                    words.dropLast(1).joinToString(resources.getString(R.string.sight_list_separator)),
                    words.last(),
                )
            }
        resources.getString(R.string.sight_sentence, list).replaceFirstChar { it.titlecase(locale) }
    }
}

/** The power-rails sentence, without its full stop: the caller puts the source's mark before it. */
@StringRes
public fun railsSentence(summary: CapabilitySummary): Int =
    if (summary.railsExposed) R.string.sight_rails_measured else R.string.sight_rails_modeled
