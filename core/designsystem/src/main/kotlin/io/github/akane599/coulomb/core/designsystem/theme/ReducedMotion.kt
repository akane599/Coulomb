// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withContext

/** SPEC 11.6: under reduced motion every transition is a crossfade of this length. */
public const val REDUCED_FADE_MS: Int = 150

/**
 * True while the system's animator duration scale is 0, which "Remove animations" also sets. The
 * key is `Settings.Global.ANIMATOR_DURATION_SCALE` (core/java/android/provider/Settings.java,
 * android-16.0.0_r4 lines 17372-17380, android-17.0.0_r1 lines 17850-17858: "Setting to 0.0f will
 * cause animations to end immediately"). Observed, so a change applies without a restart.
 */
@Composable
public fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(animatorScale(resolver) == 0f) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = animatorScale(resolver) == 0f
                }
            }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun animatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * Compose scales every animation by the system's animator scale and ends it at once at 0. The
 * reduced crossfade runs on the real clock instead, so it keeps its 150 ms (SPEC 11.6).
 */
private object RealClock : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Fades [this] to [target]: SPEC 11.6's reduced crossfade, at the real clock. */
public suspend fun Animatable<Float, AnimationVector1D>.crossfadeTo(target: Float) {
    withContext(RealClock) { animateTo(target, tween(REDUCED_FADE_MS)) }
}
