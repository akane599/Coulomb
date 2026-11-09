// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.AppDrainRow
import io.github.akane599.coulomb.core.ui.IconStandIn
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.rememberAppIcon

/** One ranked row of the Apps lens: an app, or the power no app was charged for. */
internal sealed interface AppsEntry {
    val mah: Double

    data class App(
        val app: AppDrain,
    ) : AppsEntry {
        override val mah: Double get() = app.mah
    }

    data class Device(
        override val mah: Double,
    ) : AppsEntry
}

/**
 * What the Apps lens lists for a window, in modeled mAh before [factor]. The rows, [rest],
 * [hidden], [otherMah] and the unattributed row add up to the window's total, so shares sum to
 * the whole (SPEC 11.8).
 */
internal data class AppsView(
    /** The ranked rows: every app with at least [ROW_SHARE] of the total, or the top three, and the device row. */
    val rows: List<AppsEntry>,
    /** Apps under [ROW_SHARE], ranked: the remainder row. */
    val rest: List<AppDrain>,
    /** System apps the switch hides, ranked. */
    val hidden: List<AppDrain>,
    /** The UIDs no window stored one by one (ADR-0015). */
    val otherMah: Double,
    /** Measured minus modeled, when it is its own row (SPEC 6.5: above 10% of measured). Derived. */
    val unattributedMah: Double?,
    /** 1, or measured over modeled while the apps are scaled to the measured total. */
    val factor: Double,
    /** What every share divides by. */
    val denominator: Double,
) {
    val scaled: Boolean get() = factor != 1.0

    fun share(mah: Double): Double = if (denominator > 0) mah * factor / denominator else 0.0

    val remainderMah: Double get() = rest.sumOf { it.mah } + hidden.sumOf { it.mah } + otherMah

    val empty: Boolean get() = rows.isEmpty() && remainderMah <= 0
}

/**
 * The Apps lens of [report] (SPEC 6.5, 10.3). Scaling multiplies every modeled figure by measured
 * over modeled, so they sum to the measured total and the unattributed row goes; it needs a
 * measured total and a modeled one.
 */
internal fun appsView(
    report: DrainReport,
    includeSystem: Boolean,
    scaled: Boolean,
): AppsView {
    val drains = report.drains
    val measured = drains.measuredMah?.takeIf { it > 0 && !drains.estimated }
    val scaleTo = measured?.takeIf { scaled && canScale(drains) }
    val scaling = scaleTo != null
    val factor = scaleTo?.let { it / drains.modeledMah } ?: 1.0
    val denominator = scaleTo ?: drains.total
    val visible = drains.apps.filter { includeSystem || !it.isSystem }.sortedByDescending { it.mah }
    val hidden = drains.apps.filter { !includeSystem && it.isSystem }.sortedByDescending { it.mah }
    val share = { mah: Double -> if (denominator > 0) mah * factor / denominator else 0.0 }
    val large = visible.filter { share(it.mah) >= ROW_SHARE }
    val shown = if (large.size < MIN_ROWS) visible.take(MIN_ROWS) else large
    val rows =
        buildList {
            shown.forEach { add(AppsEntry.App(it)) }
            if (drains.deviceMah > 0) add(AppsEntry.Device(drains.deviceMah))
        }.sortedByDescending { it.mah }
    val gap = drains.unattributedMah
    val unattributed = gap.takeIf { !scaling && measured != null && it > measured * UNATTRIBUTED_ROW }
    return AppsView(rows, visible.drop(shown.size), hidden, drains.otherMah, unattributed, factor, denominator)
}

/** The share at which an app gets its own row (lab: "Each under 3%"). */
internal const val ROW_SHARE = 0.03

/** At least this many apps get rows, however small (SPEC 10.3 ranked list). */
private const val MIN_ROWS = 3

/**
 * "Scale to measured" applies: the window has a measured and a modeled total (ADR-0036 §12), and the
 * measured one is not a range (§17).
 */
internal fun canScale(drains: WindowDrains): Boolean {
    val measured = drains.measuredMah ?: return false
    return measured > 0 && !drains.estimated && drains.modeledMah > 0
}

/** SPEC 6.5: the unattributed row shows whenever it exceeds 10% of measured. */
private const val UNATTRIBUTED_ROW = 0.10

/**
 * The Apps lens as list items: the switches and caption, then each row keyed by its UID, so a
 * reorder moves the rows instead of rewriting them (SPEC 11.6, 15).
 */
