// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import io.github.akane599.coulomb.core.datastore.proto.Settings
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [Settings] on disk as protobuf. An unreadable file is a [CorruptionException], which the store replaces with the defaults. */
internal object SettingsSerializer : Serializer<Settings> {
    override val defaultValue: Settings = Settings()

    override suspend fun readFrom(input: InputStream): Settings =
        try {
            Settings.ADAPTER.decode(input)
        } catch (e: IOException) {
            throw CorruptionException("Unreadable settings", e)
        }

    override suspend fun writeTo(
        t: Settings,
        output: OutputStream,
    ) {
        Settings.ADAPTER.encode(output, t)
    }
}
