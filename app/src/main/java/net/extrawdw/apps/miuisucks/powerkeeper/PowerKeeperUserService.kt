package net.extrawdw.apps.miuisucks.powerkeeper

import android.os.Process
import android.util.Log
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

    override fun enforce(wechatPolicy: Int, targetUserIds: IntArray): String {
        val policy = WechatPolicy.fromCode(wechatPolicy)
        val script = EnforcementScript.build(policy, BuildConfig.APPLICATION_ID, targetUserIds.toList())
        return buildString {
            appendLine(startFcmProtection())
            append(runScript(script))
        }.trim()
    }

    override fun destroy() {
        Log.i(TAG, "Destroying Shizuku user service")
        fcmPolling?.cancel(false)
        fcmExecutor.shutdownNow()
        exitProcess(0)
    }

    override fun startFcmProtection(): String {
        startFcmPolling()

        return buildString {
            appendLine("FCM protection")
            appendLine(runGreezerCommand("Disable explicit GMS limiter", "IM", "GMS", "disable"))
            appendLine(runGreezerCommand("Restore ordinary GMS allowlist", "LM", "add", MilletNoRestrictList.GMS_PACKAGE))
            appendLine(ensureGmsNoRestrict())
            append("FCM setting monitor: active (${FCM_POLL_SECONDS}s Shizuku poll)")
        }
    }

    override fun getMilletNoRestrictValue(): String {
        val read = readMilletNoRestrict()
        return if (read.succeeded) {
            read.output.ifBlank { "(empty)" }
        } else {
            "FAILED: could not read ${MilletNoRestrictList.SETTING_NAME} (${read.summary})"
        }
    }

    override fun listAndroidUsers(): String {
        val result = runCommand(
            listOf(PM_BINARY, "list", "users"),
            USER_LIST_COMMAND_TIMEOUT_SECONDS,
        )
        return if (result.succeeded) {
            result.output
        } else {
            "FAILED: could not list Android users (${result.summary})"
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
                        .onFailure { error -> Log.w(TAG, "FCM repair poll failed", error) }
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
        } else if (result.contains("appended GMS")) {
            Log.i(TAG, "FCM repair ($reason): $result")
        }
    }

    private fun ensureGmsNoRestrict(): String = synchronized(fcmRepairLock) {
        val initialRead = readMilletNoRestrict()
        if (!initialRead.succeeded) {
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
            return@synchronized "MILLET no-restrict: FAILED to append GMS (${write.summary})"
        }

        val verification = readMilletNoRestrict()
        val verifiedEntries = if (verification.succeeded) {
            MilletNoRestrictList.parse(verification.output)
        } else {
            emptyList()
        }
        if (MilletNoRestrictList.GMS_PACKAGE !in verifiedEntries) {
            return@synchronized "MILLET no-restrict: FAILED verification (${verification.summary})"
        }

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
        }.trim()
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
        private const val TAG = "PowerKeeperUserService"
        private const val COMMAND_TIMEOUT_SECONDS = 120L
        private const val SETTINGS_COMMAND_TIMEOUT_SECONDS = 10L
        private const val GREEZER_COMMAND_TIMEOUT_SECONDS = 20L
        private const val USER_LIST_COMMAND_TIMEOUT_SECONDS = 10L
        private const val FCM_POLL_SECONDS = 2L
        private const val MAX_OUTPUT_LENGTH = 64_000
        private const val SETTINGS_BINARY = "/system/bin/settings"
        private const val SETTINGS_USER = "0"
        private const val DUMPSYS_BINARY = "/system/bin/dumpsys"
        private const val PM_BINARY = "/system/bin/pm"
    }
}
