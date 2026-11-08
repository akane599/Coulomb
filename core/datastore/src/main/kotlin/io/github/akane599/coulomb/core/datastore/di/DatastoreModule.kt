// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.datastore.SettingsSerializer
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatastoreModule {
    /** One store for the process; a corrupt file falls back to the defaults rather than failing every read. */
    @Provides
    @Singleton
    fun settings(
        @ApplicationContext context: Context,
    ): DataStore<Settings> =
        DataStoreFactory.create(
            serializer = SettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.dataStoreFile(FILE) },
        )

    private const val FILE = "settings.pb"
}
