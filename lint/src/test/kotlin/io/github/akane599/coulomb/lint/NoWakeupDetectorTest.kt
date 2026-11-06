// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask
import org.junit.Test

class NoWakeupDetectorTest {
    private fun lint(vararg files: TestFile) =
        TestLintTask
            .lint()
            .files(*files)
            .issues(NoWakeupDetector.ALARM_MANAGER, NoWakeupDetector.UNTIMED_WAKE_LOCK)
            .run()

    @Test
    fun everyAlarmManagerCallIsAnError() {
        lint(
            kotlin(
                """
                package test

                import android.app.AlarmManager
                import android.app.PendingIntent

                fun schedule(alarms: AlarmManager, intent: PendingIntent) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, 0L, intent)
                    alarms.cancel(intent)
                }
                """,
            ).indented(),
        ).expect(
            """
            src/test/test.kt:7: Error: android.app.AlarmManager.setExactAndAllowWhileIdle is not allowed: Coulomb never sets alarms (SPEC 7.4) [CoulombAlarmManager]
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, 0L, intent)
                       ~~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/test.kt:8: Error: android.app.AlarmManager.cancel is not allowed: Coulomb never sets alarms (SPEC 7.4) [CoulombAlarmManager]
                alarms.cancel(intent)
                       ~~~~~~
            2 errors
            """,
        )
    }

    @Test
    fun wakelocksNeedAConstantTimeoutOfAtMostTwoSeconds() {
        lint(
            kotlin(
                """
                package test

                import android.os.PowerManager

                private const val SHORT = 1_500L

                fun hold(lock: PowerManager.WakeLock, computed: Long) {
                    lock.acquire()
                    lock.acquire(10 * 60_000L)
                    lock.acquire(2_000L)
                    lock.acquire(SHORT)
                    lock.acquire(computed)
                    lock.release()
                }
                """,
            ).indented(),
        ).expect(
            """
            src/test/test.kt:8: Error: WakeLock.acquire() without a timeout; pass at most 2000 ms (SPEC 7.4) [CoulombUntimedWakeLock]
                lock.acquire()
                ~~~~~~~~~~~~~~
            src/test/test.kt:9: Error: Wakelock timeout of 600000 ms; the limit is 2000 ms (SPEC 7.4) [CoulombUntimedWakeLock]
                lock.acquire(10 * 60_000L)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~
            2 errors
            """,
        )
    }

    @Test
    fun javaCallsAreCheckedToo() {
        lint(
            java(
                """
                package test;

                import android.os.PowerManager;

                class Holder {
                    void hold(PowerManager.WakeLock lock) {
                        lock.acquire();
                    }
                }
                """,
            ).indented(),
        ).expectErrorCount(1)
    }

    @Test
    fun otherAcquireMethodsAreFine() {
        lint(
            kotlin(
                """
                package test

                class Gate {
                    fun acquire() = Unit
                }

                fun use(gate: Gate) = gate.acquire()
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun anAllowlistedClassSaysSoWithSuppressLint() {
        lint(
            kotlin(
                """
                package test

                import android.annotation.SuppressLint
                import android.app.AlarmManager
                import android.app.PendingIntent

                // Reason: an example; the real allowlist starts empty (ADR-0016).
                @SuppressLint("CoulombAlarmManager")
                class Allowed {
                    fun clear(alarms: AlarmManager, intent: PendingIntent) = alarms.cancel(intent)
                }
                """,
            ).indented(),
        ).expectClean()
    }
}