@Suppress("LongParameterList") // One lens's inputs.
internal fun LazyListScope.appsItems(
    state: DrainUiState.Ready,
    report: DrainReport,
    view: AppsView,
    formats: Formats,
    actions: DrainActions,
    lists: ListState,
) {
    val provenance = if (view.scaled) Provenance.DERIVED else Provenance.MODELED
    item(key = "apps-head", contentType = "apps-head") {
        Column(Modifier.padding(top = CoulombSpacing.Space8)) {
            CoulombSwitchRow(stringResource(R.string.drain_include_system), state.includeSystem, actions.includeSystem)
            // Under the tabs, so switching lenses never moves them (ADR-0036 §24).
            if (canScale(report.drains)) CoulombSwitchRow(stringResource(R.string.drain_scale), state.scaled, actions.scale)
            Captions(view, report, formats)
            if (view.empty) {
                Text(
                    stringResource(R.string.drain_no_apps),
                    Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space12),
                    color = CoulombTheme.colors.textSecondary,
                    style = CoulombTheme.typography.body,
                )
            } else {
                Spacer(Modifier.height(CoulombSpacing.Space12))
            }
        }
    }
    if (!view.empty) {
        itemsIndexed(view.rows, key = { _, entry -> entry.key }, contentType = { _, _ -> "row" }) { index, entry ->
            when (entry) {
                is AppsEntry.App -> AppRow(entry.app, view, index == 0, provenance, formats, actions, rowMotion())
                is AppsEntry.Device -> DeviceRow(entry.mah, view, index == 0, provenance, formats, rowMotion())
            }
        }
        if (view.remainderMah > 0) remainderItems(view, provenance, formats, actions, lists)
    }
    view.unattributedMah?.let { gap -> item(key = "gap", contentType = "gap") { Unattributed(gap, view, formats) } }
}

/**
 * The remainder row, then once it is open each app it folds and "Show fewer". The row stays where
 * it is and says whether it is open, so TalkBack's focus stays on it (an accordion).
 */
private fun LazyListScope.remainderItems(
    view: AppsView,
    provenance: Provenance,
    formats: Formats,
    actions: DrainActions,
    lists: ListState,
) {
    val open = lists.remainderOpen
    item(key = "remainder", contentType = "row") {
        Remainder(view, provenance, formats, view.rows.isEmpty(), open, toggle = { lists.setRemainderOpen(!open) }, rowMotion())
    }
    if (!open) return
    itemsIndexed(view.rest, key = { _, app -> appKey(app.uid) }, contentType = { _, _ -> "row" }) { _, app ->
        AppRow(app, view, false, provenance, formats, actions, rowMotion())
    }
    if (view.hidden.isNotEmpty()) {
        item(key = "hidden", contentType = "row") {
            val hiddenMah = view.hidden.sumOf { it.mah }
            val share = view.share(hiddenMah)
            AppDrainRow(
                icon = null,
                name = pluralStringResource(R.plurals.drain_hidden_system, view.hidden.size, formats.integer(view.hidden.size.toLong())),
                detail = null,
                value = formats.mah(hiddenMah * view.factor),
                provenance = provenance,
                share = share,
                shareText = formats.share(share),
                first = false,
                onClick = null,
                modifier = rowMotion(),
                standIn = IconStandIn.Group("+${formats.integer(view.hidden.size.toLong())}"),
            )
        }
    }
    if (view.otherMah > 0) {
        item(key = "small", contentType = "row") {
            val share = view.share(view.otherMah)
            AppDrainRow(
                icon = null,
                name = stringResource(R.string.drain_small_apps),
                detail = null,
                value = formats.mah(view.otherMah * view.factor),
                provenance = provenance,
                share = share,
                shareText = formats.share(share),
                first = false,
                onClick = null,
                modifier = rowMotion(),
                standIn = IconStandIn.Group("+"),
            )
        }
    }
    item(key = "fewer", contentType = "button") {
        CoulombTextButton(
            text = stringResource(R.string.drain_show_fewer),
            onClick = { lists.setRemainderOpen(false) },
            modifier = rowMotion().padding(top = CoulombSpacing.Space4),
            edge = ButtonEdge.Start,
        )
    }
}

/** An app's key in the list, the same whether its row is ranked or opened from the remainder. */
private fun appKey(uid: Int) = "app-$uid"

private val AppsEntry.key: String
    get() =
        when (this) {
            is AppsEntry.App -> appKey(app.uid)
            is AppsEntry.Device -> DEVICE_KEY
        }

