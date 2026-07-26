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
        if (!compatibility.supported) {
            store.saveLastRun(false, "Unsupported device: ${compatibility.reason}")
            return Result.failure()
        }
        val settings = store.loadSettings()

        if (!isShizukuAvailable()) {
            store.saveLastRun(false, "Shizuku is not running. The periodic worker will try again.")
            return Result.retry()
        }
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            store.saveLastRun(false, "Shizuku permission has not been granted. Open the app to authorize it.")
            return Result.success()
        }

        return try {
            val report = PrivilegedServiceClient.enforce(
                settings.wechatPolicy,
                store.loadEnabledAndroidUserIds(),
            )
            val succeeded = !report.contains("FAILED:") && !report.contains("exit_code=")
            store.saveLastRun(succeeded, report)
            Result.success()
        } catch (throwable: Throwable) {
            store.saveLastRun(false, "Enforcement failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            Result.retry()
        }
    }

    private fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)
}
