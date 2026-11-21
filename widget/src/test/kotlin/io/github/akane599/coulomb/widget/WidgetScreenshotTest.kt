// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * The four widgets of SPEC 12.4 as a launcher would draw them: Glance's `RemoteViews`, inflated
 * into a view of the widget's size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class WidgetScreenshotTest {
    private class Fixed(
        private val content: @Composable () -> Unit,
    ) : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact

        override suspend fun provideGlance(
            context: Context,
            id: GlanceId,
        ) = provideContent { content() }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun capture(
        name: String,
        size: DpSize,
        content: @Composable () -> Unit,
    ) {
        val context: Context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val width = (size.width.value * density).toInt()
        val height = (size.height.value * density).toInt()
        val views = runBlocking { Fixed(content).compose(context, size = size) }
        val host = FrameLayout(context)
        host.addView(views.apply(context, host), width, height)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
        // A widget has no activity: drawn into a bitmap, as the launcher's window would be.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bitmap))
        bitmap.captureRoboImage("src/test/screenshots/$name.png")
    }

    private val now = 1_763_719_200_000L

    @Test
    fun level() = capture("widget-level", DpSize(170.dp, 64.dp)) { LevelContent(Samples.level) }

    @Test
    fun levelTall() = capture("widget-level-tall", DpSize(170.dp, 150.dp)) { LevelContent(Samples.level) }

    @Test
    fun levelWide() = capture("widget-level-wide", DpSize(340.dp, 110.dp)) { LevelContent(Samples.level) }

    @Test
    fun levelCharging() =
        capture("widget-level-charging", DpSize(170.dp, 64.dp)) {
            LevelContent(Samples.level.copy(state = PowerState.CHARGING, story = null))
        }

    @Test
    fun levelEmpty() = capture("widget-level-empty", DpSize(170.dp, 64.dp)) { LevelContent(null) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun levelDark() = capture("widget-level-dark", DpSize(170.dp, 150.dp)) { LevelContent(Samples.level) }

    @Test
    fun drains() = capture("widget-drains", DpSize(340.dp, 160.dp)) { DrainsContent(Samples.drains) }

    @Test
    fun drainsShort() = capture("widget-drains-short", DpSize(340.dp, 110.dp)) { DrainsContent(Samples.drains) }

    @Test
    fun drainsEmpty() = capture("widget-drains-empty", DpSize(340.dp, 160.dp)) { DrainsContent(null) }

    @Test
    @Config(qualifiers = "tr-w412dp-h915dp-night-xhdpi")
    fun drainsTurkishDark() = capture("widget-drains-tr-dark", DpSize(340.dp, 160.dp)) { DrainsContent(Samples.drains) }

    @Test
    fun night() = capture("widget-night", DpSize(340.dp, 160.dp)) { NightContent(Samples.night(now), ZoneOffset.UTC) }

    @Test
    fun nightCharged() =
        capture("widget-night-charged", DpSize(340.dp, 160.dp)) {
            NightContent(Samples.night(now).copy(charged = true, ratePctPerHour = null), ZoneOffset.UTC)
        }

    @Test
    fun nightFlat() =
        capture("widget-night-flat", DpSize(340.dp, 160.dp)) {
            NightContent(Samples.night(now).copy(endLevel = 79, ratePctPerHour = null), ZoneOffset.UTC)
        }

    @Test
    fun nightEmpty() = capture("widget-night-empty", DpSize(340.dp, 160.dp)) { NightContent(null, ZoneOffset.UTC) }

    @Test
    fun health() = capture("widget-health", DpSize(170.dp, 160.dp)) { HealthContent(Samples.health) }

    @Test
    fun healthWide() = capture("widget-health-wide", DpSize(260.dp, 160.dp)) { HealthContent(Samples.health) }

    @Test
    fun healthEmpty() = capture("widget-health-empty", DpSize(170.dp, 160.dp)) { HealthContent(null) }

    // Each widget at the smallest size its provider declares, and at the largest text: nothing may be cut (SPEC 12.4).
    @Test
    fun levelSmallest() = capture("widget-level-min", DpSize(110.dp, 50.dp)) { LevelContent(Samples.level) }

    @Test
    fun levelSmallestEmpty() = capture("widget-level-min-empty", DpSize(110.dp, 50.dp)) { LevelContent(null) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi", fontScale = 2f)
    fun levelLargeText() = capture("widget-level-200", DpSize(170.dp, 64.dp)) { LevelContent(Samples.level) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi", fontScale = 2f)
    fun levelTallLargeText() = capture("widget-level-tall-200", DpSize(170.dp, 150.dp)) { LevelContent(Samples.level) }

    @Test
    fun drainsSmallest() = capture("widget-drains-min", DpSize(250.dp, 110.dp)) { DrainsContent(Samples.drains) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi", fontScale = 2f)
    fun drainsLargeText() = capture("widget-drains-200", DpSize(340.dp, 160.dp)) { DrainsContent(Samples.drains) }

    @Test
    fun nightSmallest() = capture("widget-night-min", DpSize(250.dp, 110.dp)) { NightContent(Samples.night(now), ZoneOffset.UTC) }

    @Test
    fun nightSmallestEmpty() = capture("widget-night-min-empty", DpSize(250.dp, 110.dp)) { NightContent(null, ZoneOffset.UTC) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi", fontScale = 2f)
    fun nightLargeText() = capture("widget-night-200", DpSize(340.dp, 160.dp)) { NightContent(Samples.night(now), ZoneOffset.UTC) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi", fontScale = 2f)
    fun nightSmallestLargeText() =
        capture("widget-night-min-200", DpSize(250.dp, 110.dp)) { NightContent(Samples.night(now), ZoneOffset.UTC) }

    @Test
    @Config(qualifiers = "tr-w412dp-h915dp-night-xhdpi")
    fun nightTurkishDark() = capture("widget-night-tr-dark", DpSize(340.dp, 160.dp)) { NightContent(Samples.night(now), ZoneOffset.UTC) }

    @Test
    fun healthSmallest() = capture("widget-health-min", DpSize(110.dp, 110.dp)) { HealthContent(Samples.health) }

    @Test
    fun healthSmallestEmpty() = capture("widget-health-min-empty", DpSize(110.dp, 110.dp)) { HealthContent(null) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi", fontScale = 2f)
    fun healthLargeText() = capture("widget-health-200", DpSize(170.dp, 160.dp)) { HealthContent(Samples.health) }

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun healthDark() = capture("widget-health-dark", DpSize(170.dp, 160.dp)) { HealthContent(Samples.health) }

    @Test
    @Config(qualifiers = "tr-w412dp-h915dp-xhdpi")
    fun healthTurkish() = capture("widget-health-tr", DpSize(170.dp, 160.dp)) { HealthContent(Samples.health) }
}
