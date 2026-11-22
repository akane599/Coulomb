// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.analytics.WakeSubsystem
import io.github.akane599.coulomb.core.data.WakeApps
import io.github.akane599.coulomb.core.data.WakeEntry
import io.github.akane599.coulomb.core.data.WakeNight
import io.github.akane599.coulomb.core.data.WakeSourceDetail
import io.github.akane599.coulomb.core.data.WakeSourceRepository
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigure
import io.github.akane599.coulomb.core.designsystem.component.CoulombFigureStrip
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentSectionGap
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.ui.breakable
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.DataRow
import io.github.akane599.coulomb.core.ui.page.ErrorState
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.SectionTitle
import io.github.akane599.coulomb.core.ui.page.StandIn
import java.time.ZoneId

/** The model of one record's page in the current navigation entry. */
@Composable
public fun wakeSourceViewModel(
    kind: WakeKind,
    tag: String,
): WakeSourceViewModel =
    hiltViewModel<WakeSourceViewModel, WakeSourceViewModel.Factory>(
        key = "wake-$kind-$tag",
        creationCallback = { it.create(kind, tag) },
    )

/**
 * One kernel wakelock, wakeup source or wakeup reason (SPEC 10.5): what it is, how often it ran in
 * Drain's window and in the latest nights, which apps followed it, and what the user can do, which
 * is often nothing. [onOpenApp] opens App detail for an app that followed.
 */
@Composable
public fun WakeSourceScreen(
    viewModel: WakeSourceViewModel,
    onOpenApp: (uid: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WakeSourceContent(state, viewModel::retry, onOpenApp, modifier)
}

@Composable
internal fun WakeSourceContent(
    state: WakeSourceState,
    retry: () -> Unit,
    onOpenApp: (Int) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
    nowMs: Long = remember(state) { System.currentTimeMillis() },
) {
    val gutter = currentGutter()
    Column(
        modifier
            .fillMaxSize()
            .background(CoulombTheme.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, bottom = CoulombSpacing.Space48)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MaxWidth),
    ) {
        when (state) {
            WakeSourceState.Loading -> {
                Skeleton()
            }

            WakeSourceState.Error -> {
                ErrorState(
                    stringResource(R.string.drain_error_title),
                    stringResource(R.string.drain_error_body),
                    stringResource(R.string.drain_error_action),
                    retry,
                )
            }

            is WakeSourceState.Ready -> {
                val detail = state.detail
                if (detail == null) {
                    StandIn(stringResource(R.string.wake_no_window), top = CoulombSpacing.Space24)
                } else {
                    FadeInOnce { Page(detail, onOpenApp, zone, nowMs) }
                }
            }
        }
    }
}

private val MaxWidth = 720.dp

/** The apps table shows this many; the rest are a line. */
private const val APPS_SHOWN = 8

@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.drain_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        SkeletonBlock(26.dp, Modifier.width(180.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(280.dp))
        SkeletonBlock(72.dp, Modifier.padding(top = currentSectionGap()).fillMaxWidth())
    }
}

@Composable
private fun Page(
    detail: WakeSourceDetail,
    onOpenApp: (Int) -> Unit,
    zone: ZoneId,
    nowMs: Long,
) {
    val formats = rememberFormats()
    Column(Modifier.fillMaxWidth()) {
        Head(detail)
        // The mark is for figures: a section that only says why it has none carries no mark.
        SectionTitle(
            stringResource(R.string.wake_in_window),
            provenance = detail.entry?.let { Provenance.MEASURED },
            // On a compact window Drain's selector is not on screen here.
            caption = spanName(detail.span, formats),
        )
        InWindow(detail.entry, detail.coverage, formats)
        if (detail.nights.isNotEmpty()) {
            SectionTitle(stringResource(R.string.wake_nights), provenance = Provenance.MEASURED)
            Nights(detail.nights, zone, nowMs, formats)
        }
        SectionTitle(stringResource(R.string.wake_apps), provenance = detail.apps?.takeIf { it.wakes > 0 }?.let { Provenance.MEASURED })
        Apps(detail.apps, onOpenApp, zone, nowMs, formats)
        SectionTitle(stringResource(R.string.wake_do), provenance = null)
        Text(
            stringResource(advice(detail)),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.body,
        )
    }
}

