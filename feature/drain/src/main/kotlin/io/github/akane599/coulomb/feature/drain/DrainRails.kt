// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.RailDrain
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.page.TableRow

/**
 * S16's power monitors under the Components lens (SPEC 6.4: "energy per rail per window"). Their
 * names are the maker's and differ between devices (PowerMonitor.java, android-17.0.0_r1 L27-37),
 * so they are listed as given and do not replace the modeled components above (ADR-0068): rails
 * first, Measured, then the maker's energy consumers, which may be modeled.
 */
internal fun LazyListScope.railsItem(
    report: DrainReport,
    formats: Formats,
) {
    val rails = report.rails ?: return
    item(key = "components-rails", contentType = "rails") { Rails(rails, formats) }
}

@Composable
private fun Rails(
    all: List<RailDrain>,
    formats: Formats,
) {
    val (rails, consumers) = all.partition { it.rail }
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16)) {
        Subhead(
            stringResource(R.string.drain_rails),
            definition = stringResource(R.string.drain_rails_definition),
            provenance = Provenance.MEASURED.takeIf { rails.isNotEmpty() },
        )
        Note(stringResource(R.string.drain_rails_caption), top = 0.dp)
        if (rails.isNotEmpty()) RailTable(R.string.drain_column_rail, rails, formats)
        if (consumers.isNotEmpty()) {
            RailTable(R.string.drain_column_consumer, consumers, formats)
            Note(stringResource(R.string.drain_rails_consumers))
        }
    }
}

@Composable
private fun RailTable(
    nameColumn: Int,
    rows: List<RailDrain>,
    formats: Formats,
) {
    val total = rows.sumOf { it.energyUws }.toDouble()
    InlineTable(
        columns =
            listOf(
                stringResource(nameColumn),
                stringResource(R.string.drain_column_energy),
                stringResource(R.string.drain_column_share),
            ),
        rows =
            rows.filter { it.energyUws > 0 }.map {
                TableRow(
                    it.name,
                    listOf(
                        stringResource(R.string.drain_rail_mwh, formats.amount(it.mwh)),
                        formats.share(
                            if (total >
                                0
                            ) {
                                it.energyUws / total
                            } else {
                                0.0
                            },
                        ),
                    ),
                )
            },
    )
}
