// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.ChargeDetailEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.model.DesignCapacitySource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
internal class HealthRepositoryTest {
    private lateinit var db: CoulombDatabase
    private lateinit var repository: HealthRepository
    private val now = System.currentTimeMillis()
    private val minuteNs = 60_000_000_000L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CoulombDatabase.inMemory(context)
        val dispatchers = AppDispatchers(Dispatchers.IO, Dispatchers.Default)
        repository =
            HealthRepository(db.sessionDao(), db.sampleDao(), db.deviceDao(), { FINGERPRINT }, { ZoneOffset.UTC }, dispatchers)
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    /** A closed charge from 20% to 100% over 80 minutes, a sample a minute at 2 A and 4 V. */
    private suspend fun charge(): Long {
        val start = now - 2 * 3_600_000L
        val id =
            db.sessionDao().insert(
                SessionEntity(
                    kind = SessionKind.CHARGE,
                    bootSeq = 1,
                    startElapsedNs = 0,
                    endElapsedNs = 80 * minuteNs,
                    startWall = start,
                    endWall = start + 80 * 60_000L,
                    startSnapshotId = null,
                    endSnapshotId = null,
                    startLevel = 20,
                    endLevel = 100,
                    startChargeUah = null,
                    endChargeUah = null,
                    chargeSource = null,
                    avgCurrentMa = null,
                    peakCurrentMa = null,
                    minTempDeciC = 290,
                    meanTempDeciC = 320,
                    maxTempDeciC = 360,
                    awakeRatio = null,
                    deepIdleReached = null,
                    deepIdleReachedMs = null,
                    minutesAtOrAbove80 = 20,
                    minutesAt100 = 0,
                    stressIndex = 1.0,
                    cycleEquivalents = 0.8,
                    closeReason = SessionCloseReason.STATE_CHANGED,
                    quality = 1.0,
                ),
            )
        for (minute in 0..80) {
            db.sampleDao().insert(
                BatterySampleEntity(
                    1,
                    minute * minuteNs,
                    start + minute * 60_000L,
                    20 + minute,
                    null,
                    2_000_000,
                    2_000,
                    4_000,
                    320,
                    2,
                    2,
                    false,
                    0,
                    0,
                ),
            )
        }
        val charger = db.sessionDao().insertChargerProfile(ChargerProfileEntity(0, "", 2, 8.0, start, start + 80 * 60_000L, null))
        db.sessionDao().upsertChargeDetail(ChargeDetailEntity(id, 2, charger, 8.0, 8.0, null, 20, 0, null, null))
        return id
    }

    @Test
    fun aClosedChargeReachesTheReportAndOpensWithItsCurve() =
        runTest {
            val id = charge()
            val report = real { repository.report.first { it.sessions.isNotEmpty() } }
            assertThat(report.capacity).isNull()
            val row = report.sessions.single()
            assertThat(row.fromPct to row.toPct).isEqualTo(20 to 100)
            assertThat(report.moreSessions).isFalse()
            assertThat(report.habits?.depthPct).isWithin(1e-9).of(80.0)
            assertThat(report.chargers.single().baselineW).isEqualTo(8.0)

            val detail = checkNotNull(real { repository.session(id).first() })
            assertThat(detail.curve).hasSize(81)
            assertThat(detail.curve.first().watts).isWithin(1e-9).of(8.0)
            assertThat(detail.charger?.id).isEqualTo(report.chargers.single().id)
            assertThat(real { repository.session(id + 1).first() }).isNull()
        }

    /**
     * Compacted to minutes (SPEC 7.7): the charge began 30 s into the boot's first minute. That
     * minute counts when its first reading is the charge's, and starts the curve at the charge's start.
     */
    @Test
    fun aCompactedChargeKeepsTheMinuteItBeganIn() =
        runTest {
            val start = now - 2 * 3_600_000L
            val id =
                db.sessionDao().insert(
                    SessionEntity(
                        kind = SessionKind.CHARGE,
                        bootSeq = 1,
                        startElapsedNs = minuteNs / 2,
                        endElapsedNs = 3 * minuteNs,
                        startWall = start,
                        endWall = start + 150_000L,
                        startSnapshotId = null,
                        endSnapshotId = null,
                        startLevel = 50,
                        endLevel = 53,
                        startChargeUah = null,
                        endChargeUah = null,
                        chargeSource = null,
                        avgCurrentMa = null,
                        peakCurrentMa = null,
                        minTempDeciC = 290,
                        meanTempDeciC = 300,
                        maxTempDeciC = 310,
                        awakeRatio = null,
                        deepIdleReached = null,
                        deepIdleReachedMs = null,
                        minutesAtOrAbove80 = 0,
                        minutesAt100 = 0,
                        stressIndex = 0.0,
                        cycleEquivalents = 0.03,
                        closeReason = SessionCloseReason.STATE_CHANGED,
                        quality = 1.0,
                    ),
                )

            fun minute(
                index: Int,
                firstWall: Long,
            ) = "INSERT INTO sample_aggregate VALUES (60, 1, ${index * minuteNs}, $firstWall, 6, ${50 + index}, ${51 + index}, " +
                "NULL, NULL, 1900000, 2000000, 2100000, 4000, 300, 300, 300, 0.0, 2)"
            val sql = db.openHelper.writableDatabase
            sql.execSQL(minute(0, start))
            sql.execSQL(minute(1, start + 30_000L))
            sql.execSQL(minute(2, start + 90_000L))

            val curve = checkNotNull(real { repository.session(id).first() }).curve
            assertThat(curve).hasSize(3)
            assertThat(curve.first().wallMs).isEqualTo(start)
            assertThat(curve.map { it.wallMs }).isInStrictOrder()

            // A first reading from before the plug: that minute is the discharge's, not the charge's.
            sql.execSQL("UPDATE sample_aggregate SET startWallMs = ${start - 20_000L} WHERE startElapsedNs = 0")
            assertThat(checkNotNull(real { repository.session(id).first() }).curve).hasSize(2)
        }

    @Test
    fun aRenameAndTheOwnersDesignCapacityAreStored() =
        runTest {
            charge()
            db.deviceDao().upsertProfile(
                DeviceProfileEntity(FINGERPRINT, "t", "t", 37, 3_700_000, 5_000, "power_profile", 1, 1, true, 0, 0.9),
            )
            val charger = real { repository.report.first { it.chargers.isNotEmpty() } }.chargers.single()

            repository.renameCharger(charger.id, "  Desk charger  ")
            assertThat(
                real {
                    repository.report.first {
                        it.chargers
                            .single()
                            .name
                            .isNotEmpty()
                    }
                }.chargers.single().name,
            ).isEqualTo("Desk charger")

            assertThat(repository.setDesignCapacity(300)).isFalse()
            assertThat(repository.setDesignCapacity(4_800)).isTrue()
            val design = real { repository.report.first { it.design?.mah == 4_800.0 } }.design
            assertThat(design?.source).isEqualTo(DesignCapacitySource.USER)
        }

    private companion object {
        const val FINGERPRINT = "test/device:17/1"
    }
}
