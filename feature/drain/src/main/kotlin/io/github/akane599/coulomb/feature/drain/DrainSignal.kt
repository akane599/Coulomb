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
import io.github.akane599.coulomb.core.data.SignalTime
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.page.TableRow

/**
 * The window's time by signal strength under the Components lens (SPEC 2, "signal strength
 * distribution": time per signal bin and scanning time), where the modem's share is: a weak signal
 * is why the radio costs more. Strongest first; a strength the phone never had is left out.
 */
internal fun LazyListScope.signalItem(
    report: DrainReport,
    formats: Formats,
) {
    val signal = report.signal ?: return
    item(key = "components-signal", contentType = "signal") { Signal(signal, formats) }
}

@Composable
private fun Signal(
    signal: SignalTime,
    formats: Formats,
) {
    val total = signal.totalMs
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space16)) {
        Subhead(
            stringResource(R.string.drain_signal),
            definition = stringResource(R.string.drain_signal_definition),
            provenance = Provenance.MEASURED,
        )
        Note(stringResource(R.string.drain_signal_caption), top = 0.dp)
        val rows =
            signal.binsMs
                .withIndex()
                .reversed()
                .filter { it.value > 0 }
                .map { (bin, ms) ->
                    TableRow(stringResource(BIN_NAMES[bin]), listOf(formats.held(ms), formats.share(ms.toDouble() / total)))
                }
        InlineTable(
            columns =
                listOf(
                    stringResource(R.string.drain_column_signal),
                    stringResource(R.string.drain_column_time),
                    stringResource(R.string.drain_column_share),
                ),
            rows = rows,
        )
        if (signal.scanningMs > 0) Note(stringResource(R.string.drain_signal_scanning, formats.held(signal.scanningMs)))
    }
}

/** Android's signal strengths, 0 (none or unknown) to 4 (great): CellSignalStrength's levels. */
private val BIN_NAMES =
    listOf(
        R.string.drain_signal_none,
        R.string.drain_signal_poor,
        R.string.drain_signal_moderate,
        R.string.drain_signal_good,
        R.string.drain_signal_great,
    )
