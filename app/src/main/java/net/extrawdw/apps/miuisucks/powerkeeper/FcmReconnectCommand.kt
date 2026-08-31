package net.extrawdw.apps.miuisucks.powerkeeper

internal object FcmReconnectCommand {
    const val ACTION = "com.google.android.intent.action.GCM_RECONNECT"

    fun build(userId: String): List<String> = listOf(
        "/system/bin/am",
        "broadcast",
        "--user",
        userId,
        "-a",
        ACTION,
        "-p",
        MilletNoRestrictList.GMS_PACKAGE,
    )
}
