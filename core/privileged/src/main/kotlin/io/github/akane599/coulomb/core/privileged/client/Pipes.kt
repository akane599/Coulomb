// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.os.ParcelFileDescriptor
import java.io.InputStream

/** Creates pipes; the indirection lets JVM tests use in-memory pipes. */
internal fun interface PipeFactory {
    fun create(): PipePair<ParcelFileDescriptor>
}

/** `ParcelFileDescriptor.createPipe()` (public API since 9): [0] read end, [1] write end. */
internal object AndroidPipes : PipeFactory {
    override fun create(): PipePair<ParcelFileDescriptor> {
        val (read, write) = ParcelFileDescriptor.createPipe()
        return object : PipePair<ParcelFileDescriptor> {
            override val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(read)
            override val sink: ParcelFileDescriptor = write

            override fun closeSink() {
                runCatching { write.close() }
            }

            override fun closeInput() {
                runCatching { input.close() }
            }
        }
    }
}
