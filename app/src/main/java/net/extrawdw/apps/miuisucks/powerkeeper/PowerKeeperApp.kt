package net.extrawdw.apps.miuisucks.powerkeeper

import android.app.Application

class PowerKeeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(
            "App",
            "onCreate version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) pid=${android.os.Process.myPid()}",
        )
    }
}
