// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.ServerInfo
import io.github.akane599.coulomb.core.model.WorkerFailure
import io.github.akane599.coulomb.core.model.WorkerStatus
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.BootstrapItem
import io.github.akane599.coulomb.core.privileged.BootstrapReport
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.DurablePermission
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.GrantKind
import io.github.akane599.coulomb.core.privileged.GrantOutcome
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import io.github.akane599.coulomb.core.privileged.PatternCheck
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.PrivilegeDiagnostics
import io.github.akane599.coulomb.core.privileged.PrivilegeDrills
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedQuery
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.Route
import io.github.akane599.coulomb.core.privileged.RoutePreference
import io.github.akane599.coulomb.core.privileged.SettingsNamespace
import io.github.akane599.coulomb.core.privileged.SysfsRoot
import io.github.akane599.coulomb.core.privileged.SystemService
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Shizuku and Android seams of [PrivilegeController]; unit tests pass fakes. It exists to group
 * the seams into one parameter, so its own list is long by design.
 */
@Suppress("LongParameterList")
internal class PrivilegePlatform(
    val api: ShizukuApi,
    val grantSource: GrantSource,
    val ledger: PrivilegeLedger,
    val binderGrants: BinderGrants,
    val inAppDumper: InAppDumper,
    val pipes: PipeFactory,
    val foreground: ForegroundSignal,
)

/**
 * The privileged core (SPEC 4): turns Shizuku's listeners into one [PrivilegeState], owns the
 * worker connection, runs the bootstrap and routes every request worker -> T1 -> unavailable.
 *
 * Shizuku events are queued and handled one at a time on [scope], so state transitions never race;
 * Shizuku calls that might block (requestPermission, bindUserService) run on [io] in their own job.
 */
