// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * SPEC 14.4 "Language". Android keeps the choice per app and applies it to every screen,
 * notification and widget of Coulomb, so nothing is stored here. [tag] is null for the system's
 * languages.
 */
internal enum class AppLanguage(
    val tag: String?,
) {
    SYSTEM(null),
    TURKISH("tr"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * The app's own languages as set, an empty list for the system's.
         * AOSP: `LocaleManager.getApplicationLocales()`,
         * frameworks/base/core/java/android/app/LocaleManager.java:118-127 (android-16.0.0_r4 and android-17.0.0_r1).
         */
        fun of(context: Context): AppLanguage {
            val set = context.getSystemService(LocaleManager::class.java).applicationLocales
            val first = if (set.isEmpty) null else set[0].language
            return entries.firstOrNull { it.tag == first } ?: SYSTEM
        }
    }
}

/**
 * Sets Coulomb's language; the empty list goes back to the system's. Android stores it and
 * recreates the activity in the new language.
 * AOSP: `LocaleManager.setApplicationLocales(LocaleList)`,
 * frameworks/base/core/java/android/app/LocaleManager.java:54-77 (android-16.0.0_r4 and android-17.0.0_r1).
 */
internal fun Context.setAppLanguage(language: AppLanguage) {
    val locales = language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
    getSystemService(LocaleManager::class.java).applicationLocales = locales
}
