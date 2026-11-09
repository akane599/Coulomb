// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentifiersTest {
    @Test
    fun aLongIdentifierBreaksAfterSeparatorsAndInsideALongCamelCaseName() {
        assertEquals(
            "HalcyonPush:\u200BNotification\u200BWake\u200BLock",
            breakable("HalcyonPush:NotificationWakeLock"),
        )
        assertEquals("app.\u200Bhalcyon/\u200B.\u200Bsync.\u200BSyncJob", breakable("app.halcyon/.sync.SyncJob"))
    }

    @Test
    fun aShortRunOrWordsKeepTheirOwnBreaks() {
        assertEquals("ExoPlayer:Lock", breakable("ExoPlayer:Lock"))
        assertEquals("Sync job for FeedPrefetch", breakable("Sync job for FeedPrefetch"))
        // All capitals: no humps to break at.
        assertEquals("GOOGLE_SERVICES_ALARM", breakable("GOOGLE_SERVICES_ALARM").replace("\u200B", ""))
    }
}