internal class PrivilegeController(
    private val config: PrivilegeConfig,
    platform: PrivilegePlatform,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    /** [clock] time at which this process started; null when unknown (no boot grace). */
    private val processStartMs: Long? = null,
) : PrivilegedAccess,
    PrivilegeDrills {
    private val api = platform.api
    private val grantSource = platform.grantSource
    private val ledger = platform.ledger
    private val binderGrants = platform.binderGrants
    private val inAppDumper = platform.inAppDumper
    private val pipes = platform.pipes
    private val foreground = platform.foreground
    private val connector =
        WorkerConnector(
            api,
            scope,
            io,
            config.workerVersion,
            clock,
            bindKnownGood = { managerIdentity()?.let { it == boundManager } == true },
            onBound = ::rememberBoundManager,
        )
    private val transfer = StreamTransfer(io, clock)

    private data class Core(
        val manager: ManagerStatus = ManagerStatus.UNKNOWN,
        val server: ServerInfo? = null,
        val permission: ManagerPermission = ManagerPermission.UNKNOWN,
        val permissionHanging: Boolean = false,
        val grants: GrantStatus = GrantStatus(),
        val grantsVerifiedBefore: Boolean = false,
        val fingerprintChanged: Boolean = false,
        val connection: Long = 0L,
        /** The binder died less than [STOPPED_NOTICE_MS] ago: the tier drops, the issue waits. */
        val quietStop: Boolean = false,
    ) {
        val authorized: Boolean get() = manager == ManagerStatus.RUNNING && permission == ManagerPermission.GRANTED
    }

    private val core = MutableStateFlow(Core())
    private val binderGrantPathWorked = MutableStateFlow<Boolean?>(null)
    private val bootstrapReport = MutableStateFlow<BootstrapReport?>(null)

    override val state: StateFlow<PrivilegeState> =
        combine(core, connector.state) { c, w -> c.toState(w) }
            .stateIn(scope, SharingStarted.Eagerly, PrivilegeState())

    override val diagnostics: StateFlow<PrivilegeDiagnostics> =
        combine(core, connector.state, binderGrantPathWorked) { c, w, b ->
            PrivilegeDiagnostics(
                lastBindMs = w.lastBindMs,
                workerUid = w.workerUid,
                workerSeLinuxContext = w.workerSeLinuxContext,
                fingerprintChanged = c.fingerprintChanged,
                grantsVerifiedBefore = c.grantsVerifiedBefore,
                permissionRequestHanging = c.permissionHanging,
                binderGrantPathWorked = b,
            )
        }.stateIn(scope, SharingStarted.Eagerly, PrivilegeDiagnostics())

    override val lastBootstrap: StateFlow<BootstrapReport?> = bootstrapReport.asStateFlow()

    // --- Shizuku events ----------------------------------------------------------------------------

    private sealed interface Event {
        /** [attempt] > 0 is a retry of a binder whose server did not answer yet, from [epoch]. */
        data class BinderReceived(
            val attempt: Int = 0,
            val epoch: Int? = null,
        ) : Event

        data object BinderDead : Event

        data class StoppedCheck(
            val epoch: Int,
        ) : Event

        data class PermissionResult(
            val requestCode: Int,
            val granted: Boolean,
        ) : Event

        data object NoBinderCheck : Event

        data object RequestPermission : Event

        data class PermissionHangCheck(
            val requestId: Int,
        ) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val bootstrapMutex = Mutex()

    // Confined to the event loop.
    private var permissionRequestId = 0
    private var permissionRequestedAt = 0L
    private var autoBootstrapStarted = false

    /** One higher for each binder received or lost, so a delayed retry or notice can tell it is stale. */
    private var binderEpoch = 0
    private var connection = 0L

    /** [LedgerRecord.boundManager], cached: read at start, written after a bind. */
    @Volatile private var boundManager: String? = null

    private val listener =
        object : ShizukuEvents {
            override fun onBinderReceived() {
                events.trySend(Event.BinderReceived())
            }

            override fun onBinderDead() {
                events.trySend(Event.BinderDead)
            }

            override fun onPermissionResult(
                requestCode: Int,
                granted: Boolean,
            ) {
                events.trySend(Event.PermissionResult(requestCode, granted))
            }
        }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            verifyGrantsAtStart()
            for (event in events) handle(event)
        }
        // Sticky: delivers BinderReceived at once when a binder is already there.
        api.setListener(listener)
        val noBinderDelay = noBinderDelayMs()
        scope.launch {
            delay(noBinderDelay)
            events.send(Event.NoBinderCheck)
        }
    }

    /**
     * SPEC 4.5 says "no binder within 2 s", but a manager started at boot may need a minute or two.
     * When this process started soon after boot, the check waits until [BOOT_GRACE_MS] after process
     * start; until then the manager stays UNKNOWN, which shows nothing (ADR-0029 §6). The sticky
     * listener still delivers a binder the moment it arrives.
     */
    private fun noBinderDelayMs(): Long {
        val start = processStartMs ?: return NO_BINDER_MS
        if (start >= BOOT_WINDOW_MS) return NO_BINDER_MS
        return maxOf(NO_BINDER_MS, BOOT_GRACE_MS - (clock() - start))
    }

    override fun requestManagerPermission() {
        events.trySend(Event.RequestPermission)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.BinderReceived -> {
                onBinderEvent(event)
            }

            Event.BinderDead -> {
                binderEpoch += 1
                onBinderDead()
            }

            is Event.StoppedCheck -> {
                if (event.epoch == binderEpoch) core.update { it.copy(quietStop = false) }
            }

            is Event.PermissionResult -> {
                onPermissionResult(event.requestCode, event.granted)
            }

            Event.NoBinderCheck -> {
                onNoBinderCheck()
            }

            Event.RequestPermission -> {
                onRequestPermission()
            }

            is Event.PermissionHangCheck -> {
                // A dialog on screen pauses Coulomb's activity: the user is deciding, nothing hangs.
                val dialogShown = foreground.lastPausedAt()?.let { it >= permissionRequestedAt } == true
                if (event.requestId == permissionRequestId &&
                    core.value.permission == ManagerPermission.PENDING &&
                    !dialogShown
                ) {
                    core.update { it.copy(permissionHanging = true) }
                }
            }
        }
    }

    /** A binder from the listener starts a new epoch; a retry counts only within its own. */
    private suspend fun onBinderEvent(event: Event.BinderReceived) {
        if (event.epoch == null) binderEpoch += 1
        if (event.epoch == null || event.epoch == binderEpoch) onBinderReceived(event.attempt)
    }

    private suspend fun onBinderReceived(attempt: Int) {
        val details = withContext(io) { if (api.pingBinder()) api.serverDetails() else null }
        if (details == null) {
            // Right after boot or a restart the binder can arrive before its server answers. Try
            // again at 1, 2, 4 and 8 s instead of waiting for a binder that already came.
            if (attempt < BINDER_RETRIES) {
                val epoch = binderEpoch
                scope.launch {
                    delay(BINDER_RETRY_MS shl attempt)
                    events.send(Event.BinderReceived(attempt + 1, epoch))
                }
            }
            return
        }
        val sui = api.isSui()
        val manager = if (sui) null else withContext(io) { api.managerPackage() }
        // SPEC 4.5 auto-resume: a new binder may be a different (working) server, possibly in the
        // other mode; its uid is read above, on every binder (ADR-0028 §3).
        connection += 1
        connector.reset(connection)
        if (details.apiVersion < MIN_SERVER_API) connector.markUnavailable(WorkerFailure.SERVER_TOO_OLD)
        val granted = withContext(io) { api.checkSelfPermission() }
        val previous = core.value.permission
        val permission =
            when {
                granted -> ManagerPermission.GRANTED
                previous == ManagerPermission.PENDING -> ManagerPermission.PENDING
                withContext(io) { api.permissionDeniedPermanently() } -> ManagerPermission.DENIED
                else -> ManagerPermission.NOT_REQUESTED
            }
        core.update {
            it.copy(
                manager = ManagerStatus.RUNNING,
                server =
                    ServerInfo(
                        apiVersion = details.apiVersion,
                        uid = details.uid,
                        seLinuxContext = details.seLinuxContext,
                        isSui = sui,
                        managerPackage = manager?.packageName,
                        managerVersionName = manager?.versionName,
                    ),
                permission = permission,
                permissionHanging = permission == ManagerPermission.PENDING && it.permissionHanging,
                connection = connection,
                quietStop = false,
            )
        }
        if (permission == ManagerPermission.GRANTED) maybeAutoBootstrap()
    }

    private fun onBinderDead() {
        connector.onManagerDead()
        permissionRequestId += 1
        core.update {
            it.copy(
                manager = ManagerStatus.NOT_RUNNING,
                server = null,
                permission = ManagerPermission.UNKNOWN,
                permissionHanging = false,
                quietStop = true,
            )
        }
        // A restart or a mode switch brings a new binder within seconds; only a longer silence is
        // "Shizuku is stopped" (ADR-0029 §6).
        val epoch = binderEpoch
        scope.launch {
            delay(STOPPED_NOTICE_MS)
            events.send(Event.StoppedCheck(epoch))
        }
    }

    /** The manager's package and version, or Sui with its API level; null without a server. */
    private fun managerIdentity(): String? =
        core.value.server?.let { s -> if (s.isSui) "sui@${s.apiVersion}" else s.managerPackage?.let { "$it@${s.managerVersionName}" } }

    private fun rememberBoundManager() {
        val identity = managerIdentity() ?: return
        if (identity == boundManager) return
        boundManager = identity
        scope.launch(io) { runCatching { ledger.write(ledger.read().copy(boundManager = identity)) } }
    }

    private suspend fun onNoBinderCheck() {
        if (core.value.manager != ManagerStatus.UNKNOWN) return
        val installed = withContext(io) { api.managerPackage() } != null
        core.update {
            if (it.manager ==
                ManagerStatus.UNKNOWN
            ) {
                it.copy(manager = if (installed) ManagerStatus.NOT_RUNNING else ManagerStatus.NOT_INSTALLED)
            } else {
                it
            }
        }
    }

    private suspend fun onRequestPermission() {
        val c = core.value
        if (c.manager != ManagerStatus.RUNNING || c.permission == ManagerPermission.GRANTED) return
        if (withContext(io) { api.permissionDeniedPermanently() }) {
            core.update { it.copy(permission = ManagerPermission.DENIED, permissionHanging = false) }
            return
        }
        val id = ++permissionRequestId
        permissionRequestedAt = clock()
        core.update { it.copy(permission = ManagerPermission.PENDING, permissionHanging = false) }
        // On Android 17 with official Shizuku 13.6.0 the request may never answer (SPEC 4.5).
        scope.launch(io) { runCatching { api.requestPermission(PERMISSION_REQUEST_CODE) } }
        scope.launch {
            delay(PERMISSION_HANG_MS)
            events.send(Event.PermissionHangCheck(id))
        }
    }

    private fun onPermissionResult(
        requestCode: Int,
        granted: Boolean,
    ) {
        if (requestCode != PERMISSION_REQUEST_CODE) return
        permissionRequestId += 1
        core.update {
            it.copy(
                permission = if (granted) ManagerPermission.GRANTED else ManagerPermission.DENIED,
                permissionHanging = false,
            )
        }
        if (granted) maybeAutoBootstrap()
    }

    /** SPEC 4.4: only on the first authorization; a later loss is repaired by one tap (4.5). */
    private fun maybeAutoBootstrap() {
        val c = core.value
        if (autoBootstrapStarted || c.grantsVerifiedBefore || c.grants.durableCore) return
        autoBootstrapStarted = true
        scope.launch { bootstrap() }
    }

    // --- grants ------------------------------------------------------------------------------------

    private suspend fun verifyGrantsAtStart() {
        val record = withContext(io) { ledger.read() }
        boundManager = record.boundManager
        val grants = withContext(io) { grantSource.read() }
        val changed = record.verifiedFingerprint != null && record.verifiedFingerprint != config.fingerprint
        applyGrants(grants, record, fingerprintChanged = changed)
    }

    private suspend fun applyGrants(
        grants: GrantStatus,
        known: LedgerRecord? = null,
        fingerprintChanged: Boolean? = null,
    ) {
        val record = known ?: withContext(io) { ledger.read() }
        if (grants.durableCore && (!record.coreGrantsVerified || record.verifiedFingerprint != config.fingerprint)) {
            withContext(
                io,
            ) { runCatching { ledger.write(record.copy(coreGrantsVerified = true, verifiedFingerprint = config.fingerprint)) } }
        }
        core.update {
            it.copy(
                grants = grants,
                grantsVerifiedBefore = record.coreGrantsVerified || grants.durableCore,
                fingerprintChanged = fingerprintChanged ?: it.fingerprintChanged,
            )
        }
    }

    override suspend fun refreshGrants(): GrantStatus {
        val grants = withContext(io) { grantSource.read() }
        applyGrants(grants)
        return grants
    }

    override fun adbBootstrapCommands(includeWriteSecureSettings: Boolean): List<String> =
        bootstrapKinds(includeWriteSecureSettings).map(::adbCommand)

    private fun adbCommand(kind: GrantKind): String =
        when (val p = kind.durable) {
            null -> "adb shell appops set ${config.packageName} GET_USAGE_STATS allow"
            else -> "adb shell pm grant ${config.packageName} ${p.permission}"
        }

    override suspend fun bootstrap(includeWriteSecureSettings: Boolean): BootstrapReport =
        bootstrapMutex.withLock {
            val items = bootstrapKinds(includeWriteSecureSettings).map { bootstrapOne(it) }
            refreshGrants()
            val missing = items.filter { it.outcome != GrantOutcome.GRANTED && it.outcome != GrantOutcome.ALREADY_GRANTED }
            BootstrapReport(items, missing.map { adbCommand(it.grant) }).also { bootstrapReport.value = it }
        }

    private suspend fun bootstrapOne(kind: GrantKind): BootstrapItem {
        if (hasGrant(kind)) return BootstrapItem(kind, GrantOutcome.ALREADY_GRANTED, null)
        if (!core.value.authorized) return BootstrapItem(kind, GrantOutcome.NO_PRIVILEGED_PATH, null)
        val workerStatus = grantViaWorker(kind)
        if (workerStatus == WorkerStatusCode.OK && hasGrant(kind)) return BootstrapItem(kind, GrantOutcome.GRANTED, Route.WORKER)
        // SPEC 4.3 fallback: the worker did not bind (or did not manage it); try the Shizuku binder.
        val viaBinder = grantViaBinder(kind)
        val outcome =
            when {
                viaBinder == BinderGrantResult.OK -> if (hasGrant(kind)) GrantOutcome.GRANTED else GrantOutcome.NOT_VERIFIED
                workerStatus == WorkerStatusCode.OK -> GrantOutcome.NOT_VERIFIED
                workerStatus != null || viaBinder == BinderGrantResult.FAILED -> GrantOutcome.COMMAND_FAILED
                else -> GrantOutcome.NO_PRIVILEGED_PATH
            }
        val route =
            when {
                viaBinder == BinderGrantResult.OK -> Route.SHIZUKU_BINDER
                workerStatus != null -> Route.WORKER
                else -> null
            }
        return BootstrapItem(kind, outcome, route)
    }

    private suspend fun hasGrant(kind: GrantKind): Boolean = kind.isIn(withContext(io) { grantSource.read() })

    /** The worker's status for the grant call, or null when no worker answered. */
    private suspend fun grantViaWorker(kind: GrantKind): Int? {
        if (!workerUsableNow()) return null
        val call = connector.call { w -> kind.durable?.let { w.grantPermission(it.id) } ?: w.allowUsageStatsAppOp() }
        return (call as? WorkerCall.Ok)?.value
    }

    private suspend fun grantViaBinder(kind: GrantKind): BinderGrantResult {
        val result =
            withContext(io) {
                kind.durable?.let { binderGrants.grant(it, config.packageName, config.uid / PER_USER_RANGE) }
                    ?: binderGrants.allowUsageStats(config.packageName, config.uid)
            }
        if (result != BinderGrantResult.UNAVAILABLE) binderGrantPathWorked.value = result == BinderGrantResult.OK
        return result
    }

    // --- dumps -------------------------------------------------------------------------------------

    override suspend fun <R> dumpBatteryStats(
        mode: BatteryStatsMode,
        historyStart: Long,
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        route: RoutePreference,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R> =
        dump(
            viaWorker = { w, sink -> w.dumpBatteryStats(mode.id, historyStart, sink) },
            inApp =
                InAppTarget(BATTERYSTATS, mode.args(historyStart)) {
                    it.canDumpBatteryStats &&
                        (!mode.listsAllUsers || it.interactAcrossUsers)
                },
            timeoutMs = timeoutMs,
            isComplete = isComplete,
            route = route,
            consume = consume,
        )

    override suspend fun <R> dumpService(
        service: SystemService,
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        route: RoutePreference,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R> =
        dump(
            viaWorker = { w, sink -> w.dumpService(service.id, sink) },
            // A service apps cannot look up needs the worker; without it the result is NO_ACCESS.
            inApp = InAppTarget(service.binderName, emptyArray()) { it.dump }.takeIf { service.inApp },
            timeoutMs = timeoutMs,
            isComplete = isComplete,
            route = route,
            consume = consume,
        )

    override suspend fun <R> runQuery(
        query: PrivilegedQuery,
        packageName: String?,
        timeoutMs: Long,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R> =
        dump(
            viaWorker = { w, sink -> w.runQuery(query.id, packageName, sink) },
            inApp = null,
            timeoutMs = timeoutMs,
            isComplete = { true },
            consume = consume,
        )

    override suspend fun <R> readKernelFiles(
        sources: Int,
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R> = kernel(timeoutMs, isComplete, consume) { w, sink -> w.readKernelFiles(sources, sink) }

    override suspend fun <R> readKernelLog(
        bootKey: Long,
        afterSeq: Long,
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R> = kernel(timeoutMs, isComplete, consume) { w, sink -> w.readKernelLog(bootKey, afterSeq, sink) }

    /**
     * The root kernel reads (ADR-0028, ADR-0029). Not tried unless the live server runs as root, so
     * shell mode costs no bind; the result's origin still says which uid served it.
     */
    private suspend fun <R> kernel(
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        consume: suspend (InputStream) -> R,
        viaWorker: (ICoulombPrivileged, android.os.ParcelFileDescriptor) -> Int,
    ): DumpResult<R> {
        if (core.value.server?.isRoot != true) return DumpResult.Unavailable(FailureReason.ACCESS_DENIED, "not root")
        return dump(viaWorker = viaWorker, inApp = null, timeoutMs = timeoutMs, isComplete = isComplete, consume = consume)
    }

    /** A dump Coulomb can also run in its own process (T1) when [allowedBy] the current grants. */
    private class InAppTarget(
        val binderName: String,
        val args: Array<String>,
        val allowedBy: (GrantStatus) -> Boolean,
    )

    private suspend fun <R> dump(
        viaWorker: (ICoulombPrivileged, android.os.ParcelFileDescriptor) -> Int,
        inApp: InAppTarget?,
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        consume: suspend (InputStream) -> R,
        route: RoutePreference = RoutePreference.WORKER_FIRST,
    ): DumpResult<R> {
        val sniffing: suspend (InputStream) -> Sniffed<R> = { raw ->
            val input = BufferedInputStream(raw, SNIFF_BUFFER)
            DumpDenial.check(input)?.let { Sniffed.Denied(it) } ?: Sniffed.Value(consume(input))
        }
        // In-app first: a result that is not Unavailable is final; otherwise the worker may still
        // serve it, and the in-app refusal is kept for when it cannot.
        val inAppFirst = if (route == RoutePreference.IN_APP_FIRST) inApp?.let { dumpInApp(it, timeoutMs, isComplete, sniffing) } else null
        if (inAppFirst != null && inAppFirst !is DumpResult.Unavailable) return inAppFirst
        // Null when the worker is unavailable (bind timeout, version mismatch) or failed before streaming.
        val fromWorker =
            if (workerUsableNow()) {
                val call =
                    connector.call { w ->
                        transfer.run(pipes.create(), timeoutMs, { sink -> viaWorker(w, sink) }, WorkerStatusCode.OK, sniffing)
                    }
                (call as? WorkerCall.Ok)?.let { it.value.toSniffedResult(Route.WORKER, isComplete, it.origin) }
            } else {
                null
            }
        return fromWorker
            ?: inApp?.takeIf { route == RoutePreference.WORKER_FIRST }?.let { dumpInApp(it, timeoutMs, isComplete, sniffing) }
            ?: inAppFirst
            ?: DumpResult.Unavailable(FailureReason.NO_ACCESS, noAccessDetail())
    }

    /** T1 (SPEC 4.1). Null when the current grants cannot pass the service's own checks. */
    private suspend fun <R> dumpInApp(
        target: InAppTarget,
        timeoutMs: Long,
        isComplete: (R) -> Boolean,
        sniffing: suspend (InputStream) -> Sniffed<R>,
    ): DumpResult<R>? {
        if (!target.allowedBy(withContext(io) { grantSource.read() })) return null
        val result =
            transfer.run(
                pipes.create(),
                timeoutMs,
                { sink -> inAppDumper.dump(target.binderName, target.args, sink) },
                WorkerStatusCode.OK,
                sniffing,
            )
        return result.toSniffedResult(Route.IN_APP, isComplete)
    }

    // --- small calls -------------------------------------------------------------------------------

    override suspend fun getSetting(
        namespace: SettingsNamespace,
        key: String,
    ): PrivilegedResult<String?> = small { w, st -> w.getSetting(namespace.id, key, st) }

    override suspend fun listSettingKeys(namespace: SettingsNamespace): PrivilegedResult<List<String>> =
        small { w, st -> w.listSettingKeys(namespace.id, st).orEmpty().toList() }

    override suspend fun readSysfs(
        root: SysfsRoot,
        relativePath: String,
    ): PrivilegedResult<ByteArray> =
        small { w, st ->
            w.readSysfs(root.id, relativePath, st)
                ?: ByteArray(0)
        }

    override suspend fun listSysfs(
        root: SysfsRoot,
        relativeDir: String,
    ): PrivilegedResult<List<String>> = small { w, st -> w.listSysfs(root.id, relativeDir, st).orEmpty().toList() }

    /** A small worker call that reports its WorkerStatusCode in an `out int[1]`. */
    private suspend fun <T> small(block: (ICoulombPrivileged, IntArray) -> T): PrivilegedResult<T> {
        if (!workerUsableNow()) return PrivilegedResult.Failure(FailureReason.NO_ACCESS, noAccessDetail())
        val call =
            connector.call { w ->
                val status = intArrayOf(NO_STATUS)
                block(w, status) to status[0]
            }
        return when (call) {
            is WorkerCall.Ok -> {
                val (value, status) = call.value
                if (status == WorkerStatusCode.OK) {
                    PrivilegedResult.Success(value, Route.WORKER, call.origin)
                } else {
                    PrivilegedResult.Failure(failureFor(status), "status $status")
                }
            }

            is WorkerCall.Unavailable -> {
                PrivilegedResult.Failure(FailureReason.NO_ACCESS, call.failure?.name)
            }

            is WorkerCall.Died -> {
                PrivilegedResult.Failure(FailureReason.WORKER_DIED, call.error.toString())
            }

            is WorkerCall.Failed -> {
                PrivilegedResult.Failure(failureFor(call.error), call.error.message)
            }
        }
    }

    override fun releaseWorker() = connector.release(remove = true)

    // --- drills (debug builds only) -----------------------------------------------------------------

    override suspend fun streamTestPattern(
        bytes: Long,
        truncateAt: Long,
        chunkDelayMs: Int,
        timeoutMs: Long,
    ): DumpResult<PatternCheck> {
        if (!config.debuggable) return DumpResult.Unavailable(FailureReason.NOT_PERMITTED, "release build")
        if (!workerUsableNow()) return DumpResult.Unavailable(FailureReason.NO_ACCESS, noAccessDetail())
        val call =
            connector.call { w ->
                transfer.run(
                    pipes.create(),
                    timeoutMs,
                    { sink -> w.streamTestPattern(bytes, truncateAt, chunkDelayMs, sink) },
                    WorkerStatusCode.OK,
                ) {
                    verifyPattern(it, bytes)
                }
            }
        return when (call) {
            is WorkerCall.Ok -> call.value.toDumpResult(Route.WORKER, { it.complete }, call.origin)
            is WorkerCall.Unavailable -> DumpResult.Unavailable(FailureReason.NO_ACCESS, call.failure?.name)
            is WorkerCall.Died -> DumpResult.Unavailable(FailureReason.WORKER_DIED, call.error.toString())
            is WorkerCall.Failed -> DumpResult.Unavailable(failureFor(call.error), call.error.message)
        }
    }

    override suspend fun simulateFingerprintChange() {
        if (!config.debuggable) return
        withContext(io) { ledger.write(ledger.read().copy(verifiedFingerprint = SIMULATED_OLD_FINGERPRINT)) }
        verifyGrantsAtStart()
    }

    override fun setSimulatedBindHang(enabled: Boolean) {
        if (config.debuggable) connector.simulateBindHang = enabled
    }

    override fun resetWorker() {
        if (config.debuggable) connector.reset()
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun workerUsableNow(): Boolean = core.value.authorized && connector.state.value.status != WorkerStatus.UNAVAILABLE

    private fun noAccessDetail(): String {
        val c = core.value
        val w = connector.state.value
        return "manager=${c.manager} permission=${c.permission} worker=${w.status}${w.failure?.let { " ($it)" }.orEmpty()}"
    }

    private fun Core.toState(w: WorkerConnectionState): PrivilegeState {
        val failure = w.failure.takeIf { w.status == WorkerStatus.UNAVAILABLE }
        val issueManager = if (quietStop && manager == ManagerStatus.NOT_RUNNING) ManagerStatus.UNKNOWN else manager
        return PrivilegeState(
            manager = manager,
            server = server,
            permission = permission,
            worker = w.status,
            workerFailure = failure,
            grants = grants,
            issue = PrivilegeIssues.derive(issueManager, permission, permissionHanging, failure, grants, grantsVerifiedBefore),
            connection = connection,
        )
    }

    internal companion object {
        /** SPEC 4.5: "No binder within 2 s". */
        const val NO_BINDER_MS = 2_000L

        /** A process that started this soon after boot gets the boot grace (ADR-0029 §6). */
        const val BOOT_WINDOW_MS = 10 * 60_000L

        /** The boot grace, counted from process start. */
        const val BOOT_GRACE_MS = 180_000L

        /** "Shizuku is stopped" shows only after this long without a new binder. */
        const val STOPPED_NOTICE_MS = 8_000L
        const val BINDER_RETRY_MS = 1_000L
        const val BINDER_RETRIES = 4

        /** SPEC 4.5: "No callback within 5 s". */
        const val PERMISSION_HANG_MS = 5_000L

        /** The `(Context)` UserService constructor exists from Shizuku API 13 (see CoulombPrivilegedService). */
        const val MIN_SERVER_API = 13
        const val PERMISSION_REQUEST_CODE = 0x4350
        const val PER_USER_RANGE = 100_000

        /** Status left in the out array when the worker never wrote one (an older worker build). */
        const val NO_STATUS = -1
        const val BATTERYSTATS = "batterystats"
        const val SIMULATED_OLD_FINGERPRINT = "coulomb/drill/simulated-previous-build"
        private const val SNIFF_BUFFER = 64 * 1024

        fun bootstrapKinds(includeWriteSecureSettings: Boolean): List<GrantKind> =
            buildList {
                add(GrantKind.BATTERY_STATS)
                add(GrantKind.DUMP)
                add(GrantKind.PACKAGE_USAGE_STATS)
                add(GrantKind.USAGE_STATS)
                add(GrantKind.INTERACT_ACROSS_USERS)
                if (includeWriteSecureSettings) add(GrantKind.WRITE_SECURE_SETTINGS)
            }

        fun failureFor(status: Int): FailureReason =
            when (status) {
                WorkerStatusCode.SERVICE_NOT_FOUND -> FailureReason.SERVICE_NOT_FOUND
                WorkerStatusCode.DUMP_FAILED -> FailureReason.DUMP_FAILED
                WorkerStatusCode.COMMAND_FAILED -> FailureReason.COMMAND_FAILED
                WorkerStatusCode.TIMEOUT -> FailureReason.TIMEOUT
                WorkerStatusCode.ACCESS_DENIED -> FailureReason.ACCESS_DENIED
                WorkerStatusCode.INVALID_ARGUMENT -> FailureReason.INVALID_ARGUMENT
                WorkerStatusCode.NOT_PERMITTED -> FailureReason.NOT_PERMITTED
                WorkerStatusCode.NOT_FOUND -> FailureReason.NOT_FOUND
                else -> FailureReason.WORKER_ERROR
            }

        fun failureFor(error: Throwable): FailureReason =
            when (error) {
                is IllegalArgumentException -> FailureReason.INVALID_ARGUMENT
                is SecurityException -> FailureReason.NOT_PERMITTED
                else -> FailureReason.WORKER_ERROR
            }
    }
}

private val GrantKind.durable: DurablePermission?
    get() =
        when (this) {
            GrantKind.BATTERY_STATS -> DurablePermission.BATTERY_STATS
            GrantKind.DUMP -> DurablePermission.DUMP
            GrantKind.WRITE_SECURE_SETTINGS -> DurablePermission.WRITE_SECURE_SETTINGS
            GrantKind.PACKAGE_USAGE_STATS -> DurablePermission.PACKAGE_USAGE_STATS
            GrantKind.INTERACT_ACROSS_USERS -> DurablePermission.INTERACT_ACROSS_USERS
            GrantKind.USAGE_STATS -> null
        }

private fun GrantKind.isIn(g: GrantStatus): Boolean =
    when (this) {
        GrantKind.BATTERY_STATS -> g.batteryStats
        GrantKind.DUMP -> g.dump
        GrantKind.USAGE_STATS -> g.usageStats
        GrantKind.PACKAGE_USAGE_STATS -> g.usageStatsPermission
        GrantKind.INTERACT_ACROSS_USERS -> g.interactAcrossUsers
        GrantKind.WRITE_SECURE_SETTINGS -> g.writeSecureSettings
    }
