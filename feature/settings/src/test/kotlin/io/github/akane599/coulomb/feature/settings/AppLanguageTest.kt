// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.app.Application
import android.app.LocaleManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SPEC 14.4 "Language": Android keeps the app's language; Settings reads and sets it there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppLanguageTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun theChoiceIsWhatAndroidHoldsAndSystemClearsIt() {
        assertThat(AppLanguage.of(context)).isEqualTo(AppLanguage.SYSTEM)

        context.setAppLanguage(AppLanguage.TURKISH)
        assertThat(AppLanguage.of(context)).isEqualTo(AppLanguage.TURKISH)
        assertThat(context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()).isEqualTo("tr")

        context.setAppLanguage(AppLanguage.SYSTEM)
        assertThat(context.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty).isTrue()
        assertThat(AppLanguage.of(context)).isEqualTo(AppLanguage.SYSTEM)
    }
}
