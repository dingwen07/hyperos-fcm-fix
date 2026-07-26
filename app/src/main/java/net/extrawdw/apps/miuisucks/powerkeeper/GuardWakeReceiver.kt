package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GuardWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        if (!XiaomiCompatibility.check(context.applicationContext).supported) return

        val store = GuardSettingsStore(context.applicationContext)
        val settings = store.loadSettings()
        EnforcementScheduler.schedule(context.applicationContext, settings.intervalMinutes)
        // Always recreate the daemon Shizuku monitor after boot or an app update.
        // WorkManager retries if Shizuku has not started yet.
        EnforcementScheduler.recoverFcmNow(context.applicationContext)

        val lastRun = store.loadLastRun()
        val staleAfterMillis = settings.intervalMinutes * 60_000L
        if (lastRun == null || System.currentTimeMillis() - lastRun.timestampMillis >= staleAfterMillis) {
            EnforcementScheduler.runNow(context.applicationContext)
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