/** The list caption names every share's denominator (DESIGN.md); a lower bound says so. */
@Composable
private fun Captions(
    view: AppsView,
    report: DrainReport,
    formats: Formats,
) {
    val drains = report.drains
    val total = formats.mah(view.denominator).joined()
    val shares =
        when {
            view.scaled -> stringResource(R.string.drain_scaled_caption, total)
            drains.sharesOfMeasured -> stringResource(R.string.drain_shares_of_measured, total)
            else -> stringResource(R.string.drain_shares_of_modeled, total)
        }
    val captions = listOfNotNull(shares, if (report.lowerBound) stringResource(R.string.drain_lower_bound) else null)
    Column(Modifier.padding(top = CoulombSpacing.Space8)) {
        captions.forEach {
            Text(
                it,
                Modifier.fillMaxWidth(),
                color = CoulombTheme.colors.textSecondary,
                style = CoulombTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun AppRow(
    app: AppDrain,
    view: AppsView,
    first: Boolean,
    provenance: Provenance,
    formats: Formats,
    actions: DrainActions,
    modifier: Modifier = Modifier,
) {
    val share = view.share(app.mah)
    val open = actions.openApp
    AppDrainRow(
        icon = rememberAppIcon(app.iconPackage, actions.icon, actions.peekIcon),
        name = formats.appName(app.label, app.uid),
        // A bare UID has no name to take an initial from.
        standIn = if (app.label == null && !app.isSystem) IconStandIn.Initial("#") else null,
        detail = null,
        value = formats.mah(app.mah * view.factor),
        provenance = provenance,
        share = share,
        shareText = formats.share(share),
        first = first,
        onClick = open?.let { { it(app.uid) } },
        modifier = modifier,
    )
}

/** SPEC 6.5's friendly system row: what the model charged to no app. */
@Composable
private fun DeviceRow(
    mah: Double,
    view: AppsView,
    first: Boolean,
    provenance: Provenance,
    formats: Formats,
    modifier: Modifier = Modifier,
) {
    val share = view.share(mah)
    AppDrainRow(
        icon = null,
        name = stringResource(R.string.drain_device),
        detail = stringResource(R.string.drain_device_detail),
        value = formats.mah(mah * view.factor),
        provenance = provenance,
        share = share,
        shareText = formats.share(share),
        first = first,
        onClick = null,
        modifier = modifier,
        standIn = IconStandIn.Device,
    )
}

/** The remainder row (DESIGN.md): "22 more apps", each under 3%, or the system apps the switch hides. */
@Composable
private fun Remainder(
    view: AppsView,
    provenance: Provenance,
    formats: Formats,
    first: Boolean,
    open: Boolean,
    toggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = view.rest.size + view.hidden.size
    val share = view.share(view.remainderMah)
    // The small apps have no count of their own, so the name says they are in the figure too.
    val name =
        when {
            count == 0 -> stringResource(R.string.drain_small_apps)
            view.otherMah > 0 -> pluralStringResource(R.plurals.drain_more_apps_and_small, count, formats.integer(count.toLong()))
            else -> pluralStringResource(R.plurals.drain_more_apps, count, formats.integer(count.toLong()))
        }
    val detail =
        when {
            view.hidden.isNotEmpty() -> {
                pluralStringResource(R.plurals.drain_hidden_system, view.hidden.size, formats.integer(view.hidden.size.toLong()))
            }

            view.rest.isNotEmpty() -> {
                stringResource(R.string.drain_each_under, formats.share(ROW_SHARE))
            }

            else -> {
                null
            }
        }
    val state = stringResource(if (open) R.string.drain_state_expanded else R.string.drain_state_collapsed)
    AppDrainRow(
        icon = null,
        name = name,
        detail = detail,
        value = formats.mah(view.remainderMah * view.factor),
        provenance = provenance,
        share = share,
        shareText = formats.share(share),
        first = first,
        onClick = toggle,
        modifier =
            modifier.semantics {
                stateDescription = state
                if (open) {
                    collapse {
                        toggle()
                        true
                    }
                } else {
                    expand {
                        toggle()
                        true
                    }
                }
            },
        standIn = IconStandIn.Remainder(if (count > 0) "+${formats.integer(count.toLong())}" else "+"),
    )
}

/** SPEC 6.5: measured energy outside the power model, in its own group with a dashed tile. */
@Composable
private fun Unattributed(
    mah: Double,
    view: AppsView,
    formats: Formats,
) {
    val share = view.share(mah)
    Column(Modifier.padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.drain_gap_heading),
            // Full width, as the captions around it, so it aligns to the same edge in RTL.
            Modifier.fillMaxWidth().padding(bottom = CoulombSpacing.Space12).semantics { heading() },
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.label,
        )
        AppDrainRow(
            icon = null,
            name = stringResource(R.string.drain_unattributed_name),
            detail = stringResource(R.string.drain_unattributed_detail),
            value = formats.mah(mah),
            provenance = Provenance.DERIVED,
            share = share,
            shareText = formats.share(share),
            first = true,
            onClick = null,
            standIn = IconStandIn.Gap,
        )
    }
}

/** A key no UID takes. */
private const val DEVICE_KEY = "device"
