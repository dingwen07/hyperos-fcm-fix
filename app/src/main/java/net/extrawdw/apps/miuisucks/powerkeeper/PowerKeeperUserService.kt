package net.extrawdw.apps.miuisucks.powerkeeper

import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class PowerKeeperUserService : IPrivilegedService.Stub {
    constructor() : super()

    private val fcmExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fcm-protection").apply { isDaemon = true }
    }
    private val systemCommands = SystemServiceCommandRunner()
    private val fcmRepairLock = Any()
    private val aurogonRepairLock = Any()
    private var autostartSwitchOpAvailable: Boolean? = null
    @Volatile
    private var fcmPolling: ScheduledFuture<*>? = null
    @Volatile
    private var fcmPollingIntervalMillis = MilletPollingInterval.DEFAULT_MILLIS
    @Volatile
    private var fcmReconnectEnabled = true
    private var cachedGmsUid: Int? = null
    private val serviceLogLock = Any()
    private val serviceLogTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val pendingServiceLogs = ArrayDeque<String>()
    private var pendingServiceLogChars = 0
    private var droppedServiceLogLines = 0
    private var serviceLogWriter: BufferedWriter? = null

    init {
        serviceLog('I', "Service", "created uid=${Process.myUid()} pid=${Process.myPid()}")
    }

    override fun enforce(
        aurogonPackages: Array<out String>,
        policyPackages: Array<out String>,
        autostartManaged: BooleanArray,
        autostartEnabled: BooleanArray,
        dozeManaged: BooleanArray,
        dozePolicies: IntArray,
        targetUserIds: IntArray,
        trigger: String,
    ): String = enforceBatched(
        aurogonPackages,
        policyPackages,
        autostartManaged,
        autostartEnabled,
        dozeManaged,
        dozePolicies,
        targetUserIds,
        trigger,
        null,
    )

    override fun enforceBatched(
        aurogonPackages: Array<out String>,
        policyPackages: Array<out String>,
        autostartManaged: BooleanArray,
        autostartEnabled: BooleanArray,
        dozeManaged: BooleanArray,
        dozePolicies: IntArray,
        targetUserIds: IntArray,
        trigger: String,
        progressCallback: IEnforcementProgressCallback?,
    ): String {
        val startedAt = System.nanoTime()
        val policies = decodePolicies(
            policyPackages,
            autostartManaged,
            autostartEnabled,
            dozeManaged,
            dozePolicies,
        )
        val batches = EnforcementBatching.split(policies)
        val targetUsers = targetUserIds.distinct().sorted()
        require(targetUsers.all { it >= 0 }) { "Invalid Android user ID" }
        val includeAutostartSwitchOp = isAutostartSwitchOpAvailable()
        serviceLog(
            'I',
            "Enforcement",
            "start trigger=$trigger aurogon=${aurogonPackages.size} policies=${policies.size} batches=${batches.size} users=${targetUsers.joinToString()}",
        )
        notifyProgress(progressCallback, 0, policies.size)
        return runCatching {
            val installedSnapshot = loadInstalledPackages(targetUsers)
            val report = buildString {
                appendLine(startFcmProtection(aurogonPackages, trigger))
                appendLine(installedSnapshot.report)
                if (batches.isEmpty()) {
                    append(
                        enforcePolicyBatch(
                            policies = emptyList(),
                            targetUsers = targetUsers,
                            installedPackagesByUser = installedSnapshot.packagesByUser,
                            includeSelfProtection = true,
                            includeWriteSettings = true,
                            includeAutostartSwitchOp = includeAutostartSwitchOp,
                            progressStartIndex = 0,
                            onProgress = null,
                        ),
                    )
                } else {
                    var completed = 0
                    batches.forEachIndexed { index, batch ->
                        appendLine("Policy batch ${index + 1}/${batches.size} (${batch.size} apps)")
                        appendLine(
                            enforcePolicyBatch(
                                policies = batch,
                                targetUsers = targetUsers,
                                installedPackagesByUser = installedSnapshot.packagesByUser,
                                includeSelfProtection = index == 0,
                                includeWriteSettings = index == batches.lastIndex,
                                includeAutostartSwitchOp = includeAutostartSwitchOp,
                                progressStartIndex = completed,
                                onProgress = { processed ->
                                    notifyProgress(progressCallback, processed, policies.size)
                                },
                            ),
                        )
                        completed += batch.size
                        notifyProgress(progressCallback, completed, policies.size)
                    }
                }
            }.trim()
            if (installedSnapshot.failed) {
                "$report\nFAILED: one or more installed-package snapshots failed"
            } else {
                report
            }
        }.onSuccess { report ->
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            serviceLog('I', "Enforcement", "finish trigger=$trigger durationMs=$durationMillis report=${report.singleLine(4_000)}")
        }.onFailure { error ->
            serviceLog('E', "Enforcement", "failed trigger=$trigger error=${error.stackTraceToString().take(4_000)}")
        }.getOrThrow()
    }

    private fun enforcePolicyBatch(
        policies: List<AppPolicy>,
        targetUsers: List<Int>,
        installedPackagesByUser: Map<Int, Set<String>>,
        includeSelfProtection: Boolean,
        includeWriteSettings: Boolean,
        includeAutostartSwitchOp: Boolean,
        progressStartIndex: Int,
        onProgress: ((Int) -> Unit)?,
    ): String {
        val startedAt = System.nanoTime()
        val report = CommandReport("Per-app battery policy enforcement")
        report.line("shell_uid=${Process.myUid()} configured_apps=${policies.size}")
        if (targetUsers.isEmpty() && policies.any { it.autostartManaged || it.dozeManaged }) {
            report.line("No Android users selected; configured app policies were skipped")
        }
        policies.forEachIndexed { index, policy ->
            EnforcementCommandPlan.requireValidPackageName(policy.packageName)
            if (policy.autostartManaged || policy.dozeManaged) {
                var installed = false
                targetUsers.forEach { userId ->
                    if (policy.packageName in installedPackagesByUser[userId].orEmpty()) {
                        installed = true
                        EnforcementCommandPlan.policyCommands(
                            policy,
                            userId,
                            includeAutostartSwitchOp,
                        ).forEach(report::run)
                        report.line(
                            "${policy.packageName} user $userId: " +
                                "autostart=${if (policy.autostartManaged) policy.autostartEnabled else "unmanaged"} " +
                                "doze=${if (policy.dozeManaged) policy.dozePolicy.persistedValue else "unmanaged"}",
                        )
                    } else {
                        report.line("${policy.packageName} user $userId: skipped (not installed)")
                    }
                }
                if (installed) EnforcementCommandPlan.dozeWhitelistCommand(policy)?.let(report::run)
            } else {
                report.line("${policy.packageName}: no per-app policy selected")
            }
            onProgress?.invoke(progressStartIndex + index + 1)
        }
        if (includeSelfProtection) {
            report.line("Self-protection: user 0")
            EnforcementCommandPlan.selfProtectionCommands(
                BuildConfig.APPLICATION_ID,
                includeAutostartSwitchOp,
            ).forEach(report::run)
        }
        if (includeWriteSettings) report.run(EnforcementCommandPlan.writeAppOpsSettingsCommand())
        val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        return report.build(durationMillis).also { output ->
            serviceLog(
                if (report.failed == 0) 'I' else 'E',
                "Commands",
                "attempts=${report.attempts} failed=${report.failed} durationMs=$durationMillis output=${output.singleLine(4_000)}",
            )
        }
    }

    private fun decodePolicies(
        policyPackages: Array<out String>,
        autostartManaged: BooleanArray,
        autostartEnabled: BooleanArray,
        dozeManaged: BooleanArray,
        dozePolicies: IntArray,
    ): List<AppPolicy> {
        require(
            listOf(
                autostartManaged.size,
                autostartEnabled.size,
                dozeManaged.size,
                dozePolicies.size,
            ).all { it == policyPackages.size },
        ) {
            "Mismatched app-policy arrays"
        }
        return policyPackages.indices.map { index ->
            AppPolicy(
                packageName = policyPackages[index],
                appEnabled = true,
                autostartManaged = autostartManaged[index],
                autostartEnabled = autostartEnabled[index],
                dozeManaged = dozeManaged[index],
                dozePolicy = AppDozePolicy.fromCode(dozePolicies[index]),
            )
        }.onEach { policy -> EnforcementCommandPlan.requireValidPackageName(policy.packageName) }
    }

    private fun loadInstalledPackages(targetUserIds: List<Int>): InstalledPackagesSnapshot {
        var failed = false
        val reports = mutableListOf<String>()
        val packagesByUser = targetUserIds.associateWith { userId ->
            val result = runSystemCommand(
                SystemServiceCommands.packageManager("list", "packages", "--user", userId.toString()),
                USER_LIST_COMMAND_TIMEOUT_SECONDS,
                MAX_PACKAGE_LIST_OUTPUT_LENGTH,
            )
            if (!result.succeeded) {
                failed = true
                reports += "Installed packages user $userId: FAILED (${result.summary})"
                emptySet()
            } else {
                val packages = result.output
                    .lineSequence()
                    .mapNotNull { line -> line.removePrefix("package:").takeIf { it != line && it.isNotBlank() } }
                    .toSet()
                reports += "Installed packages user $userId: ${packages.size} found"
                packages
            }
        }
        return InstalledPackagesSnapshot(packagesByUser, reports.joinToString("\n"), failed)
    }

    private fun notifyProgress(callback: IEnforcementProgressCallback?, completed: Int, total: Int) {
        if (callback == null) return
        runCatching { callback.onProgress(completed, total) }
            .onFailure { error ->
                serviceLog('W', "Enforcement", "progress callback failed: ${error.message ?: error.javaClass.simpleName}")
            }
    }

    override fun destroy() {
        serviceLog('I', "Service", "destroy uid=${Process.myUid()}")
        Log.i(TAG, "Destroying Shizuku user service")
        fcmPolling?.cancel(false)
        fcmExecutor.shutdownNow()
        systemCommands.close()
        synchronized(serviceLogLock) {
            runCatching { serviceLogWriter?.close() }
            serviceLogWriter = null
        }
        exitProcess(0)
    }

    override fun startFcmProtection(
        aurogonPackages: Array<out String>,
        trigger: String,
    ): String {
        val desired = aurogonPackages.toSet()
        val logTag = fcmLogTag(trigger)
        serviceLog(
            'I',
            logTag,
            "start trigger=$trigger aurogon=${desired.size} pollActive=${isFcmPollingActive()}",
        )
        startFcmPolling()

        val report = buildString {
            appendLine("FCM protection")
            appendLine(runGreezerCommand("Disable explicit GMS limiter", "IM", "GMS", "disable"))
            appendLine(runGreezerCommand("Restore ordinary GMS allowlist", "LM", "add", MilletNoRestrictList.GMS_PACKAGE))
            appendLine(ensureGmsNoRestrict(logTag))
            appendLine(ensureAurogon(desired))
            append(
                "FCM setting monitor: active " +
                    "(${MilletPollingInterval.diagnosticLabel(fcmPollingIntervalMillis)} Shizuku poll, " +
                    "reconnect ${if (fcmReconnectEnabled) "enabled" else "disabled"})",
            )
        }
        serviceLog('I', logTag, "finish trigger=$trigger report=${report.singleLine(2_000)}")
        return report
    }

    override fun unstop(
        packageNames: Array<out String>,
        targetUserIds: IntArray,
        trigger: String,
    ): String {
        val packages = packageNames.distinct().sorted()
        val users = targetUserIds.distinct().sorted()
        packages.forEach(EnforcementCommandPlan::requireValidPackageName)
        require(users.all { it >= 0 }) { "Invalid Android user ID" }
        serviceLog(
            'I',
            "Unstop",
            "start trigger=$trigger packages=${packages.size} users=${users.joinToString()}",
        )
        var checked = 0
        var unstopped = 0
        var failed = 0
        val lines = mutableListOf<String>()
        if (packages.isEmpty() || users.isEmpty()) {
            lines += "Auto unstop: no packages or Android users selected"
        } else {
            users.forEach { userId ->
                val state = runSystemCommand(
                    SystemServiceCommands.packageManager(
                        "list",
                        "packages",
                        "--user",
                        userId.toString(),
                        "--show-stopped",
                    ),
                    USER_LIST_COMMAND_TIMEOUT_SECONDS,
                    MAX_PACKAGE_LIST_OUTPUT_LENGTH,
                )
                if (!state.succeeded) {
                    failed++
                    lines += "Auto unstop: failed to list user $userId (${state.summary})"
                    return@forEach
                }
                val stoppedPackages = PackageManagerOutput.selectedStoppedPackages(state.output, packages)
                stoppedPackages.forEach { packageName ->
                    checked++
                    val result = runSystemCommand(
                        SystemServiceCommands.packageManager(
                            "unstop",
                            "--user",
                            userId.toString(),
                            packageName,
                        ),
                        USER_LIST_COMMAND_TIMEOUT_SECONDS,
                    )
                    if (result.succeeded) {
                        unstopped++
                        lines += "Auto unstop: user $userId package $packageName"
                    } else {
                        failed++
                        lines += "Auto unstop: failed user $userId package $packageName (${result.summary})"
                    }
                }
            }
        }
        lines += "Auto unstop summary: checked=$checked unstopped=$unstopped failed=$failed"
        val report = lines.joinToString("\n")
        serviceLog(
            if (failed > 0) 'E' else 'I',
            "Unstop",
            "finish trigger=$trigger report=${report.singleLine(2_000)}",
        )
        return report
    }

    override fun reconcileAurogon(
        aurogonPackages: Array<out String>,
        trigger: String,
    ): String {
        val desired = aurogonPackages.toSet()
        startFcmPolling()
        val report = ensureAurogon(desired)
        serviceLog(
            if (report.contains("FAILED:")) 'E' else 'I',
            "Aurogon",
            "reconcile trigger=$trigger enabled=${desired.size} report=${report.singleLine(2_000)}",
        )
        return report
    }

    override fun applyAppPolicy(
        packageName: String,
        autostartManaged: Boolean,
        autostartEnabled: Boolean,
        dozeManaged: Boolean,
        dozePolicy: Int,
        targetUserIds: IntArray,
        trigger: String,
    ): String {
        EnforcementCommandPlan.requireValidPackageName(packageName)
        val targetUsers = targetUserIds.distinct().sorted()
        require(targetUsers.all { it >= 0 }) { "Invalid Android user ID" }
        val policy = AppPolicy(
            packageName = packageName,
            appEnabled = true,
            autostartManaged = autostartManaged,
            autostartEnabled = autostartEnabled,
            dozeManaged = dozeManaged,
            dozePolicy = AppDozePolicy.fromCode(dozePolicy),
        )
        serviceLog(
            'I',
            "AppPolicy",
            "apply trigger=$trigger package=$packageName autostart=${if (autostartManaged) autostartEnabled else "unmanaged"} battery=${if (dozeManaged) policy.dozePolicy.persistedValue else "unmanaged"} users=${targetUserIds.joinToString()}",
        )
        val installedSnapshot = loadInstalledPackages(targetUsers)
        return buildString {
            appendLine(installedSnapshot.report)
            append(
                enforcePolicyBatch(
                    policies = listOf(policy),
                    targetUsers = targetUsers,
                    installedPackagesByUser = installedSnapshot.packagesByUser,
                    includeSelfProtection = false,
                    includeWriteSettings = true,
                    includeAutostartSwitchOp = isAutostartSwitchOpAvailable(),
                    progressStartIndex = 0,
                    onProgress = null,
                ),
            )
            if (installedSnapshot.failed) append("\nFAILED: one or more installed-package snapshots failed")
        }.also { report ->
            serviceLog(
                if (report.contains("FAILED")) 'E' else 'I',
                "AppPolicy",
                "finish trigger=$trigger report=${report.singleLine(2_000)}",
            )
        }
    }

    /**
     * The Xiaomi Autostart switch AppOp (10053) does not exist on some ROMs (e.g. HyperOS 1),
     * where AppOpsService rejects it with "Bad operation #10053" and every write fails.
     * Probe with the same command shape on our own package once per service process: the
     * mutation only ever affects our own app and is identical to the self-protection write.
     */
    private fun isAutostartSwitchOpAvailable(): Boolean {
        autostartSwitchOpAvailable?.let { return it }
        val result = runSystemCommand(
            SystemServiceCommands.appOps(
                "set",
                "--user",
                "0",
                BuildConfig.APPLICATION_ID,
                EnforcementCommandPlan.MIUI_AUTOSTART_SWITCH_OP.toString(),
                "allow",
            ),
            SETTINGS_COMMAND_TIMEOUT_SECONDS,
        )
        val available = result.succeeded
        autostartSwitchOpAvailable = available
        return available
    }

    override fun getMilletNoRestrictValue(trigger: String): String {
        val read = readMilletNoRestrict()
        val result = if (read.succeeded) {
            read.output.ifBlank { "(empty)" }
        } else {
            "FAILED: could not read ${MilletNoRestrictList.SETTING_NAME} (${read.summary})"
        }
        serviceLog(if (read.succeeded) 'I' else 'E', "MILLET", "read trigger=$trigger value=${result.singleLine(2_000)}")
        return result
    }

    override fun listAndroidUsers(trigger: String): String {
        val result = runSystemCommand(
            SystemServiceCommands.packageManager("list", "users"),
            USER_LIST_COMMAND_TIMEOUT_SECONDS,
        )
        val output = if (result.succeeded) {
            result.output
        } else {
            "FAILED: could not list Android users (${result.summary})"
        }
        serviceLog(if (result.succeeded) 'I' else 'E', "Users", "list trigger=$trigger output=${output.singleLine(2_000)}")
        return output
    }

    override fun attachLogPath(logPath: String) {
        val file = validateLogFile(logPath)
        synchronized(serviceLogLock) {
            runCatching { serviceLogWriter?.close() }
            serviceLogWriter = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
            )
            enqueueServiceLogLocked(
                'I',
                "Service",
                "external log attached file=${file.name} uid=${Process.myUid()} pid=${Process.myPid()}",
            )
            flushPendingServiceLogsLocked()
        }
    }

    override fun configureFcmPolling(
        intervalMillis: Long,
        fcmReconnectEnabled: Boolean,
        trigger: String,
    ): String {
        require(MilletPollingInterval.isSupported(intervalMillis)) {
            "Unsupported MILLET polling interval: $intervalMillis ms"
        }
        val wasActive: Boolean
        val changed: Boolean
        val reconnectChanged: Boolean
        synchronized(this) {
            wasActive = isFcmPollingActive()
            changed = fcmPollingIntervalMillis != intervalMillis
            reconnectChanged = this.fcmReconnectEnabled != fcmReconnectEnabled
            if (wasActive && changed) {
                fcmPolling?.cancel(false)
                fcmPolling = null
            }
            fcmPollingIntervalMillis = intervalMillis
            this.fcmReconnectEnabled = fcmReconnectEnabled
            startFcmPollingLocked()
        }
        val label = MilletPollingInterval.diagnosticLabel(intervalMillis)
        serviceLog(
            'I',
            fcmLogTag(trigger),
            "poll configured trigger=$trigger interval=$label changed=$changed " +
                "fcmReconnect=$fcmReconnectEnabled reconnectChanged=$reconnectChanged wasActive=$wasActive",
        )
        return "FCM setting monitor: active ($label Shizuku poll, " +
            "reconnect ${if (fcmReconnectEnabled) "enabled" else "disabled"})"
    }

    private fun startFcmPolling() {
        synchronized(this) {
            startFcmPollingLocked()
        }
    }

    private fun startFcmPollingLocked() {
        if (isFcmPollingActive()) return
        val intervalMillis = fcmPollingIntervalMillis
        fcmPolling = fcmExecutor.scheduleWithFixedDelay(
            {
                runFcmPoll()
            },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        serviceLog(
            'I',
            FCM_POLL_LOG_TAG,
            "poll started interval=${MilletPollingInterval.diagnosticLabel(intervalMillis)}",
        )
    }

    private fun isFcmPollingActive(): Boolean =
        fcmPolling?.let { !it.isCancelled && !it.isDone } == true

    private fun runFcmPoll() {
        runCatching { ensureGmsNoRestrict(FCM_POLL_LOG_TAG) }
            .onSuccess { result -> logMilletRepairResult(result) }
            .onFailure { error ->
                serviceLog('E', FCM_POLL_LOG_TAG, "MILLET repair crashed: ${error.stackTraceToString().take(4_000)}")
            }
        if (fcmReconnectEnabled) {
            runCatching { runFcmReconnectProtection() }
                .onFailure { error ->
                    serviceLog(
                        'E',
                        FCM_POLL_LOG_TAG,
                        "reconnect protection crashed: ${error.stackTraceToString().take(4_000)}",
                    )
                }
        }
    }

    private fun runFcmReconnectProtection() {
        val previouslyCachedUid = cachedGmsUid
        val gmsUid = previouslyCachedUid ?: refreshCachedGmsUid()
        if (gmsUid == null) {
            forceFcmReconnect("poll:gms-uid-unavailable")
            return
        }

        when (FcmSocketProbe.probe(gmsUid)) {
            FcmSocketProbeResult.MATCHED -> Unit
            FcmSocketProbeResult.NO_MATCH -> {
                serviceLog(
                    'I',
                    FCM_POLL_LOG_TAG,
                    "socket no match cachedGmsUid=$gmsUid " +
                        "pollInterval=${MilletPollingInterval.diagnosticLabel(fcmPollingIntervalMillis)}",
                )
                if (previouslyCachedUid != null) refreshCachedGmsUid()
                forceFcmReconnect("poll:socket-missing")
            }
            FcmSocketProbeResult.UNAVAILABLE -> forceFcmReconnect("poll:socket-unavailable")
        }
    }

    private fun refreshCachedGmsUid(): Int? {
        val result = runSystemCommand(
            SystemServiceCommands.packageManager(
                "list",
                "packages",
                "--user",
                SETTINGS_USER,
                "-U",
                MilletNoRestrictList.GMS_PACKAGE,
            ),
            GMS_UID_COMMAND_TIMEOUT_SECONDS,
        )
        val resolvedUid = result.takeIf(SystemServiceCommandRunner.Result::succeeded)
            ?.output
            ?.let { output -> PackageManagerOutput.packageUid(output, MilletNoRestrictList.GMS_PACKAGE) }
        if (resolvedUid != null) cachedGmsUid = resolvedUid
        return resolvedUid
    }

    override fun forceFcmReconnect(trigger: String): String {
        val logTag = fcmLogTag(trigger)
        val result = runSystemCommand(
            SystemServiceCommands.activity(
                "broadcast",
                "--user",
                SETTINGS_USER,
                "-a",
                GCM_RECONNECT_ACTION,
                "-p",
                MilletNoRestrictList.GMS_PACKAGE,
            ),
            FCM_COMMAND_TIMEOUT_SECONDS,
        )
        val report = if (result.succeeded) {
            "FCM reconnect: broadcast sent"
        } else {
            "FCM reconnect: FAILED to send broadcast (${result.summary})"
        }
        serviceLog(
            if (result.succeeded) 'I' else 'E',
            logTag,
            "reconnect trigger=$trigger result=${report.singleLine(2_000)}",
        )
        return report
    }

    private fun logMilletRepairResult(result: String) {
        if (result.contains("FAILED")) {
            serviceLog('E', FCM_POLL_LOG_TAG, "MILLET repair result=${result.singleLine(2_000)}")
        } else if (result.contains("append write completed")) {
            serviceLog('I', FCM_POLL_LOG_TAG, "MILLET repair result=${result.singleLine(2_000)}")
        }
    }

    private fun fcmLogTag(trigger: String): String = when {
        trigger.startsWith(FCM_POLL_TRIGGER_PREFIX) -> FCM_POLL_LOG_TAG
        else -> FCM_LOG_TAG
    }

    private fun ensureGmsNoRestrict(logTag: String): String = synchronized(fcmRepairLock) {
        val initialRead = readMilletNoRestrict()
        if (!initialRead.succeeded) {
            serviceLog('E', logTag, "MILLET read failed ${initialRead.summary}")
            return@synchronized "MILLET no-restrict: FAILED to read (${initialRead.summary})"
        }

        val existing = MilletNoRestrictList.parse(initialRead.output)
        if (MilletNoRestrictList.GMS_PACKAGE in existing) {
            return@synchronized "MILLET no-restrict: GMS present; preserved ${existing.size} entries"
        }

        val updated = MilletNoRestrictList.appendGms(initialRead.output)
        val serialized = MilletNoRestrictList.serialize(updated)
        val write = runSystemCommand(
            SystemServiceCommands.settings(
                "--user",
                SETTINGS_USER,
                "put",
                "system",
                MilletNoRestrictList.SETTING_NAME,
                serialized,
            ),
            SETTINGS_COMMAND_TIMEOUT_SECONDS,
        )
        if (!write.succeeded) {
            serviceLog('E', logTag, "MILLET append failed existing=${existing.joinToString()} ${write.summary}")
            return@synchronized "MILLET no-restrict: FAILED to append GMS (${write.summary})"
        }

        serviceLog(
            'I',
            logTag,
            "MILLET append write completed existing=${existing.joinToString()} updated=${updated.joinToString()}",
        )
        "MILLET no-restrict: append write completed after preserving ${existing.size} entries"
    }

    private fun readMilletNoRestrict(): SystemServiceCommandRunner.Result = runSystemCommand(
        SystemServiceCommands.settings(
            "--user",
            SETTINGS_USER,
            "get",
            "system",
            MilletNoRestrictList.SETTING_NAME,
        ),
        SETTINGS_COMMAND_TIMEOUT_SECONDS,
    )

    private fun ensureAurogon(desired: Set<String>): String = synchronized(aurogonRepairLock) {
        val initialRead = readAurogonEnable()
        if (!initialRead.succeeded) {
            return@synchronized "Aurogon: FAILED to read (${initialRead.summary})"
        }
        val candidate = AurogonConfig.merge(
            initialRead.output,
            desired,
            BuildConfig.APPLICATION_ID,
        )
        val current = initialRead.output.takeUnless { it == "null" }.orEmpty()
        if (candidate == current && AurogonConfig.rulesEffective(current, desired)) {
            return@synchronized "Aurogon: ${desired.size} enabled rules present"
        }

        val stableRead = readAurogonEnable()
        if (!stableRead.succeeded) {
            return@synchronized "Aurogon: FAILED stability read (${stableRead.summary})"
        }
        val stableCurrent = stableRead.output.takeUnless { it == "null" }.orEmpty()
        if (stableCurrent != current) {
            return@synchronized "Aurogon: changed concurrently; deferred"
        }

        val command = if (candidate.isEmpty()) {
            SystemServiceCommands.settings("delete", "global", AurogonConfig.SETTING_NAME)
        } else {
            SystemServiceCommands.settings("put", "global", AurogonConfig.SETTING_NAME, candidate)
        }
        val write = runSystemCommand(command, SETTINGS_COMMAND_TIMEOUT_SECONDS)
        if (!write.succeeded) {
            return@synchronized "Aurogon: FAILED to write (${write.summary})"
        }

        val verification = readAurogonEnable()
        if (!verification.succeeded || !AurogonConfig.rulesEffective(verification.output, desired)) {
            return@synchronized "Aurogon: FAILED verification (${verification.summary})"
        }
        val converged = AurogonConfig.merge(
            verification.output,
            desired,
            BuildConfig.APPLICATION_ID,
        )
        val verifiedValue = verification.output.takeUnless { it == "null" }.orEmpty()
        if (converged != verifiedValue) {
            return@synchronized "Aurogon: FAILED convergence verification"
        }

        serviceLog('I', "Aurogon", "updated desired=${desired.joinToString()} preservedValueLength=${candidate.length}")
        "Aurogon: updated managed rules (${desired.size} enabled)"
    }

    private fun readAurogonEnable(): SystemServiceCommandRunner.Result = runSystemCommand(
        SystemServiceCommands.settings("get", "global", AurogonConfig.SETTING_NAME),
        SETTINGS_COMMAND_TIMEOUT_SECONDS,
    )

    private fun runGreezerCommand(label: String, vararg arguments: String): String {
        val result = systemCommands.dump(
            serviceName = SystemServiceCommands.GREEZER_SERVICE,
            arguments = arguments.toList(),
            timeoutSeconds = GREEZER_COMMAND_TIMEOUT_SECONDS,
            maxOutputLength = MAX_OUTPUT_LENGTH,
        )
        serviceLog(
            if (result.succeeded) 'I' else 'E',
            "Greezer",
            "command=${arguments.joinToString(" ")} completed=${result.completed} exit=${result.exitCode} output=${result.output.singleLine(2_000)}",
        )
        return if (result.succeeded) {
            "$label: applied"
        } else {
            "$label: unavailable (${result.summary})"
        }
    }

    private fun serviceLog(level: Char, tag: String, message: String) {
        when (level) {
            'E' -> Log.e("$TAG/$tag", message)
            'W' -> Log.w("$TAG/$tag", message)
            else -> Log.i("$TAG/$tag", message)
        }
        synchronized(serviceLogLock) {
            enqueueServiceLogLocked(level, tag, message)
            flushPendingServiceLogsLocked()
        }
    }

    private fun enqueueServiceLogLocked(level: Char, tag: String, message: String) {
        val line = buildString {
            append(serviceLogTimeFormat.format(Date()))
            append(' ')
            append(level)
            append("/SHIZUKU/")
            append(tag)
            append(": ")
            append(message)
        }
        pendingServiceLogs.addLast(line)
        pendingServiceLogChars += line.length + 1
        while (pendingServiceLogChars > MAX_PENDING_LOG_CHARS && pendingServiceLogs.isNotEmpty()) {
            pendingServiceLogChars -= pendingServiceLogs.removeFirst().length + 1
            droppedServiceLogLines++
        }
    }

    private fun flushPendingServiceLogsLocked() {
        val writer = serviceLogWriter ?: return
        runCatching {
            if (droppedServiceLogLines > 0) {
                writer.append(serviceLogTimeFormat.format(Date()))
                writer.append(" W/SHIZUKU/Service: dropped $droppedServiceLogLines oldest buffered log lines")
                writer.newLine()
            }
            pendingServiceLogs.forEach { line ->
                writer.append(line)
                writer.newLine()
            }
            writer.flush()
        }.onSuccess {
            pendingServiceLogs.clear()
            pendingServiceLogChars = 0
            droppedServiceLogLines = 0
        }.onFailure { error ->
            Log.w(TAG, "Could not append external Shizuku log", error)
            runCatching { writer.close() }
            serviceLogWriter = null
        }
    }

    private fun validateLogFile(logPath: String): File {
        val expectedDirectory = File(
            "/storage/emulated/0/Android/data/${BuildConfig.APPLICATION_ID}/files/logs",
        ).canonicalFile
        val file = File(logPath).canonicalFile
        require(file.parentFile == expectedDirectory) { "Log path is outside the app's owner-user log directory" }
        require(file.name.startsWith("session-") && file.extension == "log") { "Invalid log filename" }
        return file
    }

    private fun String.singleLine(maxChars: Int): String {
        val flattened = lineSequence().joinToString(" | ").trim()
        return if (flattened.length > maxChars) flattened.take(maxChars) + "..." else flattened
    }

    private fun runSystemCommand(
        command: SystemServiceCommand,
        timeoutSeconds: Long,
        maxOutputLength: Int = MAX_OUTPUT_LENGTH,
    ): SystemServiceCommandRunner.Result = systemCommands.run(command, timeoutSeconds, maxOutputLength)

    private inner class CommandReport(header: String) {
        private val lines = mutableListOf(header)
        private val deadlineNanos = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(POLICY_BATCH_TIMEOUT_SECONDS)
        var attempts: Int = 0
            private set
        private var succeeded: Int = 0
        var failed: Int = 0
            private set

        fun line(message: String) {
            lines += message
        }

        fun run(command: SystemServiceCommand) {
            attempts++
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                failed++
                lines += "FAILED [${command.diagnosticName}]: policy batch timed out"
                return
            }
            val remainingSeconds = (remainingNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
            val result = runSystemCommand(
                command,
                minOf(POLICY_COMMAND_TIMEOUT_SECONDS, remainingSeconds),
            )
            if (result.succeeded) {
                succeeded++
            } else {
                failed++
                lines += "FAILED [${command.diagnosticName}]: ${result.summary}"
            }
        }

        fun build(durationMillis: Long): String = buildString {
            append("service_uid=${Process.myUid()} duration_ms=$durationMillis").appendLine()
            append(lines.joinToString("\n")).appendLine()
            append("summary: $succeeded/$attempts commands succeeded; $failed failed")
        }
    }

    private data class InstalledPackagesSnapshot(
        val packagesByUser: Map<Int, Set<String>>,
        val report: String,
        val failed: Boolean,
    )

    companion object {
        private const val TAG = "PowerKeeperFix/Shizuku"
        private const val POLICY_BATCH_TIMEOUT_SECONDS = 120L
        private const val POLICY_COMMAND_TIMEOUT_SECONDS = 10L
        private const val SETTINGS_COMMAND_TIMEOUT_SECONDS = 10L
        private const val FCM_COMMAND_TIMEOUT_SECONDS = 10L
        private const val GMS_UID_COMMAND_TIMEOUT_SECONDS = 10L
        private const val GREEZER_COMMAND_TIMEOUT_SECONDS = 20L
        private const val USER_LIST_COMMAND_TIMEOUT_SECONDS = 10L
        private const val MAX_OUTPUT_LENGTH = 64_000
        private const val MAX_PACKAGE_LIST_OUTPUT_LENGTH = 512_000
        private const val MAX_PENDING_LOG_CHARS = 128_000
        private const val SETTINGS_USER = "0"
        private const val GCM_RECONNECT_ACTION = "com.google.android.intent.action.GCM_RECONNECT"
        private const val FCM_LOG_TAG = "FCM"
        private const val FCM_POLL_LOG_TAG = "FCMPoll"
        private const val FCM_POLL_TRIGGER_PREFIX = "poll:"
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
