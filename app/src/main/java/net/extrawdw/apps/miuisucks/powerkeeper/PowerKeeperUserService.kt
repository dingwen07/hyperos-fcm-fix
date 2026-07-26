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

    override fun enforce(wechatPolicy: Int, targetUserIds: IntArray, trigger: String): String {
        val startedAt = System.nanoTime()
        val policy = WechatPolicy.fromCode(wechatPolicy)
        serviceLog('I', "Enforce", "start trigger=$trigger policy=${policy.persistedValue} users=${targetUserIds.joinToString()}")
        val script = EnforcementScript.build(policy, BuildConfig.APPLICATION_ID, targetUserIds.toList())
        return runCatching {
            buildString {
                appendLine(startFcmProtection(trigger))
                append(runScript(script))
            }.trim()
        }.onSuccess { report ->
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            serviceLog('I', "Enforce", "finish trigger=$trigger durationMs=$durationMillis report=${report.singleLine(4_000)}")
        }.onFailure { error ->
            serviceLog('E', "Enforce", "failed trigger=$trigger error=${error.stackTraceToString().take(4_000)}")
        }.getOrThrow()
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

    override fun startFcmProtection(trigger: String): String {
        serviceLog('I', "FCM", "start trigger=$trigger pollActive=${isFcmPollingActive()}")
        startFcmPolling()

        val report = buildString {
            appendLine("FCM protection")
            appendLine(runGreezerCommand("Disable explicit GMS limiter", "IM", "GMS", "disable"))
            appendLine(runGreezerCommand("Restore ordinary GMS allowlist", "LM", "add", MilletNoRestrictList.GMS_PACKAGE))
            appendLine(ensureGmsNoRestrict())
            append("FCM setting monitor: active (${FCM_POLL_SECONDS}s Shizuku poll)")
        }
        serviceLog('I', "FCM", "finish trigger=$trigger report=${report.singleLine(2_000)}")
        return report
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
        } else if (result.contains("appended GMS")) {
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

    private fun runScript(script: String): String {
        val startedAt = System.nanoTime()
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = thread(name = "guard-command-output", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < MAX_OUTPUT_LENGTH) output.appendLine(line)
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

    private fun runCommand(command: List<String>, timeoutSeconds: Long): CommandResult {
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
                    if (output.length < MAX_OUTPUT_LENGTH) output.appendLine(line)
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

    companion object {
        private const val TAG = "PowerKeeperFix/Shizuku"
        private const val COMMAND_TIMEOUT_SECONDS = 120L
        private const val SETTINGS_COMMAND_TIMEOUT_SECONDS = 10L
        private const val GREEZER_COMMAND_TIMEOUT_SECONDS = 20L
        private const val USER_LIST_COMMAND_TIMEOUT_SECONDS = 10L
        private const val FCM_POLL_SECONDS = 2L
        private const val MAX_OUTPUT_LENGTH = 64_000
        private const val MAX_PENDING_LOG_CHARS = 128_000
        private const val SETTINGS_BINARY = "/system/bin/settings"
        private const val SETTINGS_USER = "0"
        private const val DUMPSYS_BINARY = "/system/bin/dumpsys"
        private const val PM_BINARY = "/system/bin/pm"
    }
}