/** What kind of record it is, its raw name, its subsystem and the dictionary's line. */
@Composable
private fun Head(detail: WakeSourceDetail) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        Text(stringResource(detail.kind.title), Modifier.semantics { heading() }, color = colors.textPrimary, style = type.title)
        Text(breakable(detail.tag), Modifier.padding(top = CoulombSpacing.Space4), color = colors.textPrimary, style = type.mono)
        Text(
            stringResource(R.string.wake_subsystem, stringResource(detail.subsystem.label)),
            Modifier.padding(top = CoulombSpacing.Space4),
            color = colors.textSecondary,
            style = type.caption,
        )
        Text(
            stringResource(detail.meaning?.text ?: R.string.wake_no_meaning),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16),
            color = if (detail.meaning != null) colors.textPrimary else colors.textSecondary,
            style = type.body,
        )
    }
}

@get:StringRes
private val WakeKind.title: Int
    get() =
        when (this) {
            WakeKind.PARTIAL -> R.string.wake_kind_partial
            WakeKind.KERNEL -> R.string.wake_kind_kernel
            WakeKind.WAKEUP_SOURCE -> R.string.wake_kind_source
            WakeKind.WAKEUP_REASON -> R.string.wake_kind_reason
            WakeKind.RESUME_CAUSE -> R.string.wake_kind_resume
            WakeKind.SUSPEND_ABORT -> R.string.wake_kind_abort
        }

/**
 * The record in Drain's window, or why there is none. A window keeps each kind's longest records
 * only (`WindowBuilder.MAX_WAKES_PER_KIND`, `MIN_WAKE_MS`), so a missing record is never "none":
 * under whole coverage it was not among the kept ones, otherwise it was not collected (audit DATA-013).
 */
@Composable
private fun InWindow(
    entry: WakeEntry?,
    coverage: Coverage,
    formats: Formats,
) {
    val colors = CoulombTheme.colors
    val type = CoulombTheme.typography
    if (entry == null) {
        StandIn(stringResource(if (coverage == Coverage.WHOLE) R.string.wake_none_in_window else R.string.wake_not_collected))
        return
    }
    CoulombFigureStrip(Modifier.padding(top = CoulombSpacing.Space8)) {
        if (entry.totalMs > 0) {
            CoulombFigure(
                stringResource(R.string.wake_held),
                entry.screenOffShare?.let {
                    stringResource(R.string.wake_screen_off_share, formats.share(it))
                },
            ) {
                Text(formats.held(entry.totalMs), color = colors.textPrimary, style = type.title)
            }
        }
        CoulombFigure(stringResource(R.string.wake_count), null) {
            Text(formats.integer(entry.count), color = colors.textPrimary, style = type.title)
        }
        entry.wakeups?.takeIf { it > 0 }?.let {
            CoulombFigure(stringResource(R.string.wake_woke), null) {
                Text(formats.integer(it), color = colors.textPrimary, style = type.title)
            }
        }
    }
    if (coverage == Coverage.PARTIAL) Note(stringResource(R.string.wake_partial))
}

/** SPEC 10.5's per-session counts: each of the latest nights, with its bar against the busiest of them. */
@Composable
private fun Nights(
    nights: List<WakeNight>,
    zone: ZoneId,
    nowMs: Long,
    formats: Formats,
) {
    val byTime = nights.any { (it.entry?.totalMs ?: 0) > 0 }
    val most = nights.maxOf { night -> night.entry?.let { if (byTime) it.totalMs else it.count } ?: 0 }
    nights.forEachIndexed { index, night ->
        val entry = night.entry
        val amount = entry?.let { if (byTime) it.totalMs else it.count } ?: 0
        DataRow(
            name = formats.clock(night.night.startWallMs, zone, nowMs),
            detail = stringResource(R.string.wake_night_length, formats.duration(night.night.durationMs / MS_PER_MINUTE)),
            value = nightValue(night, byTime, formats),
            mark = null,
            share = if (entry != null && most > 0) amount.toDouble() / most else null,
            first = index == 0,
        )
    }
}

/** A night's figure: no record kept, not collected, or its time or count, "at least" where the night was collected in part. */
@Composable
private fun nightValue(
    night: WakeNight,
    byTime: Boolean,
    formats: Formats,
): String {
    val entry = night.entry
    val figure =
        when {
            entry == null && night.coverage == Coverage.WHOLE -> stringResource(R.string.wake_night_none)
            entry == null -> stringResource(R.string.wake_night_not_collected)
            byTime -> formats.held(entry.totalMs)
            else -> pluralStringResource(R.plurals.drain_times, entry.count.quantity(), formats.integer(entry.count))
        }
    return if (entry != null && night.coverage == Coverage.PARTIAL) stringResource(R.string.wake_at_least, figure) else figure
}

