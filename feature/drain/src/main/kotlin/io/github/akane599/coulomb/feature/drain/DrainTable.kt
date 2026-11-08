// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.format.Formats

/** The bars whose figures "View as table" opens (SPEC 11.8). */
internal enum class DrainTable { RECONCILE, COMPONENTS }

/** [open]'s figures as a table: the same numbers the bar and its key or rows show. */
@Composable
internal fun tableOf(
    open: DrainTable,
    report: DrainReport,
    formats: Formats,
): DataTable {
    val energy = stringResource(R.string.drain_column_energy)
    return when (open) {
        DrainTable.RECONCILE -> {
            val drains = report.drains
            val measured = drains.measuredMah ?: 0.0
            val gap = drains.unattributedMah
            DataTable(
                title = stringResource(R.string.drain_table_reconcile),
                columns = listOf(stringResource(R.string.drain_column_part), energy, stringResource(R.string.drain_column_share_measured)),
                rows =
                    listOf(
                        listOf(stringResource(R.string.drain_row_measured), formats.mah(measured).joined(), formats.share(1.0)),
                        listOf(
                            stringResource(R.string.drain_row_modeled),
                            formats.mah(drains.modeledMah).joined(),
                            formats.share(drains.modeledMah / measured),
                        ),
                        listOf(stringResource(R.string.drain_unattributed_name), formats.mah(gap).joined(), formats.share(gap / measured)),
                    ),
                provenance = null,
                rowProvenance = listOf(drains.measuredProvenance ?: Provenance.MEASURED, Provenance.MODELED, Provenance.DERIVED),
            )
        }

        DrainTable.COMPONENTS -> {
            val total = report.components.sumOf { it.mah }
            DataTable(
                title = stringResource(R.string.drain_table_components),
                // The components caption names the base: the modeled total.
                columns = listOf(stringResource(R.string.drain_column_component), energy, stringResource(R.string.drain_column_share)),
                rows =
                    report.components.map {
                        listOf(stringResource(it.component.label), formats.mah(it.mah).joined(), formats.share(it.mah / total))
                    },
                provenance = Provenance.MODELED,
            )
        }
    }
}
