// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombSwitchRow
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.component.animatePlacement
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.AppDrainRow
import io.github.akane599.coulomb.core.ui.IconStandIn
import io.github.akane599.coulomb.core.ui.format.Formats

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
    val measured = drains.measuredMah?.takeIf { it > 0 }
    val scaleTo = measured?.takeIf { scaled && drains.modeledMah > 0 }
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

/** SPEC 6.5: the unattributed row shows whenever it exceeds 10% of measured. */
private const val UNATTRIBUTED_ROW = 0.10

private val IconSize = 40.dp

@Composable
internal fun AppsLens(
    state: DrainUiState.Ready,
    report: DrainReport,
    formats: Formats,
    actions: DrainActions,
) {
    val view = appsView(report, state.includeSystem, state.scaled)
    val drains = report.drains
    val measured = drains.measuredMah?.takeIf { it > 0 }
    Column(Modifier.padding(top = CoulombSpacing.Space8)) {
        CoulombSwitchRow(stringResource(R.string.drain_include_system), state.includeSystem, actions.includeSystem)
        if (measured != null && drains.modeledMah > 0) {
            CoulombSwitchRow(stringResource(R.string.drain_scale), state.scaled, actions.scale)
        }
        Captions(view, report, formats)
        if (view.empty) {
            Text(
                stringResource(R.string.drain_no_apps),
                Modifier.padding(top = CoulombSpacing.Space12),
                color = CoulombTheme.colors.textSecondary,
                style = CoulombTheme.typography.body,
            )
        } else {
            AppRows(view, report, formats, actions)
        }
        view.unattributedMah?.let { Unattributed(it, view, formats) }
    }
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
            (drains.measuredMah ?: 0.0) >= drains.modeledMah -> stringResource(R.string.drain_shares_of_measured, total)
            else -> stringResource(R.string.drain_shares_of_modeled, total)
        }
    val captions = listOfNotNull(shares, if (report.lowerBound) stringResource(R.string.drain_lower_bound) else null)
    Column(Modifier.padding(top = CoulombSpacing.Space8)) {
        captions.forEach { Text(it, color = CoulombTheme.colors.textSecondary, style = CoulombTheme.typography.caption) }
    }
}

@Composable
private fun AppRows(
    view: AppsView,
    report: DrainReport,
    formats: Formats,
    actions: DrainActions,
) {
    var expanded by rememberSaveable(report.span) { mutableStateOf(false) }
    val provenance = if (view.scaled) Provenance.DERIVED else Provenance.MODELED
    Column(Modifier.padding(top = CoulombSpacing.Space12)) {
        // Keyed, so a reorder moves the rows instead of rewriting them (SPEC 11.6).
        view.rows.forEachIndexed { index, entry ->
            val first = index == 0
            when (entry) {
                is AppsEntry.App -> {
                    key(entry.app.uid) { AppRow(entry.app, view, first, provenance, formats, actions, Modifier.animatePlacement()) }
                }

                is AppsEntry.Device -> {
                    key(DEVICE_KEY) { DeviceRow(entry.mah, view, first, provenance, formats, Modifier.animatePlacement()) }
                }
            }
        }
        if (view.remainderMah > 0) {
            if (expanded) {
                Expanded(view, provenance, formats, actions, first = view.rows.isEmpty(), collapse = { expanded = false })
            } else {
                Remainder(view, provenance, formats, first = view.rows.isEmpty(), expand = { expanded = true })
            }
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
        icon = appIcon(app.iconPackage, actions.icon),
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
    expand: () -> Unit,
) {
    val count = view.rest.size + view.hidden.size
    val share = view.share(view.remainderMah)
    val name =
        if (count > 0) pluralStringResource(R.plurals.drain_more_apps, count, count) else stringResource(R.string.drain_small_apps)
    val detail =
        when {
            view.hidden.isNotEmpty() -> pluralStringResource(R.plurals.drain_hidden_system, view.hidden.size, view.hidden.size)
            view.rest.isNotEmpty() -> stringResource(R.string.drain_each_under, formats.share(ROW_SHARE))
            else -> null
        }
    AppDrainRow(
        icon = null,
        name = name,
        detail = detail,
        value = formats.mah(view.remainderMah * view.factor),
        provenance = provenance,
        share = share,
        shareText = formats.share(share),
        first = first,
        onClick = expand,
        modifier = Modifier.animatePlacement(),
        standIn = IconStandIn.Remainder(if (count > 0) "+${formats.integer(count.toLong())}" else "+"),
    )
}

/** The remainder opened: each small app, the hidden system apps as one row, the folded UIDs, and "Show fewer". */
@Composable
private fun Expanded(
    view: AppsView,
    provenance: Provenance,
    formats: Formats,
    actions: DrainActions,
    first: Boolean,
    collapse: () -> Unit,
) = Column {
    view.rest.forEachIndexed { index, app ->
        key(app.uid) { AppRow(app, view, first && index == 0, provenance, formats, actions, Modifier.animatePlacement()) }
    }
    val hiddenMah = view.hidden.sumOf { it.mah }
    if (view.hidden.isNotEmpty()) {
        val share = view.share(hiddenMah)
        AppDrainRow(
            icon = null,
            name = pluralStringResource(R.plurals.drain_hidden_system, view.hidden.size, view.hidden.size),
            detail = null,
            value = formats.mah(hiddenMah * view.factor),
            provenance = provenance,
            share = share,
            shareText = formats.share(share),
            first = first && view.rest.isEmpty(),
            onClick = null,
            modifier = Modifier.animatePlacement(),
            standIn = IconStandIn.Remainder("+${formats.integer(view.hidden.size.toLong())}"),
        )
    }
    if (view.otherMah > 0) {
        val share = view.share(view.otherMah)
        AppDrainRow(
            icon = null,
            name = stringResource(R.string.drain_small_apps),
            detail = null,
            value = formats.mah(view.otherMah * view.factor),
            provenance = provenance,
            share = share,
            shareText = formats.share(share),
            first = first && view.rest.isEmpty() && view.hidden.isEmpty(),
            onClick = null,
            modifier = Modifier.animatePlacement(),
            standIn = IconStandIn.Remainder("+"),
        )
    }
    CoulombTextButton(
        text = stringResource(R.string.drain_show_fewer),
        onClick = collapse,
        modifier = Modifier.padding(top = CoulombSpacing.Space4),
        edge = ButtonEdge.Start,
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
            Modifier.padding(bottom = CoulombSpacing.Space4).semantics { heading() },
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

/** The app's icon from the shared cache, loaded once per package and size. */
@Composable
internal fun appIcon(
    iconPackage: String?,
    icon: suspend (String, Int) -> ImageBitmap?,
): ImageBitmap? {
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val load by rememberUpdatedState(icon)
    val bitmap by produceState<ImageBitmap?>(null, iconPackage, sizePx) {
        value = iconPackage?.let { load(it, sizePx) }
    }
    return bitmap
}

/** A key no UID takes. */
private const val DEVICE_KEY = "device"