/** The apps that took the first wakelock after this wake, or why none can be named. */
@Composable
private fun Apps(
    apps: WakeApps?,
    onOpenApp: (Int) -> Unit,
    zone: ZoneId,
    nowMs: Long,
    formats: Formats,
) {
    if (apps == null) {
        StandIn(stringResource(R.string.wake_apps_no_history))
        return
    }
    val until = formats.clock(apps.toWallMs, zone, nowMs)
    if (apps.knownMs < MS_PER_MINUTE) {
        // No history is not "no wakes".
        StandIn(stringResource(R.string.wake_apps_not_collected, until))
        return
    }
    val known = formats.duration(apps.knownMs / MS_PER_MINUTE)
    if (apps.wakes == 0) {
        StandIn(stringResource(R.string.wake_apps_none, known, until))
        return
    }
    val seconds = formats.integer(WakeSourceRepository.FOLLOW_MS / MS_PER_SECOND)
    val lines =
        listOfNotNull(
            pluralStringResource(R.plurals.wake_apps_intro, apps.wakes, formats.integer(apps.wakes.toLong()), known, until, seconds),
            apps.unclaimed.takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.wake_apps_unclaimed, it, formats.integer(it.toLong()))
            },
            apps.unnamed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.wake_apps_unnamed, it, formats.integer(it.toLong())) },
        )
    Note(lines.joinToString(" "))
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8)) {
        apps.apps.take(APPS_SHOWN).forEachIndexed { index, app ->
            DataRow(
                name = formats.appName(app.label, app.uid),
                detail = null,
                value = pluralStringResource(R.plurals.wake_app_wakes, app.wakes, formats.integer(app.wakes.toLong())),
                mark = null,
                share = app.wakes.toDouble() / apps.wakes,
                first = index == 0,
                onClick = { onOpenApp(app.uid) },
            )
        }
    }
    val more = apps.apps.size - APPS_SHOWN
    if (more > 0) Note(pluralStringResource(R.plurals.wake_apps_more, more, formats.integer(more.toLong())))
}

/** "What you can do" (SPEC 10.5): by what the record is, then by its part of the phone. Often nothing. */
@StringRes
private fun advice(detail: WakeSourceDetail): Int {
    // "An app above" is said only under a list of apps.
    val listed = detail.apps?.apps?.isNotEmpty() == true && detail.apps?.let { it.knownMs >= MS_PER_MINUTE && it.wakes > 0 } == true
    val advice = if (detail.kind == WakeKind.SUSPEND_ABORT) R.string.wake_do_abort else ADVICE.getValue(detail.subsystem)
    return if (listed) advice else WITHOUT_APPS[advice] ?: advice
}

/** The same advice where no app is listed above it. */
private val WITHOUT_APPS: Map<Int, Int> =
    mapOf(
        R.string.wake_do_mobile to R.string.wake_do_mobile_no_apps,
        R.string.wake_do_wifi to R.string.wake_do_wifi_no_apps,
        R.string.wake_do_abort to R.string.wake_do_abort_no_apps,
    )

private val ADVICE: Map<WakeSubsystem, Int> =
    mapOf(
        WakeSubsystem.MOBILE_NETWORK to R.string.wake_do_mobile,
        WakeSubsystem.WIFI to R.string.wake_do_wifi,
        WakeSubsystem.BLUETOOTH to R.string.wake_do_bluetooth,
        WakeSubsystem.GPS to R.string.wake_do_gps,
        WakeSubsystem.NFC to R.string.wake_do_nfc,
        WakeSubsystem.INPUT to R.string.wake_do_input,
        WakeSubsystem.FINGERPRINT to R.string.wake_do_fingerprint,
        WakeSubsystem.SENSORS to R.string.wake_do_sensors,
        WakeSubsystem.BATTERY to R.string.wake_do_battery,
        WakeSubsystem.AUDIO to R.string.wake_do_audio,
        WakeSubsystem.CAMERA to R.string.wake_do_camera,
        WakeSubsystem.TIMERS to R.string.wake_do_timers,
        WakeSubsystem.ANDROID to R.string.wake_do_android,
        WakeSubsystem.OTHER to R.string.wake_do_other,
    )

private fun Long.quantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_SECOND = 1_000L
