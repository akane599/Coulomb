// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.common.AppDispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App icons as the system provides them (DESIGN.md), from `PackageManager.getApplicationIcon`,
 * a public API any app may call (ADR-0032 §6). Icons are never stored; SPEC 8.1 keeps the last
 * [CACHE_SIZE] in memory. Bitmaps, so widgets can use them too.
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

        /** [packageName]'s icon at [sizePx] square, or null when it is not installed. */
        public suspend fun icon(
            packageName: String,
            sizePx: Int,
        ): Bitmap? {
            val key = "$packageName@$sizePx"
            cache.get(key)?.let { return it }
            return withContext(dispatchers.io) {
                try {
                    val drawable = pm.getApplicationIcon(packageName)
                    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
                        drawable.setBounds(0, 0, sizePx, sizePx)
                        drawable.draw(Canvas(bitmap))
                        cache.put(key, bitmap)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }

        public companion object {
            /** SPEC 8.1: icons in an LRU of 64. */
            public const val CACHE_SIZE: Int = 64
        }
    }
