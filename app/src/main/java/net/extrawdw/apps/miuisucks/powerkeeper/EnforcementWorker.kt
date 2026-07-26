package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import rikka.shizuku.Shizuku

class EnforcementWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val store = GuardSettingsStore(applicationContext)
        val compatibility = XiaomiCompatibility.check(applicationContext)
        AppLog.i("Worker/Enforcement", "start id=$id attempt=$runAttemptCount supported=${compatibility.supported}")
        if (!compatibility.supported) {
            AppLog.e("Worker/Enforcement", "unsupported: ${compatibility.reason}")
            store.saveLastRun(false, "Unsupported device: ${compatibility.reason}")
            return Result.failure()
        }
        val settings = store.loadSettings()
        if (!GuardSettingsStore.isPeriodicEnforcementEnabled(settings.intervalMinutes)) {
            AppLog.i("Worker/Enforcement", "periodic enforcement disabled; skipping queued work")
            return Result.success()
        }
        val targetUserIds = store.loadEnabledAndroidUserIds()
        AppLog.i(
            "Worker/Enforcement",
            "settings aurogon=${settings.aurogonEnabledPackages.size} periodic=${settings.appPolicies.values.count(AppPolicy::periodicEnforcement)} users=${targetUserIds.joinToString()}",
        )

        if (!isShizukuAvailable()) {
            AppLog.w("Worker/Enforcement", "Shizuku unavailable; retrying")
            store.saveLastRun(false, "Shizuku is not running. The periodic worker will try again.")
            return Result.retry()
        }
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            AppLog.w("Worker/Enforcement", "Shizuku permission missing; finishing without retry")
            store.saveLastRun(false, "Shizuku permission has not been granted. Open the app to authorize it.")
            return Result.success()
        }

        return try {
            val report = PrivilegedServiceClient.enforce(
                settings.aurogonEnabledPackages,
                settings.aurogonManagedPackages,
                settings.appPolicies.values.filter(AppPolicy::periodicEnforcement),
                targetUserIds,
                trigger = "background:periodic-enforcement",
            )
            val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
            store.saveLastRun(succeeded, report)
            AppLog.i("Worker/Enforcement", "finish succeeded=$succeeded report=$report")
            Result.success()
        } catch (throwable: Throwable) {
            AppLog.e("Worker/Enforcement", "failed; retrying", throwable)
            store.saveLastRun(false, "Enforcement failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            Result.retry()
        }
    }

    private fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)
}
