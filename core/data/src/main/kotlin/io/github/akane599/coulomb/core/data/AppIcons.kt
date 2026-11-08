// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.LruCache
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.common.AppDispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App icons as the system provides them (DESIGN.md), from `PackageManager.getApplicationIcon`,
 * a public API any app may call (ADR-0032 §6). Icons are never stored; SPEC 8.1 keeps the last
 * [CACHE_SIZE] in memory. Bitmaps, so widgets can use them too. A package that has no icon is not
 * asked for again for [MISS_RETRY_MS], so a list of removed apps does not query PackageManager on
 * every scroll; after that an app installed again gets its icon.
 */
@Singleton
public class AppIcons
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        private val dispatchers: AppDispatchers,
    ) {
        private val pm = context.packageManager
        private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

        /** Packages that had no icon, with the elapsed time of the miss. */
        private val misses = LruCache<String, Long>(CACHE_SIZE)

        /** [packageName]'s icon at [sizePx] if it is already in memory; never loads, so the UI may call it while composing. */
        public fun peek(
            packageName: String,
            sizePx: Int,
        ): Bitmap? = cache.get("$packageName@$sizePx")

        /** [packageName]'s icon at [sizePx] square, or null when it is not installed. */
        public suspend fun icon(
            packageName: String,
            sizePx: Int,
        ): Bitmap? {
            val key = "$packageName@$sizePx"
            cache.get(key)?.let { return it }
            misses.get(packageName)?.let { if (SystemClock.elapsedRealtime() - it < MISS_RETRY_MS) return null }
            return withContext(dispatchers.io) {
                try {
                    val drawable = pm.getApplicationIcon(packageName)
                    createBitmap(sizePx, sizePx).also { bitmap ->
                        drawable.setBounds(0, 0, sizePx, sizePx)
                        drawable.draw(Canvas(bitmap))
                        cache.put(key, bitmap)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    misses.put(packageName, SystemClock.elapsedRealtime())
                    null
                }
            }
        }

        public companion object {
            /** SPEC 8.1: icons in an LRU of 64. */
            public const val CACHE_SIZE: Int = 64

            /** How long a package without an icon is not asked for again. */
            public const val MISS_RETRY_MS: Long = 60_000L
        }
    }
