// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegeDrills
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.capability.CapabilityProber
import io.github.akane599.coulomb.core.privileged.capability.CapabilityReport
import io.github.akane599.coulomb.core.ui.privilege.copy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * Debug-only console for the P1 protocol F drills (SPEC 16.3, 16.6). Not a product screen: it is
 * absent from release builds and is driven over adb, e.g.
 * `am start -n <app>/io.github.akane599.coulomb.debug.PrivilegeConsoleActivity --es drill stream --el bytes 20971520`.
 * Exported only to callers holding DUMP (adb shell has it; apps do not). Every result is one
 * `CoulombDrill` logcat line.
 */
@AndroidEntryPoint
class PrivilegeConsoleActivity : ComponentActivity() {
    @Inject lateinit var access: PrivilegedAccess

    @Inject lateinit var drills: PrivilegeDrills

    @Inject lateinit var prober: CapabilityProber

    private lateinit var text: TextView
    private var running: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(text) })
        lifecycleScope.launch {
            access.state.collectLatest { s ->
                val issue = s.issue?.copy()?.let { "${getString(it.title)} / ${getString(it.body)}" }
                log(
                    "state tier=${s.tier} manager=${s.manager} permission=${s.permission} worker=${s.worker} " +
                        "failure=${s.workerFailure} grants=${s.grants} issue=${s.issue} copy=\"${issue.orEmpty()}\"",
                )
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { access.refreshGrants() }
    }

    private fun handle(intent: Intent) {
        val drill = intent.getStringExtra("drill") ?: return
        running =
            lifecycleScope.launch {
                log("drill start $drill")
                when (drill) {
                    "state" -> {
                        log("diagnostics ${access.diagnostics.value}")
                    }

                    "request" -> {
                        access.requestManagerPermission()
                    }

                    "bootstrap" -> {
                        log("bootstrap ${access.bootstrap()}")
                    }

                    "adb" -> {
                        access.adbBootstrapCommands().forEach { log("adb $it") }
                    }

                    "grants" -> {
                        log("grants ${access.refreshGrants()}")
                    }

                    "stream" -> {
                        // Bounded so a caller holding DUMP cannot tie up the worker for long.
                        val bytes = intent.getLongExtra("bytes", TWENTY_MB).coerceIn(0L, MAX_DRILL_BYTES)
                        val truncateAt = intent.getLongExtra("truncate", bytes)
                        val delay = intent.getIntExtra("delay", 0).coerceIn(0, MAX_DRILL_DELAY_MS)
                        val timeout = intent.getLongExtra("timeout", STREAM_TIMEOUT_MS).coerceIn(1L, MAX_DRILL_TIMEOUT_MS)
                        log("stream ${summary(drills.streamTestPattern(bytes, truncateAt, delay, timeout))}")
                    }

                    "dump" -> {
                        val mode =
                            BatteryStatsMode.entries.firstOrNull { it.name == intent.getStringExtra("mode") } ?: BatteryStatsMode.PROTO
                        val result =
                            access.dumpBatteryStats(mode) { input ->
                                val buffer = ByteArray(BUFFER)
                                var head = ""
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    if (total == 0L) head = printableHead(buffer, n)
                                    total += n
                                }
                                "$total bytes, head \"$head\""
                            }
                        log("dump $mode ${summary(result)}")
                    }

                    "fingerprint" -> {
                        drills.simulateFingerprintChange()
                        log("fingerprint diagnostics=${access.diagnostics.value}")
                    }

                    "bindhang" -> {
                        drills.setSimulatedBindHang(intent.getBooleanExtra("on", true))
                    }

                    "resetworker" -> {
                        drills.resetWorker()
                    }

                    "release" -> {
                        access.releaseWorker()
                    }

                    "probe" -> {
                        val caps = prober.probeAll()
                        val title =
                            intent.getStringExtra("title")?.filter { it.isLetterOrDigit() || it in " -_." }?.take(MAX_TITLE) ?: "device"
                        val report =
                            CapabilityReport.markdown(
                                caps,
                                access.state.value,
                                access.diagnostics.value,
                                title,
                                Instant.now().toString(),
                            )
                        File(filesDir, "capability-report.md").writeText(report)
                        caps.forEach { log("cap ${it.id.key} ${it.state} ${it.tier} ${it.reason.orEmpty()} ${it.detail}") }
                        log("probe wrote files/capability-report.md")
                    }

                    else -> {
                        log("unknown drill $drill")
                    }
                }
                log("drill end $drill")
            }
    }

    private fun summary(result: DumpResult<*>): String =
        when (result) {
            is DumpResult.Complete -> {
                "COMPLETE route=${result.route} bytes=${result.bytes} ms=${result.durationMs} value=${result.value}"
            }

            is DumpResult.Partial -> {
                "PARTIAL route=${result.route} bytes=${result.bytes} reason=${result.reason} detail=${result.detail} value=${result.value}"
            }

            is DumpResult.Unavailable -> {
                "UNAVAILABLE reason=${result.reason} detail=${result.detail}"
            }
        }

    private fun log(line: String) {
        Log.i(TAG, line)
        text.append(line + "\n")
    }

    private companion object {
        const val TAG = "CoulombDrill"
        const val HEAD_CHARS = 96
        const val MAX_DRILL_BYTES = 64L * 1024 * 1024
        const val MAX_DRILL_DELAY_MS = 200
        const val MAX_DRILL_TIMEOUT_MS = 120_000L
        const val MAX_TITLE = 64

        /** The first bytes as ASCII (others as '.'), enough to tell a dump from an error message. */
        fun printableHead(
            buffer: ByteArray,
            length: Int,
        ): String =
            String(
                CharArray(minOf(length, HEAD_CHARS)) { i ->
                    buffer[i].toInt().toChar().takeIf { it in ' '..'~' } ?: '.'
                },
            )

        const val TWENTY_MB = 20L * 1024 * 1024
        const val STREAM_TIMEOUT_MS = 120_000L
        const val BUFFER = 64 * 1024
    }
}
