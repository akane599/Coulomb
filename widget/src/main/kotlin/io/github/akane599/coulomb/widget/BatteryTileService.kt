// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.app.PendingIntent
import android.content.res.Resources
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.akane599.coulomb.core.common.OpenRequest
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.BatteryRepository
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.formatsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Quick Settings tile (SPEC 12.5): the level as its label and the drain rate under it. It reads
 * the latest stored sample when the panel opens and asks for no new one; a tap opens Now, and a
 * long press opens the app's settings through the activity's `QS_TILE_PREFERENCES` filter.
 */
@AndroidEntryPoint
class BatteryTileService : TileService() {
    @Inject lateinit var battery: BatteryRepository

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        listening.launch {
            // A failed read leaves the tile with its name alone.
            show(battery.reading.catch { emit(null) }.first())
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                openIntent(OpenRequest.NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        startActivityAndCollapse(open)
    }

    private fun show(reading: BatteryReading?) {
        val tile = qsTile ?: return
        val name = getString(R.string.tile_label)
        if (reading == null) {
            tile.label = name
            tile.subtitle = null
            tile.state = Tile.STATE_INACTIVE
        } else {
            val words = TileWords.of(reading, formatsFor(this), resources)
            tile.label = words.level
            tile.subtitle = words.detail
            tile.contentDescription = getString(R.string.tile_spoken, words.level, words.detail)
            // Lit while the battery takes charge; nothing here switches anything.
            tile.state = if (words.state == PowerState.CHARGING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}

/** What the tile says. */
internal data class TileWords(
    val level: String,
    val detail: String,
    val state: PowerState,
) {
    companion object {
        /**
         * On battery, the rate of the screen-on time, which is the state the phone is in while the
         * panel is open; before there is one, the screen-off rate; before either, the state alone.
         */
        fun of(
            reading: BatteryReading,
            formats: Formats,
            resources: Resources,
        ): TileWords {
            val state = PowerState.of(reading.battery)
            val discharge = reading.discharge.takeIf { state == PowerState.ON_BATTERY }
            val on = discharge?.screenOn?.takeIf { it.ready }
            val off = discharge?.screenOff?.takeIf { it.ready }
            val rate = on ?: off
            val detail =
                if (rate == null) {
                    resources.getString(state.label())
                } else {
                    val figure = "${formats.rate(rate.pctPerHour)} ${formats.rateUnit}"
                    resources.getString(if (on != null) R.string.tile_rate_on else R.string.tile_rate_off, figure)
                }
            return TileWords(formats.percent(reading.battery.levelPct), detail, state)
        }
    }
}
