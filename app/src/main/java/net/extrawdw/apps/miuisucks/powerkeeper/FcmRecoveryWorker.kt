package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import rikka.shizuku.Shizuku

class FcmRecoveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val compatibility = XiaomiCompatibility.check(applicationContext)
        AppLog.i("Worker/FCM", "start id=$id attempt=$runAttemptCount supported=${compatibility.supported}")
        if (!compatibility.supported) {
            AppLog.e("Worker/FCM", "unsupported: ${compatibility.reason}")
            return Result.failure()
        }
        if (!isShizukuAvailable()) {
            AppLog.w("Worker/FCM", "Shizuku unavailable; retrying")
            return Result.retry()
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            AppLog.w("Worker/FCM", "Shizuku permission missing; finishing without retry")
            return Result.success()
        }

        val settings = GuardSettingsStore(applicationContext).loadSettings()
        return runCatching {
            PrivilegedServiceClient.startFcmProtection(
                settings.aurogonEnabledPackages,
                settings.aurogonManagedPackages,
                "background:fcm-recovery",
            )
        }
            .fold(
                onSuccess = { report ->
                    val failed = report.contains("FAILED")
                    AppLog.i("Worker/FCM", "finish failed=$failed report=$report")
                    if (failed) Result.retry() else Result.success()
                },
                onFailure = { error ->
                    AppLog.e("Worker/FCM", "failed; retrying", error)
                    Result.retry()
                },
            )
    }

    private fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)
}
