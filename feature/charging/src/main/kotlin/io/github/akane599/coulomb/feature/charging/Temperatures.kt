// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.charging

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.dumpsys.ThermalDump
import io.github.akane599.coulomb.core.model.dumpsys.ThermalReading
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.page.TableRow

/** What the sheet shows of S19: the throttling status and each sensor, warmest first. */
@Immutable
internal data class Temperatures(
    /** `PowerManager.THERMAL_STATUS_*`, 0 to 6; null when the dump did not say. */
    val status: Int?,
    val overridden: Boolean,
    val sensors: List<ThermalReading>,
    /** The sensors are the service's cached values, read when the status last changed. */
    val cached: Boolean,
) {
    companion object {
        fun of(dump: ThermalDump): Temperatures {
            val current = dump.currentTemperatures.filter { it.deciC != null }
            val sensors = current.ifEmpty { dump.cachedTemperatures.filter { it.deciC != null } }
            return Temperatures(
                status = dump.status,
                overridden = dump.statusOverridden == true,
                sensors = sensors.sortedByDescending { it.deciC },
                cached = current.isEmpty() && sensors.isNotEmpty(),
            )
        }
    }
}

internal sealed interface TemperaturesState {
    data object Reading : TemperaturesState

    data object Failed : TemperaturesState

    data class Read(
        val temperatures: Temperatures,
    ) : TemperaturesState
}

/** The way from Charging's readings to every temperature sensor (S19, on demand). */
@Composable
internal fun TemperaturesRow(onOpen: () -> Unit) {
    CoulombTextButton(
        stringResource(R.string.charging_temperatures_open),
        onOpen,
        Modifier.padding(top = CoulombSpacing.Space8),
        edge = ButtonEdge.Start,
    )
}

/** Reads S19 when it opens and on Read again; nothing read is kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemperaturesSheet(
    read: suspend () -> ThermalDump?,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val formats = rememberFormats()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<TemperaturesState>(TemperaturesState.Reading) }
    val latestRead by rememberUpdatedState(read)
    LaunchedEffect(attempt) {
        state = TemperaturesState.Reading
        state = latestRead()?.let { TemperaturesState.Read(Temperatures.of(it)) } ?: TemperaturesState.Failed
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space24),
        ) {
            Text(
                stringResource(R.string.charging_temperatures),
                Modifier.semantics { heading() },
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            TemperaturesBody(state, formats)
            if (state != TemperaturesState.Reading) {
                CoulombTextButton(
                    stringResource(R.string.charging_temperatures_again),
                    { attempt++ },
                    Modifier.padding(top = CoulombSpacing.Space8),
                    edge = ButtonEdge.Start,
                )
            }
        }
    }
}

@Composable
internal fun TemperaturesBody(
    state: TemperaturesState,
    formats: Formats,
) {
    when (state) {
        TemperaturesState.Reading -> Note(stringResource(R.string.charging_temperatures_reading))
        TemperaturesState.Failed -> Note(stringResource(R.string.charging_temperatures_failed))
        is TemperaturesState.Read -> Sensors(state.temperatures, formats)
    }
}

@Composable
private fun Sensors(
    t: Temperatures,
    formats: Formats,
) {
    t.status?.let { status ->
        Note(stringResource(R.string.charging_temperatures_status, stringResource(statusName(status))))
    }
    if (t.overridden) Note(stringResource(R.string.charging_temperatures_overridden))
    if (t.sensors.isEmpty()) {
        Note(stringResource(R.string.charging_temperatures_none))
        return
    }
    Subhead(stringResource(R.string.charging_temperatures_sensors), provenance = Provenance.MEASURED)
    if (t.cached) Note(stringResource(R.string.charging_temperatures_cached), top = 0.dp)
    InlineTable(
        columns =
            listOf(
                stringResource(R.string.charging_temperatures_column_sensor),
                stringResource(R.string.charging_temperatures_column_temp),
            ),
        rows =
            t.sensors.map { sensor ->
                val kind = typeName(sensor.type)?.let { stringResource(it) }
                val level = stringResource(statusName(sensor.status))
                val throttling = stringResource(R.string.charging_temperatures_status, level).takeIf { sensor.status > 0 }
                // The sensor's own name only where it says more than its kind.
                val own = sensor.name.takeIf { kind != null && !it.equals(kind, ignoreCase = true) }
                TableRow(
                    listOfNotNull(kind ?: sensor.name, own, throttling).joinToString("\n"),
                    listOf(formats.temperature(sensor.deciC!!.toDouble()).joined()),
                )
            },
    )
}

/** `PowerManager.THERMAL_STATUS_*`, 0 none to 6 shutdown. */
@Suppress("MagicNumber") // The platform's status values.
private fun statusName(status: Int): Int =
    when (status) {
        0 -> R.string.charging_thermal_none
        1 -> R.string.charging_thermal_light
        2 -> R.string.charging_thermal_moderate
        3 -> R.string.charging_thermal_severe
        4 -> R.string.charging_thermal_critical
        5 -> R.string.charging_thermal_emergency
        else -> R.string.charging_thermal_shutdown
    }

/** The thermal HAL's `TemperatureType` for the commonest sensors ([ThermalReading]); the rest show their own name. */
@Suppress("MagicNumber") // The HAL's TemperatureType values.
private fun typeName(type: Int): Int? =
    when (type) {
        0 -> R.string.charging_sensor_cpu
        1 -> R.string.charging_sensor_gpu
        2 -> R.string.charging_sensor_battery
        3 -> R.string.charging_sensor_skin
        4 -> R.string.charging_sensor_usb
        11 -> R.string.charging_sensor_display
        12 -> R.string.charging_sensor_modem
        13 -> R.string.charging_sensor_soc
        else -> null
    }
