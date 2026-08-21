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
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class PowerKeeperUserService : IPrivilegedService.Stub {
    constructor() : super()

    private val fcmExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fcm-protection").apply { isDaemon = true }
    }
    private val fcmRepairLock = Any()
    private val aurogonRepairLock = Any()
    private var desiredAurogon: Set<String> = emptySet()
    private var autostartSwitchOpAvailable: Boolean? = null
    private var fcmPolling: ScheduledFuture<*>? = null
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
        val includeAutostartSwitchOp = isAutostartSwitchOpAvailable()
        serviceLog(
            'I',
            "Enforce",
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
                        runScript(
                            EnforcementScript.build(
                                policies = emptyList(),
                                applicationId = BuildConfig.APPLICATION_ID,
                                targetUsers = targetUsers,
                                includeAutostartSwitchOp = includeAutostartSwitchOp,
                            ),
                        ),
                    )
                } else {
                    var completed = 0
                    batches.forEachIndexed { index, batch ->
                        appendLine("Policy batch ${index + 1}/${batches.size} (${batch.size} apps)")
                        appendLine(
                            runScript(
                                EnforcementScript.build(
                                    policies = batch,
                                    applicationId = BuildConfig.APPLICATION_ID,
                                    targetUsers = targetUsers,
                                    includeSelfProtection = index == 0,
                                    includeWriteSettings = index == batches.lastIndex,
                                    installedPackagesByUser = installedSnapshot.packagesByUser,
                                    progressStartIndex = completed,
                                    includeAutostartSwitchOp = includeAutostartSwitchOp,
                                ),
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
            serviceLog('I', "Enforce", "finish trigger=$trigger durationMs=$durationMillis report=${report.singleLine(4_000)}")
        }.onFailure { error ->
            serviceLog('E', "Enforce", "failed trigger=$trigger error=${error.stackTraceToString().take(4_000)}")
        }.getOrThrow()
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
        }
    }

    private fun loadInstalledPackages(targetUserIds: List<Int>): InstalledPackagesSnapshot {
        var failed = false
        val reports = mutableListOf<String>()
        val packagesByUser = targetUserIds.associateWith { userId ->
            val result = runCommand(
                listOf(PM_BINARY, "list", "packages", "--user", userId.toString()),
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
                serviceLog('W', "Enforce", "progress callback failed: ${error.message ?: error.javaClass.simpleName}")
            }
    }

    override fun destroy() {
        serviceLog('I', "Service", "destroy uid=${Process.myUid()}")
        Log.i(TAG, "Destroying Shizuku user service")
        fcmPolling?.cancel(false)
        fcmExecutor.shutdownNow()
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
        val desired = updateDesiredAurogon(aurogonPackages)
        serviceLog('I', "FCM", "start trigger=$trigger aurogon=${desired.size} pollActive=${isFcmPollingActive()}")
        startFcmPolling()

        val report = buildString {
            appendLine("FCM protection")
            appendLine(runGreezerCommand("Disable explicit GMS limiter", "IM", "GMS", "disable"))
            appendLine(runGreezerCommand("Restore ordinary GMS allowlist", "LM", "add", MilletNoRestrictList.GMS_PACKAGE))
            appendLine(ensureGmsNoRestrict())
            appendLine(ensureAurogon(desired))
            append("FCM setting monitor: active (${FCM_POLL_SECONDS}s Shizuku poll)")
        }
        serviceLog('I', "FCM", "finish trigger=$trigger report=${report.singleLine(2_000)}")
        return report
    }

    override fun unstop(
        packageNames: Array<out String>,
        targetUserIds: IntArray,
        trigger: String,
    ): String {
        val packages = packageNames.distinct().sorted()
        val users = targetUserIds.distinct().sorted()
        require(users.all { it >= 0 }) { "Invalid Android user ID" }
        serviceLog(
            'I',
            "Unstop",
            "start trigger=$trigger packages=${packages.size} users=${users.joinToString()}",
        )
        val report = runScript(UnstopScript.build(packages, users))
        serviceLog(
            if (report.contains("FAILED") || report.contains("exit_code=")) 'E' else 'I',
            "Unstop",
            "finish trigger=$trigger report=${report.singleLine(2_000)}",
        )
        return report
    }

    override fun reconcileAurogon(
        aurogonPackages: Array<out String>,
        trigger: String,
    ): String {
        val desired = updateDesiredAurogon(aurogonPackages)
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
        val policy = AppPolicy(
            packageName = packageName,
            appEnabled = true,
            autostartManaged = autostartManaged,
            autostartEnabled = autostartEnabled,
            dozeManaged = dozeManaged,
            dozePolicy = AppDozePolicy.fromCode(dozePolicy),
        )
        val script = EnforcementScript.build(
            policies = listOf(policy),
            applicationId = BuildConfig.APPLICATION_ID,
            targetUsers = targetUserIds.toList(),
            includeSelfProtection = false,
            includeAutostartSwitchOp = isAutostartSwitchOpAvailable(),
        )
        serviceLog(
            'I',
            "AppPolicy",
            "apply trigger=$trigger package=$packageName autostart=${if (autostartManaged) autostartEnabled else "unmanaged"} battery=${if (dozeManaged) policy.dozePolicy.persistedValue else "unmanaged"} users=${targetUserIds.joinToString()}",
        )
        return runScript(script).also { report ->
            serviceLog(
                if (report.contains("FAILED:")) 'E' else 'I',
                "AppPolicy",
                "finish trigger=$trigger report=${report.singleLine(2_000)}",
            )
        }
    }

    private fun updateDesiredAurogon(aurogonPackages: Array<out String>): Set<String> {
        val desired = aurogonPackages.toSet()
        synchronized(aurogonRepairLock) {
            desiredAurogon = desired
        }
        return desired
    }

    /**
     * The Xiaomi Autostart switch AppOp (10053) does not exist on some ROMs (e.g. HyperOS 1),
     * where AppOpsService rejects it with "Bad operation #10053" and every write fails.
     * Probe with the same command shape on our own package once per service process: the
     * mutation only ever affects our own app and is identical to the self-protection write.
     */
    private fun isAutostartSwitchOpAvailable(): Boolean {
        autostartSwitchOpAvailable?.let { return it }
        val result = runCommand(
            listOf(
                CMD_BINARY,
                "appops",
                "set",
                "--user",
                "0",
                BuildConfig.APPLICATION_ID,
                EnforcementScript.MIUI_AUTOSTART_SWITCH_OP.toString(),
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
        val result = runCommand(
            listOf(PM_BINARY, "list", "users"),
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

    private fun startFcmPolling() {
        if (isFcmPollingActive()) return
        synchronized(this) {
            if (isFcmPollingActive()) return
            fcmPolling = fcmExecutor.scheduleWithFixedDelay(
                {
                    runCatching { ensureGmsNoRestrict() }
                        .onSuccess { result -> logRepairResult("poll", result) }
                        .onFailure { error ->
                            serviceLog('E', "FCM", "repair poll crashed: ${error.stackTraceToString().take(4_000)}")
                        }
                    val desired = synchronized(aurogonRepairLock) { desiredAurogon }
                    runCatching { ensureAurogon(desired) }
                        .onSuccess { result -> logRepairResult("aurogon-poll", result) }
                        .onFailure { error ->
                            serviceLog('E', "Aurogon", "repair poll crashed: ${error.stackTraceToString().take(4_000)}")
                        }
                },
                FCM_POLL_SECONDS,
                FCM_POLL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun isFcmPollingActive(): Boolean =
        fcmPolling?.let { !it.isCancelled && !it.isDone } == true

    private fun logRepairResult(reason: String, result: String) {
        if (result.contains("FAILED")) {
            Log.w(TAG, "FCM repair ($reason): $result")
            serviceLog('E', "FCM", "repair reason=$reason result=${result.singleLine(2_000)}")
        } else if (result.contains("appended GMS") || result.contains("updated managed rules")) {
            Log.i(TAG, "FCM repair ($reason): $result")
            serviceLog('I', "FCM", "repair reason=$reason result=${result.singleLine(2_000)}")
        }
    }

    private fun ensureGmsNoRestrict(): String = synchronized(fcmRepairLock) {
        val initialRead = readMilletNoRestrict()
        if (!initialRead.succeeded) {
            serviceLog('E', "MILLET", "read failed ${initialRead.summary}")
            return@synchronized "MILLET no-restrict: FAILED to read (${initialRead.summary})"
        }

        val existing = MilletNoRestrictList.parse(initialRead.output)
        if (MilletNoRestrictList.GMS_PACKAGE in existing) {
            return@synchronized "MILLET no-restrict: GMS present; preserved ${existing.size} entries"
        }

        val updated = MilletNoRestrictList.appendGms(initialRead.output)
        val serialized = MilletNoRestrictList.serialize(updated)
        val write = runCommand(
            listOf(
                SETTINGS_BINARY,
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
            serviceLog('E', "MILLET", "append failed existing=${existing.joinToString()} ${write.summary}")
            return@synchronized "MILLET no-restrict: FAILED to append GMS (${write.summary})"
        }

        val verification = readMilletNoRestrict()
        val verifiedEntries = if (verification.succeeded) {
            MilletNoRestrictList.parse(verification.output)
        } else {
            emptyList()
        }
        if (MilletNoRestrictList.GMS_PACKAGE !in verifiedEntries) {
            serviceLog('E', "MILLET", "verification failed value=${verification.output.singleLine(2_000)} ${verification.summary}")
            return@synchronized "MILLET no-restrict: FAILED verification (${verification.summary})"
        }

        serviceLog('I', "MILLET", "appended GMS existing=${existing.joinToString()} updated=${verifiedEntries.joinToString()}")
        "MILLET no-restrict: appended GMS after preserving ${existing.size} entries"
    }

    private fun readMilletNoRestrict(): CommandResult = runCommand(
        listOf(
            SETTINGS_BINARY,
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
            listOf(SETTINGS_BINARY, "delete", "global", AurogonConfig.SETTING_NAME)
        } else {
            listOf(SETTINGS_BINARY, "put", "global", AurogonConfig.SETTING_NAME, candidate)
        }
        val write = runCommand(command, SETTINGS_COMMAND_TIMEOUT_SECONDS)
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

    private fun readAurogonEnable(): CommandResult = runCommand(
        listOf(SETTINGS_BINARY, "get", "global", AurogonConfig.SETTING_NAME),
        SETTINGS_COMMAND_TIMEOUT_SECONDS,
    )

    private fun runGreezerCommand(label: String, vararg arguments: String): String {
        val result = runCommand(listOf(DUMPSYS_BINARY, "greezer", *arguments), GREEZER_COMMAND_TIMEOUT_SECONDS)
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

    private fun runScript(script: String, onProgress: ((Int) -> Unit)? = null): String {
        val startedAt = System.nanoTime()
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = thread(name = "guard-command-output", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val progress = line
                        .removePrefix(EnforcementScript.PROGRESS_PREFIX)
                        .takeIf { it != line }
                        ?.toIntOrNull()
                    if (progress != null) {
                        onProgress?.invoke(progress)
                    } else if (output.length < MAX_OUTPUT_LENGTH) {
                        output.appendLine(line)
                    }
                }
            }
        }

        val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(2_000)
        val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        return buildString {
            append("service_uid=").append(Process.myUid())
            append(" duration_ms=").append(durationMillis).appendLine()
            append(output.toString().trim())
            if (!completed) {
                appendLine()
                append("FAILED: enforcement exceeded ").append(COMMAND_TIMEOUT_SECONDS).append(" seconds")
            } else if (process.exitValue() != 0) {
                appendLine()
                append("exit_code=").append(process.exitValue())
            }
        }.trim().also { report ->
            serviceLog(
                if (completed && process.exitValue() == 0) 'I' else 'E',
                "Script",
                "completed=$completed exit=${if (completed) process.exitValue() else -1} durationMs=$durationMillis output=${report.singleLine(4_000)}",
            )
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

    private fun runCommand(
        command: List<String>,
        timeoutSeconds: Long,
        maxOutputLength: Int = MAX_OUTPUT_LENGTH,
    ): CommandResult {
        val process = runCatching {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            return CommandResult(
                completed = true,
                exitCode = -1,
                output = error.message ?: error.javaClass.simpleName,
            )
        }
        val output = StringBuilder()
        val reader = thread(name = "privileged-command-output", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < maxOutputLength) output.appendLine(line)
                }
            }
        }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(2_000)
        return CommandResult(
            completed = completed,
            exitCode = if (completed) process.exitValue() else -1,
            output = output.toString().trim(),
        )
    }

    private data class CommandResult(
        val completed: Boolean,
        val exitCode: Int,
        val output: String,
    ) {
        val succeeded: Boolean
            get() = completed && exitCode == 0

        val summary: String
            get() = when {
                !completed -> "timed out"
                output.isNotBlank() -> "exit $exitCode: ${output.take(240)}"
                else -> "exit $exitCode"
            }
    }

    private data class InstalledPackagesSnapshot(
        val packagesByUser: Map<Int, Set<String>>,
        val report: String,
        val failed: Boolean,
    )

    companion object {
        private const val TAG = "PowerKeeperFix/Shizuku"
        private const val COMMAND_TIMEOUT_SECONDS = 120L
        private const val SETTINGS_COMMAND_TIMEOUT_SECONDS = 10L
        private const val GREEZER_COMMAND_TIMEOUT_SECONDS = 20L
        private const val USER_LIST_COMMAND_TIMEOUT_SECONDS = 10L
        private const val FCM_POLL_SECONDS = 2L
        private const val MAX_OUTPUT_LENGTH = 64_000
        private const val MAX_PACKAGE_LIST_OUTPUT_LENGTH = 512_000
        private const val MAX_PENDING_LOG_CHARS = 128_000
        private const val SETTINGS_BINARY = "/system/bin/settings"
        private const val SETTINGS_USER = "0"
        private const val DUMPSYS_BINARY = "/system/bin/dumpsys"
        private const val PM_BINARY = "/system/bin/pm"
        private const val CMD_BINARY = "/system/bin/cmd"
    }
}
