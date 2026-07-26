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
        if (!XiaomiCompatibility.check(applicationContext).supported) return Result.failure()
        if (!isShizukuAvailable()) return Result.retry()

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) return Result.success()

        return runCatching { PrivilegedServiceClient.startFcmProtection() }
            .fold(
                onSuccess = { report ->
                    if (report.contains("FAILED")) Result.retry() else Result.success()
                },
                onFailure = { Result.retry() },
            )
    }

    private fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    }.getOrDefault(false)
}
