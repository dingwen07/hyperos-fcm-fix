package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object EnforcementScheduler {
    fun schedule(context: Context, intervalMinutes: Long) {
        val normalizedInterval = intervalMinutes.coerceAtLeast(GuardSettingsStore.MINIMUM_INTERVAL_MINUTES)
        AppLog.i("Scheduler", "schedule enforcement=${normalizedInterval}m fcmRecovery=${GuardSettingsStore.MINIMUM_INTERVAL_MINUTES}m")
        val enforcementRequest = PeriodicWorkRequestBuilder<EnforcementWorker>(
            normalizedInterval,
            TimeUnit.MINUTES,
        )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            enforcementRequest,
        )

        val fcmRecoveryRequest = PeriodicWorkRequestBuilder<FcmRecoveryWorker>(
            GuardSettingsStore.MINIMUM_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FCM_RECOVERY_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            fcmRecoveryRequest,
        )
    }

    fun runNow(context: Context) {
        AppLog.i("Scheduler", "enqueue immediate enforcement")
        val request = OneTimeWorkRequestBuilder<EnforcementWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun recoverFcmNow(context: Context) {
        AppLog.i("Scheduler", "enqueue immediate FCM recovery")
        val request = OneTimeWorkRequestBuilder<FcmRecoveryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FCM_RECOVERY_NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private const val PERIODIC_WORK_NAME = "powerkeeper_guard_periodic"
    private const val IMMEDIATE_WORK_NAME = "powerkeeper_guard_immediate"
    private const val FCM_RECOVERY_WORK_NAME = "fcm_protection_recovery_periodic"
    private const val FCM_RECOVERY_NOW_WORK_NAME = "fcm_protection_recovery_immediate"
    private const val WORK_TAG = "powerkeeper_guard"
}
