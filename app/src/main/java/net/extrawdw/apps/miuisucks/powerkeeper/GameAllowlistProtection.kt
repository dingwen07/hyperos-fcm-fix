package net.extrawdw.apps.miuisucks.powerkeeper

/** Xiaomi's temporary game-predownload freezer exemptions, shared by package name. */
object GameAllowlist {
    const val SETTING_NAME = "com.miui.game.allowlist"

    fun value(rawValue: String?): String = rawValue
        ?.removeSuffix("\n")
        ?.removeSuffix("\r")
        ?.takeUnless { it == "null" }
        .orEmpty()

    fun containsGms(rawValue: String?): Boolean =
        MilletNoRestrictList.GMS_PACKAGE in value(rawValue).split('_')

    fun appendGms(rawValue: String?): String {
        val current = value(rawValue)
        if (containsGms(current)) return current
        return if (current.isEmpty()) MilletNoRestrictList.GMS_PACKAGE
        else current + "_" + MilletNoRestrictList.GMS_PACKAGE
    }

    fun removeGms(rawValue: String?): String = value(rawValue).split('_')
        .filterNot { it == MilletNoRestrictList.GMS_PACKAGE }
        .joinToString("_")

    /** A same-value PUT does not notify Settings observers on the inspected ROM. */
    fun signalReload(current: String): String {
        require(containsGms(current)) { "Reload requires GMS membership" }
        // Xiaomi's Java split("_") ignores trailing empty fields. Change the value, not membership.
        return if (current.endsWith('_')) current.dropLast(1) else current + "_"
    }
}

internal data class GameProtectionResult(
    val report: String,
    val actionTaken: Boolean = false,
    val failed: Boolean = false,
)

/** Serializes the watchdog and Binder paths without waking the device or replacing other entries. */
internal class GameAllowlistProtection(
    private val readValue: () -> Result<String>,
    private val writeValue: (String) -> Result<Unit>,
    private val thawGms: () -> Result<Unit>,
    private val reconnectGms: () -> Result<Unit>,
) {
    private val lock = Any()
    @Volatile
    var enabled = false
        private set
    private var reloadPending = false
    private var recoveryPending = false

    fun configure(enabled: Boolean, removeGmsOnDisable: Boolean): GameProtectionResult = synchronized(lock) {
        if (enabled && !this.enabled) reloadPending = true
        this.enabled = enabled
        if (enabled) return@synchronized maintainLocked()
        reloadPending = false
        recoveryPending = false
        if (removeGmsOnDisable) removeOwnedEntry() else GameProtectionResult("$LABEL: disabled")
    }

    fun maintain(): GameProtectionResult = synchronized(lock) {
        if (enabled) maintainLocked() else GameProtectionResult("$LABEL: disabled")
    }

    fun prepareReconnect(): Result<Unit> = synchronized(lock) {
        if (enabled) thawGms() else Result.success(Unit)
    }

    private fun maintainLocked(): GameProtectionResult {
        val read = readValue()
        val raw = read.getOrElse { return failure("read", it) }
        val current = GameAllowlist.value(raw)
        val missing = !GameAllowlist.containsGms(current)
        if (missing || reloadPending) {
            val candidate = if (missing) GameAllowlist.appendGms(current) else GameAllowlist.signalReload(current)
            val stable = readValue().getOrElse { return failure("stability read", it) }
            if (stable != raw) return GameProtectionResult("$LABEL: changed concurrently; deferred")
            writeValue(candidate).getOrElse { return failure("write", it) }
            val verified = readValue().getOrElse { return failure("verification read", it) }
            if (verified != candidate) {
                return failure("verify updated value", IllegalStateException("value differs after write; retry needed"))
            }
            reloadPending = false
            recoveryPending = true
        }
        if (recoveryPending) {
            thawGms().getOrElse { return failure("owner GMS thaw", it) }
            reconnectGms().getOrElse { return failure("owner GMS reconnect", it) }
            recoveryPending = false
            return GameProtectionResult("$LABEL: GMS exempt; owner thaw/reconnect broadcasts sent", actionTaken = true)
        }
        return GameProtectionResult("$LABEL: GMS present")
    }

    private fun removeOwnedEntry(): GameProtectionResult {
        val raw = readValue().getOrElse { return failure("cleanup read", it) }
        val current = GameAllowlist.value(raw)
        val candidate = GameAllowlist.removeGms(current)
        // A deleted key can leave the framework's cached membership intact. An empty PUT clears it.
        val missingKey = raw == "null"
        if (candidate == current && !missingKey) return GameProtectionResult("$LABEL: disabled; GMS absent")
        val stable = readValue().getOrElse { return failure("cleanup stability read", it) }
        if (stable != raw) {
            return failure("cleanup", IllegalStateException("allowlist changed concurrently; retry needed"))
        }
        writeValue(candidate).getOrElse { return failure("cleanup write", it) }
        val verified = readValue().getOrElse { return failure("cleanup verification", it) }
        if (GameAllowlist.containsGms(verified) || verified == "null") {
            return failure("cleanup verification", IllegalStateException("entry removal was not confirmed"))
        }
        return GameProtectionResult("$LABEL: disabled; removed app-added GMS, preserved other entries", actionTaken = true)
    }

    private fun failure(action: String, error: Throwable) = GameProtectionResult(
        "$LABEL: FAILED: $action (${error.message ?: error.javaClass.simpleName})",
        failed = true,
    )

    private companion object {
        const val LABEL = "HyperOS 4 nighttime protection"
    }
}
