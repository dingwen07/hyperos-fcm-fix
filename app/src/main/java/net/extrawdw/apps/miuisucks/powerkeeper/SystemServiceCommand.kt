package net.extrawdw.apps.miuisucks.powerkeeper

/** A command handled by a named Android Binder system service. */
data class SystemServiceCommand(
    val serviceName: String,
    val arguments: List<String>,
) {
    init {
        require(serviceName.isNotBlank()) { "System service name is blank" }
        require(arguments.none { it.contains('\u0000') }) { "Command argument contains NUL" }
    }

    val diagnosticName: String
        get() = "cmd $serviceName ${arguments.joinToString(" ")}".trim()
}

object SystemServiceCommands {
    fun settings(vararg arguments: String) = command(SETTINGS_SERVICE, *arguments)

    fun packageManager(vararg arguments: String) = command(PACKAGE_SERVICE, *arguments)

    fun activity(vararg arguments: String) = command(ACTIVITY_SERVICE, *arguments)

    fun appOps(vararg arguments: String) = command(APP_OPS_SERVICE, *arguments)

    fun deviceIdle(vararg arguments: String) = command(DEVICE_IDLE_SERVICE, *arguments)

    private fun command(serviceName: String, vararg arguments: String) =
        SystemServiceCommand(serviceName, arguments.toList())

    const val SETTINGS_SERVICE = "settings"
    const val PACKAGE_SERVICE = "package"
    const val ACTIVITY_SERVICE = "activity"
    const val APP_OPS_SERVICE = "appops"
    const val DEVICE_IDLE_SERVICE = "deviceidle"
    const val GREEZER_SERVICE = "greezer"
}
