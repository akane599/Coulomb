// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.PowerMonitor
import android.os.Process
import android.os.health.SystemHealthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.AccessTier.T0_UNPRIVILEGED
import io.github.akane599.coulomb.core.model.AccessTier.T1_DURABLE_GRANTS
import io.github.akane599.coulomb.core.model.AccessTier.T2_PRIVILEGED_WORKER
import io.github.akane599.coulomb.core.model.AccessTier.T3_ROOT
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.CapabilityState.AVAILABLE
import io.github.akane599.coulomb.core.model.CapabilityState.DEGRADED
import io.github.akane599.coulomb.core.model.CapabilityState.UNAVAILABLE
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedQuery
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.Route
import io.github.akane599.coulomb.core.privileged.SettingsNamespace
import io.github.akane599.coulomb.core.privileged.SysfsRoot
import io.github.akane599.coulomb.core.privileged.SystemService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * The SPEC 4.6 probe suite. Runs on demand (and, from P2, at first start and after an OS update).
 * Probes only record whether something works, at which tier, and non-personal details (flag
 * names, key names, file names, counts); never values that could identify the user or device
 * (the battery serial number property is never read).
 */
public class CapabilityProber
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val access: PrivilegedAccess,
        private val config: PrivilegeConfig,
    ) {
        public suspend fun probeAll(): List<Capability> {
            access.refreshGrants()
            return buildList {
                add(batteryChanged())
                addAll(batteryManagerCounters())
                add(healthProperties())
                add(batteryStatsFlags())
                add(batteryStatsDump(CapabilityId.BATTERYSTATS_PROTO, BatteryStatsMode.PROTO))
                add(batteryStatsDump(CapabilityId.BATTERYSTATS_CHECKIN, BatteryStatsMode.CHECKIN))
                add(historyCursor())
                add(kernelWakelocks())
                addAll(kernelSources())
                add(serviceDump(CapabilityId.ALARM_DUMP, SystemService.ALARM))
                add(serviceDump(CapabilityId.DEVICEIDLE_DUMP, SystemService.DEVICE_IDLE))
                add(serviceDump(CapabilityId.POWER_DUMP, SystemService.POWER))
                add(serviceDump(CapabilityId.JOBSCHEDULER_DUMP, SystemService.JOB_SCHEDULER))
                add(usageStats())
                add(standbyBuckets())
                add(powerMonitors())
                add(uidHealthStats())
                add(thermal())
                add(serviceDump(CapabilityId.BATTERY_DUMP, SystemService.BATTERY))
                add(protectionSettings())
                add(powerSupply())
                add(promotedNotifications())
                // Last: by now the probes above have bound the worker if it can bind.
                add(workerBind())
            }
        }

        // --- T0 ------------------------------------------------------------------------------------------

        private fun batteryChanged(): Capability {
            val id = CapabilityId.BATTERY_CHANGED
            val sticky: Intent =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?: return unavailable(id, T0_UNPRIVILEGED, "no sticky broadcast", FixAction.NONE)
            val cycles = sticky.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            return available(id, T0_UNPRIVILEGED, mapOf("extra_cycle_count" to (cycles >= 0).toString()))
        }

        /** S2: `Long.MIN_VALUE` and 0 mean "not supported" (SPEC 5 S2). */
        private fun batteryManagerCounters(): List<Capability> {
            val bm = context.getSystemService(BatteryManager::class.java)
            val current = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Long.MIN_VALUE
            val counter = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
            val currentId = CapabilityId.CURRENT_NOW
            val currentCap =
                when {
                    current == Long.MIN_VALUE || current == 0L -> {
                        unavailable(currentId, T0_UNPRIVILEGED, "unsupported value", FixAction.NONE)
                    }

                    else -> {
                        val detail =
                            mapOf(
                                "sign" to if (current < 0) "negative" else "positive",
                                "magnitude_digits" to abs(current).toString().length.toString(),
                            )
                        // Phones draw well under 20 A; a larger magnitude means another unit or garbage.
                        if (abs(current) < MAX_PLAUSIBLE_CURRENT_UA) {
                            available(currentId, T0_UNPRIVILEGED, detail)
                        } else {
                            cap(currentId, DEGRADED, T0_UNPRIVILEGED, "implausible magnitude", null, detail)
                        }
                    }
                }
            val counterCap =
                if (counter != Long.MIN_VALUE && counter > 0L) {
                    available(CapabilityId.CHARGE_COUNTER, T0_UNPRIVILEGED)
                } else {
                    unavailable(CapabilityId.CHARGE_COUNTER, T0_UNPRIVILEGED, "unsupported value", FixAction.NONE)
                }
            return listOf(currentCap, counterCap)
        }

        /** S16: `SystemHealthManager.getSupportedPowerMonitors` (API 35). */
        private suspend fun powerMonitors(): Capability {
            val id = CapabilityId.POWER_MONITORS
            val shm =
                context.getSystemService(SystemHealthManager::class.java)
                    ?: return unavailable(id, T0_UNPRIVILEGED, "no SystemHealthManager", FixAction.NONE)
            val monitors =
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine<List<PowerMonitor>> { cont ->
                        shm.getSupportedPowerMonitors(Runnable::run) { list -> if (cont.isActive) cont.resume(list) }
                    }
                }
            return when {
                monitors == null -> unavailable(id, T0_UNPRIVILEGED, "no answer", FixAction.NONE)
                monitors.isEmpty() -> unavailable(id, T0_UNPRIVILEGED, "none exposed", FixAction.NONE)
                else -> available(id, T0_UNPRIVILEGED, mapOf("count" to monitors.size.toString(), "types" to monitorTypes(monitors)))
            }
        }

        private fun monitorTypes(monitors: List<PowerMonitor>): String =
            monitors
                .groupingBy { it.type }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value}" }

        private fun promotedNotifications(): Capability {
            val id = CapabilityId.PROMOTED_NOTIFICATIONS
            val nm = context.getSystemService(NotificationManager::class.java)
            val can = runCatching { nm?.canPostPromotedNotifications() }.getOrNull() == true
            return if (can) {
                available(
                    id,
                    T0_UNPRIVILEGED,
                )
            } else {
                unavailable(id, T0_UNPRIVILEGED, "promotion not allowed", FixAction.GRANT_NOTIFICATIONS)
            }
        }

        // --- T1 ------------------------------------------------------------------------------------------

        /**
         * S3. The property ids are @SystemApi (not in the SDK), so they are declared here, verified in
         * core/java/android/os/BatteryManager.java: MANUFACTURING_DATE = 7, FIRST_USAGE_DATE = 8,
         * CHARGING_POLICY = 9 (each `@RequiresPermission(BATTERY_STATS) @SystemApi`), STATE_OF_HEALTH = 10
         * (`@FlaggedApi`, not in the API 37 SDK): android-16.0.0_r4 L454, L469, L481, L489;
         * android-17.0.0_r1 L483, L498, L510, L518. Id 11 (SERIAL_NUMBER) is personal and never read.
         */
        private fun healthProperties(): Capability {
            val id = CapabilityId.HEALTH_PROPERTIES
            if (!access.state.value.grants.batteryStats) {
                return unavailable(
                    id,
                    T1_DURABLE_GRANTS,
                    "BATTERY_STATS not granted",
                    FixAction.RUN_BOOTSTRAP,
                )
            }
            val bm =
                context.getSystemService(BatteryManager::class.java)
                    ?: return unavailable(id, T1_DURABLE_GRANTS, "no BatteryManager", FixAction.NONE)

            fun int(
                property: Int,
                valid: (Int) -> Boolean,
            ) = runCatching { valid(bm.getIntProperty(property)) }.getOrDefault(false)

            fun date(property: Int) = runCatching { bm.getLongProperty(property) > MIN_PLAUSIBLE_EPOCH_S }.getOrDefault(false)
            val valid =
                mapOf(
                    "state_of_health" to int(PROPERTY_STATE_OF_HEALTH) { it in 1..MAX_PLAUSIBLE_SOH },
                    "manufacturing_date" to date(PROPERTY_MANUFACTURING_DATE),
                    "first_usage_date" to date(PROPERTY_FIRST_USAGE_DATE),
                    "charging_policy" to int(PROPERTY_CHARGING_POLICY) { it in 1..MAX_CHARGING_POLICY },
                )
            val ok = valid.count { it.value }
            val detail = mapOf("valid" to valid.filterValues { it }.keys.joinToString(","))
            val invalid = "invalid: " + valid.filterValues { !it }.keys.joinToString(",")
            return when (ok) {
                valid.size -> available(id, T1_DURABLE_GRANTS, detail)
                0 -> cap(id, UNAVAILABLE, T1_DURABLE_GRANTS, invalid, FixAction.NONE, detail)
                else -> cap(id, DEGRADED, T1_DURABLE_GRANTS, invalid, null, detail)
            }
        }

        private fun usageStats(): Capability {
            val id = CapabilityId.USAGE_STATS
            return if (access.state.value.grants.usageStats) {
                available(id, T0_UNPRIVILEGED)
            } else {
                unavailable(id, T0_UNPRIVILEGED, "usage access off", FixAction.GRANT_USAGE_ACCESS)
            }
        }

        /** S17: own uid at T0, every uid with BATTERY_STATS (T1). */
        private fun uidHealthStats(): Capability {
            val id = CapabilityId.UID_HEALTH_STATS
            val shm =
                context.getSystemService(SystemHealthManager::class.java)
                    ?: return unavailable(id, T0_UNPRIVILEGED, "no SystemHealthManager", FixAction.NONE)
            val own = runCatching { shm.takeMyUidSnapshot() }.isSuccess
            val other = runCatching { shm.takeUidSnapshot(Process.SYSTEM_UID) }.isSuccess
            return when {
                other -> available(id, T1_DURABLE_GRANTS)
                own -> cap(id, DEGRADED, T0_UNPRIVILEGED, "own uid only", FixAction.RUN_BOOTSTRAP)
                else -> unavailable(id, T0_UNPRIVILEGED, "snapshot failed", FixAction.NONE)
            }
        }

        // --- dumps (T1 in-app or T2 worker) ----------------------------------------------------------------

        /** S5: the flags this build's `dumpsys batterystats -h` documents. */
        private suspend fun batteryStatsFlags(): Capability {
            val id = CapabilityId.BATTERYSTATS_FLAGS
            val result = access.dumpBatteryStats(BatteryStatsMode.HELP) { drain(it) }
            val drained = result.valueOrNull() ?: return failed(id, result, dumpTier())
            val help = String(drained.head, Charsets.UTF_8)
            val flags = KNOWN_BATTERYSTATS_FLAGS.filter { flag -> flagPattern(flag).containsMatchIn(help) }
            val missing = REQUIRED_BATTERYSTATS_FLAGS - flags.toSet()
            val detail = mapOf("flags" to flags.joinToString(","), "route" to routeOf(result))
            return if (missing.isEmpty()) {
                available(id, tierOf(result), detail)
            } else {
                cap(id, DEGRADED, tierOf(result), "missing ${missing.joinToString(",")}", null, detail)
            }
        }

        private suspend fun batteryStatsDump(
            id: CapabilityId,
            mode: BatteryStatsMode,
        ): Capability {
            val result = access.dumpBatteryStats(mode) { drain(it) }
            val drained = result.valueOrNull() ?: return failed(id, result, dumpTier())
            if (drained.bytes == 0L) return unavailable(id, tierOf(result), "empty output", FixAction.NONE)
            val detail =
                buildMap {
                    put("bytes", drained.bytes.toString())
                    put("route", routeOf(result))
                    if (mode == BatteryStatsMode.PROTO) put("looks_like_proto", looksLikeProto(drained.head).toString())
                }
            return streamed(id, result, drained, detail)
        }

        /**
         * S7 reads the history in checkin form from a cursor (ADR-0014). A cursor past every record
         * makes the probe cheap: the dump is the version line, the tag pool and `NEXT:`, which
         * the incremental read cannot work without.
         */
        private suspend fun historyCursor(): Capability {
            val id = CapabilityId.BATTERYSTATS_HISTORY
            val result = access.dumpBatteryStats(BatteryStatsMode.CHECKIN_HISTORY, historyStart = Long.MAX_VALUE) { drain(it) }
            val drained = result.valueOrNull() ?: return failed(id, result, dumpTier())
            val hasNext = String(drained.head, Charsets.UTF_8).lineSequence().any { it.startsWith(NEXT_LINE) }
            val detail = mapOf("bytes" to drained.bytes.toString(), "route" to routeOf(result), "has_next" to hasNext.toString())
            return when {
                drained.bytes == 0L -> unavailable(id, tierOf(result), "empty output", FixAction.NONE)
                !hasNext -> cap(id, DEGRADED, tierOf(result), "no NEXT line", null, detail)
                else -> streamed(id, result, drained, detail)
            }
        }

        private suspend fun serviceDump(
            id: CapabilityId,
            service: SystemService,
        ): Capability {
            val result = access.dumpService(service) { drain(it) }
            val drained = result.valueOrNull() ?: return failed(id, result, dumpTier())
            return streamed(id, result, drained, mapOf("bytes" to drained.bytes.toString(), "route" to routeOf(result)))
        }

        /** S19: the thermal service dump (T1/T2) plus the public headroom API (T0) as detail. */
        private suspend fun thermal(): Capability {
            val id = CapabilityId.THERMAL_DUMP
            val pm = context.getSystemService(PowerManager::class.java)
            val headroom = runCatching { pm?.getThermalHeadroom(THERMAL_FORECAST_S) }.getOrNull()
            val headroomDetail = "headroom_api" to (headroom != null && !headroom.isNaN()).toString()
            val result = access.dumpService(SystemService.THERMAL) { drain(it) }
            val drained = result.valueOrNull() ?: return failed(id, result, dumpTier()).let { it.copy(detail = it.detail + headroomDetail) }
            return streamed(id, result, drained, mapOf("bytes" to drained.bytes.toString(), "route" to routeOf(result), headroomDetail))
        }

        /** S8: SystemSuspend's own dump when it exists, else only batterystats' kernel wakelock totals. */
        private suspend fun kernelWakelocks(): Capability {
            val id = CapabilityId.KERNEL_WAKELOCKS
            val result = access.dumpService(SystemService.SUSPEND_CONTROL_INTERNAL) { drain(it) }
            if (result is DumpResult.Complete && result.value.bytes > 0) {
                return available(id, tierOf(result), mapOf("source" to "suspend_control_internal"))
            }
            val reason = (result as? DumpResult.Unavailable)?.reason?.name ?: "partial"
            return cap(id, DEGRADED, dumpTier(), "suspend_control_internal: $reason", null, mapOf("source" to "batterystats"))
        }

        // --- root only (T3, ADR-0028) ------------------------------------------------------------------------

        /**
         * S9, S25, S26 and S27. While the server is not root they are not tried at all: SELinux denies
         * shell every one of these paths (system/sepolicy private/genfs_contexts and private/shell.te,
         * android-16.0.0_r4 and android-17.0.0_r1), so touching them would only log denials
         * (ADR-0029 §6). As root, a path a vendor kernel or policy closes is reported per source.
         */
        private suspend fun kernelSources(): List<Capability> {
            val ids = listOf(CapabilityId.WAKEUP_SOURCES, CapabilityId.SUSPEND_STATS, CapabilityId.WAKEUP_REASONS, CapabilityId.KERNEL_LOG)
            val state = access.state.value
            if (!state.workerUsable) return ids.map { unavailable(it, T3_ROOT, "no Shizuku connection", managerFix(state)) }
            if (state.server?.isRoot !=
                true
            ) {
                return ids.map { unavailable(it, T3_ROOT, "needs Shizuku started as root", FixAction.NEEDS_ROOT) }
            }
            val files = access.readKernelFiles(KernelFrames.ALL_SOURCES) { it.readBytes() }
            val frames = files.valueOrNull()?.let(KernelFrames::walk)
            val cost = "read_ms" to ((files as? DumpResult.Complete)?.durationMs?.toString() ?: "-")
            return listOf(
                frames?.let { wakeupSources(it, cost) } ?: failed(CapabilityId.WAKEUP_SOURCES, files, T3_ROOT),
                frames?.let { suspendStats(it, cost) } ?: failed(CapabilityId.SUSPEND_STATS, files, T3_ROOT),
                frames?.let(::wakeupReasons) ?: failed(CapabilityId.WAKEUP_REASONS, files, T3_ROOT),
                kernelLog(),
            )
        }

        private fun wakeupSources(
            frames: KernelFrames,
            cost: Pair<String, String>,
        ): Capability {
            val id = CapabilityId.WAKEUP_SOURCES
            val dir = KernelPaths.WAKEUP_CLASS
            frames.failed[dir]?.let { return closedPath(id, it) }
            val entries = frames.readIn(dir).map { it.substringAfter("$dir/").substringBefore('/') }.toSet()
            val broken =
                frames
                    .failedIn(dir)
                    .keys
                    .map { it.substringAfter("$dir/").substringBefore('/') }
                    .toSet()
            val detail = mapOf("entries" to (entries + broken).size.toString(), "unreadable" to broken.size.toString(), cost)
            return when {
                entries.isEmpty() && broken.isEmpty() -> cap(id, DEGRADED, T3_ROOT, "empty", null, detail)
                entries.isEmpty() -> cap(id, UNAVAILABLE, T3_ROOT, "no source readable", FixAction.NONE, detail)
                broken.isNotEmpty() -> cap(id, DEGRADED, T3_ROOT, "some sources unreadable", null, detail)
                else -> available(id, T3_ROOT, detail)
            }
        }

        private fun suspendStats(
            frames: KernelFrames,
            cost: Pair<String, String>,
        ): Capability {
            val id = CapabilityId.SUSPEND_STATS
            val dir = KernelPaths.SUSPEND_STATS
            frames.failed[dir]?.let { return closedPath(id, it) }
            val names = frames.readIn(dir).map { it.substringAfterLast('/') }
            val detail = mapOf("files" to names.sorted().joinToString(","), cost)
            return when {
                "success" in names && "fail" in names && frames.failedIn(dir).isEmpty() -> available(id, T3_ROOT, detail)
                "success" in names && "fail" in names -> cap(id, DEGRADED, T3_ROOT, "some files unreadable", null, detail)
                else -> cap(id, UNAVAILABLE, T3_ROOT, "no success and fail counters", FixAction.NONE, detail)
            }
        }

        private fun wakeupReasons(frames: KernelFrames): Capability {
            val id = CapabilityId.WAKEUP_REASONS
            val reason = "${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_RESUME_REASON}"
            val time = "${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_SUSPEND_TIME}"
            return when {
                reason in frames.read && time in frames.read -> available(id, T3_ROOT)
                reason in frames.read -> cap(id, DEGRADED, T3_ROOT, "no last_suspend_time", null)
                else -> closedPath(id, frames.failed[reason] ?: -1)
            }
        }

        /** S27: one read from the ring's start; the kept count shows whether the allowlist matches this kernel. */
        private suspend fun kernelLog(): Capability {
            val id = CapabilityId.KERNEL_LOG
            val result = access.readKernelLog(bootKey = 0L, afterSeq = -1L) { String(it.readBytes(), Charsets.US_ASCII) }
            val text = result.valueOrNull() ?: return failed(id, result, T3_ROOT).withFix(FixAction.NONE)
            val lines = text.lines()
            val trailer = lines.lastOrNull { it.startsWith("#end ") }
            val detail =
                mapOf(
                    "kept" to lines.count { it.isNotEmpty() && !it.startsWith("#") }.toString(),
                    "lost" to (trailer?.substringAfter("lost=", "-") ?: "-"),
                    "read_ms" to ((result as? DumpResult.Complete)?.durationMs?.toString() ?: "-"),
                )
            return if (result is DumpResult.Complete && trailer != null) {
                available(id, T3_ROOT, detail)
            } else {
                cap(id, DEGRADED, T3_ROOT, "read cut short", null, detail)
            }
        }

        /** A kernel path that root still cannot read: the kernel lacks it or a policy closes it. */
        private fun closedPath(
            id: CapabilityId,
            errno: Int,
        ): Capability = unavailable(id, T3_ROOT, "closed: errno $errno", FixAction.NONE)

        private fun Capability.withFix(fix: FixAction): Capability = if (state == UNAVAILABLE) copy(fixAction = fix) else this

        // --- worker only (T2/T3) ---------------------------------------------------------------------------

        private suspend fun standbyBuckets(): Capability {
            val id = CapabilityId.STANDBY_BUCKETS
            val result = access.runQuery(PrivilegedQuery.STANDBY_BUCKETS) { drain(it) }
            val drained = result.valueOrNull() ?: return failed(id, result, workerTier())
            val lines = String(drained.head, Charsets.UTF_8).lineSequence().count { it.contains(':') }
            val detail = mapOf("entries" to lines.toString())
            return if (lines > 0) available(id, workerTier(), detail) else cap(id, DEGRADED, workerTier(), "no entries", null, detail)
        }

        /** S22: battery-related key names per namespace (values are never read here). */
        private suspend fun protectionSettings(): Capability {
            val id = CapabilityId.PROTECTION_SETTINGS
            val found = mutableListOf<String>()
            var failure: PrivilegedResult.Failure? = null
            for (ns in SettingsNamespace.entries) {
                when (val r = access.listSettingKeys(ns)) {
                    is PrivilegedResult.Success -> r.value.forEach { found += "${ns.name.lowercase()}:$it" }
                    is PrivilegedResult.Failure -> failure = r
                }
            }
            failure?.takeIf { found.isEmpty() }?.let { return failedSmall(id, it) }
            if (found.isEmpty()) return unavailable(id, workerTier(), "no battery-related keys", FixAction.NONE)
            return available(id, workerTier(), mapOf("keys" to found.joinToString(",")))
        }

        /** S23: which supplies exist and which standard battery attributes the worker can read. */
        private suspend fun powerSupply(): Capability {
            val id = CapabilityId.POWER_SUPPLY_SYSFS
            val supplies =
                when (val r = access.listSysfs(SysfsRoot.POWER_SUPPLY)) {
                    is PrivilegedResult.Success -> r.value
                    is PrivilegedResult.Failure -> return failedSmall(id, r)
                }
            // Bluetooth HID batteries are named after the peripheral's MAC ("hid-<mac>-battery"): masked.
            val suppliesDetail = "supplies" to supplies.joinToString(",") { it.replace(PERIPHERAL_ID, "<id>") }
            val battery =
                supplies.firstOrNull { it == "battery" }
                    ?: return cap(id, DEGRADED, workerTier(), "no battery supply", null, mapOf(suppliesDetail))
            val readable = mutableListOf<String>()
            val denied = mutableListOf<String>()
            for (attr in STANDARD_BATTERY_ATTRIBUTES) {
                when (val r = access.readSysfs(SysfsRoot.POWER_SUPPLY, "$battery/$attr")) {
                    is PrivilegedResult.Success -> readable += attr
                    is PrivilegedResult.Failure -> if (r.reason == FailureReason.ACCESS_DENIED) denied += attr
                }
            }
            val detail = mapOf(suppliesDetail, "readable" to readable.joinToString(","))
            val needsRoot = "needs root: ${denied.joinToString(",")}"
            return when {
                readable.isEmpty() && denied.isNotEmpty() -> cap(id, UNAVAILABLE, T3_ROOT, needsRoot, FixAction.NEEDS_ROOT, detail)
                readable.isEmpty() -> cap(id, UNAVAILABLE, workerTier(), "no standard attributes", FixAction.NONE, detail)
                denied.isEmpty() -> available(id, workerTier(), detail)
                else -> cap(id, DEGRADED, workerTier(), needsRoot, null, detail)
            }
        }

        private fun workerBind(): Capability {
            val id = CapabilityId.WORKER_BIND
            val state = access.state.value
            val diag = access.diagnostics.value
            if (state.workerUsable && diag.lastBindMs != null) {
                val detail =
                    buildMap {
                        put("bind_ms", diag.lastBindMs.toString())
                        diag.workerUid?.let { put("uid", it.toString()) }
                        diag.workerSeLinuxContext?.let { put("selinux", it) }
                    }
                return available(id, workerTier(), detail)
            }
            return unavailable(id, T2_PRIVILEGED_WORKER, state.workerFailure?.name ?: "not bound", managerFix(state))
        }

        // --- helpers -------------------------------------------------------------------------------------

        private class Drained(
            val bytes: Long,
            val head: ByteArray,
        )

        /** Reads the whole stream (so no writer ever meets a closed pipe) and keeps the first bytes. */
        private fun drain(input: InputStream): Drained {
            val head = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (head.size() < HEAD_BYTES) head.write(buffer, 0, minOf(n, HEAD_BYTES - head.size()))
                total += n
            }
            return Drained(total, head.toByteArray())
        }

        private fun streamed(
            id: CapabilityId,
            result: DumpResult<Drained>,
            drained: Drained,
            detail: Map<String, String>,
        ): Capability {
            val partial = (result as? DumpResult.Partial)?.reason?.name
            return if (result is DumpResult.Complete && drained.bytes > 0) {
                available(id, tierOf(result), detail)
            } else {
                cap(id, DEGRADED, tierOf(result), partial ?: "empty output", null, detail)
            }
        }

        private fun flagPattern(flag: String) = Regex("(?<![\\w-])" + Regex.escape(flag) + "(?![\\w-])")

        /** A protobuf message starts with a tag: field number >= 1 and wire type 0, 1, 2 or 5. */
        private fun looksLikeProto(head: ByteArray): Boolean {
            if (head.isEmpty()) return false
            val tag = head[0].toUByte().toInt()
            return tag ushr PROTO_TAG_SHIFT >= 1 && (tag and PROTO_WIRE_TYPE_MASK) in PROTO_WIRE_TYPES
        }

        private fun <R> DumpResult<R>.valueOrNull(): R? =
            when (this) {
                is DumpResult.Complete -> value
                is DumpResult.Partial -> value
                is DumpResult.Unavailable -> null
            }

        private fun routeOf(result: DumpResult<*>): String =
            when (result) {
                is DumpResult.Complete -> result.route.name
                is DumpResult.Partial -> result.route.name
                is DumpResult.Unavailable -> "none"
            }

        /** Dumps work at T1 whenever DUMP and the usage-stats op are granted, whichever route ran. */
        private fun tierOf(result: DumpResult<*>): AccessTier {
            val route = (result as? DumpResult.Complete)?.route ?: (result as? DumpResult.Partial)?.route
            return if (route == Route.IN_APP || dumpGrants()) T1_DURABLE_GRANTS else workerTier()
        }

        private fun dumpTier(): AccessTier = if (dumpGrants()) T1_DURABLE_GRANTS else T2_PRIVILEGED_WORKER

        private fun dumpGrants(): Boolean =
            access.state.value.grants
                .let { it.dump && it.usageStats }

        private fun workerTier(): AccessTier =
            if (access.state.value.server
                    ?.isRoot == true
            ) {
                T3_ROOT
            } else {
                T2_PRIVILEGED_WORKER
            }

        private fun failed(
            id: CapabilityId,
            result: DumpResult<*>,
            tier: AccessTier,
        ): Capability {
            val u = result as? DumpResult.Unavailable
            val reason = u?.reason ?: FailureReason.DUMP_FAILED
            return unavailable(id, tier, listOfNotNull(reason.name, u?.detail).joinToString(": "), fixFor(reason))
        }

        private fun failedSmall(
            id: CapabilityId,
            failure: PrivilegedResult.Failure,
        ): Capability {
            val tier = if (failure.reason == FailureReason.ACCESS_DENIED) T3_ROOT else T2_PRIVILEGED_WORKER
            return unavailable(id, tier, listOfNotNull(failure.reason.name, failure.detail).joinToString(": "), fixFor(failure.reason))
        }

        private fun fixFor(reason: FailureReason): FixAction =
            when (reason) {
                FailureReason.NO_ACCESS -> managerFix(access.state.value)
                FailureReason.ACCESS_DENIED -> FixAction.NEEDS_ROOT
                else -> FixAction.NONE
            }

        private fun managerFix(state: PrivilegeState): FixAction =
            when {
                state.manager == ManagerStatus.NOT_INSTALLED -> FixAction.CONNECT_SHIZUKU
                state.manager != ManagerStatus.RUNNING -> FixAction.START_SHIZUKU
                state.permission != ManagerPermission.GRANTED -> FixAction.GRANT_SHIZUKU_PERMISSION
                !state.grants.durableCore -> FixAction.RUN_BOOTSTRAP
                else -> FixAction.NONE
            }

        private fun available(
            id: CapabilityId,
            tier: AccessTier,
            detail: Map<String, String> = emptyMap(),
        ) = cap(id, AVAILABLE, tier, null, null, detail)

        private fun unavailable(
            id: CapabilityId,
            tier: AccessTier,
            reason: String,
            fix: FixAction,
        ) = cap(id, UNAVAILABLE, tier, reason, fix)

        private fun cap(
            id: CapabilityId,
            state: CapabilityState,
            tier: AccessTier,
            reason: String?,
            fix: FixAction?,
            detail: Map<String, String> = emptyMap(),
        ) = Capability(id, state, tier, reason, fix, System.currentTimeMillis(), config.fingerprint, detail)

        private companion object {
            const val PROPERTY_MANUFACTURING_DATE = 7
            const val PROPERTY_FIRST_USAGE_DATE = 8
            const val PROPERTY_CHARGING_POLICY = 9
            const val PROPERTY_STATE_OF_HEALTH = 10

            /** CHARGING_POLICY_DEFAULT (1) to CHARGING_POLICY_FORCE_FULL_CHARGE (5), android-17.0.0_r1 L322-353. */
            const val MAX_CHARGING_POLICY = 5
            const val MAX_PLAUSIBLE_SOH = 150

            /** 2010-01-01 in epoch seconds; earlier dates are placeholders. */
            const val MIN_PLAUSIBLE_EPOCH_S = 1_262_304_000L
            const val MAX_PLAUSIBLE_CURRENT_UA = 20_000_000L

            /** A MAC address or a long hex run (serial-like) inside a power-supply name. */
            val PERIPHERAL_ID = Regex("(?i)([0-9a-f]{2}[:_-]){5}[0-9a-f]{2}|[0-9a-f]{12,}")

            /** Protobuf wire format: tag = (field_number << 3) | wire_type; valid types 0, 1, 2, 5. */
            const val PROTO_TAG_SHIFT = 3
            const val PROTO_WIRE_TYPE_MASK = 0x07
            val PROTO_WIRE_TYPES = setOf(0, 1, 2, 5)
            const val CALLBACK_TIMEOUT_MS = 5_000L
            const val THERMAL_FORECAST_S = 10
            const val BUFFER = 64 * 1024
            const val HEAD_BYTES = 256 * 1024

            /** The history dump's last line (BatteryStats.dumpHistory, android-16.0.0_r4 L7552-7554, android-17.0.0_r1 L7584-7586). */
            const val NEXT_LINE = "NEXT: "

            val KNOWN_BATTERYSTATS_FLAGS =
                listOf(
                    "--checkin",
                    "-c",
                    "--proto",
                    "--history",
                    "--history-start",
                    "--charged",
                    "--daily",
                    "--usage",
                    "--cpu",
                    "--power-profile",
                    "--settings",
                )
            val REQUIRED_BATTERYSTATS_FLAGS = listOf("--proto", "-c", "--history-start")

            /** Standard power_supply class attributes (kernel Documentation/ABI/testing/sysfs-class-power). */
            val STANDARD_BATTERY_ATTRIBUTES =
                listOf(
                    "capacity",
                    "status",
                    "current_now",
                    "voltage_now",
                    "charge_counter",
                    "charge_full",
                    "charge_full_design",
                    "cycle_count",
                    "temp",
                )
        }
    }
