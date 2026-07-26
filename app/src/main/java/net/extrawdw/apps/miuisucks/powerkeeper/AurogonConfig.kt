package net.extrawdw.apps.miuisucks.powerkeeper

object AurogonConfig {
    const val SETTING_NAME = "aurogon_enable"
    const val FCM_RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE"

    private const val BROADCAST_PREFIX = "broadcastctrl:"
    private const val MARKER_ACTION = "__MIUI_POWERKEEPER_AUROGON_V1__"
    private const val DISABLED_ACTION = "__MIUI_POWERKEEPER_DISABLED__"
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    /** Merges this app's complete managed overlay into Xiaomi's shared cloud setting. */
    fun merge(
        rawValue: String?,
        desiredPackages: Collection<String>,
        managedPackages: Collection<String>,
        managerPackage: String,
    ): String {
        require(PACKAGE_NAME.matches(managerPackage)) { "Invalid manager package" }
        require(desiredPackages.all(PACKAGE_NAME::matches)) { "Invalid target package" }
        require(managedPackages.all(PACKAGE_NAME::matches)) { "Invalid managed package" }

        val raw = rawValue.normalizedSettingValue()
        val sections = splitSections(raw)
        val managedSections = sections.filter { it.isManagedSection(managerPackage) }
        val baseSections = sections.filterNot { it.isManagedSection(managerPackage) }
        val base = parseBroadcastState(baseSections)
        val prior = parseManagedState(managedSections, managerPackage)
        val desired = desiredPackages.toSortedSet()

        // Xiaomi's false flag already thaws every frozen broadcast target. Do not override it.
        if (!base.enabled) return joinSections(baseSections)

        val enabled = desired
        val disabled = (prior.known + managedPackages) - desired

        val tokens = mutableListOf("$managerPackage/$MARKER_ACTION")
        enabled.forEach { packageName ->
            val baseAction = base.packageActions[packageName]
            if (baseAction.allowsFcm()) return@forEach
            val action = listOfNotNull(baseAction?.takeIf(String::isNotBlank), FCM_RECEIVE_ACTION)
                .joinToString(",")
            tokens += "$packageName/$action"
        }
        disabled.sorted().forEach { packageName ->
            if (packageName !in base.packageActions) tokens += "$packageName/$DISABLED_ACTION"
        }

        // A marker-only section carries no state and would unnecessarily become the final cloud flag.
        val managed = if (tokens.size == 1) null else "broadcastctrl:true#${tokens.joinToString("#")}"
        return joinSections(if (managed == null) baseSections else baseSections + managed)
    }

    fun rulesEffective(rawValue: String?, packages: Collection<String>): Boolean {
        val state = parseBroadcastState(splitSections(rawValue.normalizedSettingValue()))
        if (!state.enabled) return true
        return packages.all { packageName ->
            state.packageActions[packageName].allowsFcm()
        }
    }

    private fun parseManagedState(sections: List<String>, managerPackage: String): ManagedState {
        val enabled = linkedSetOf<String>()
        val disabled = linkedSetOf<String>()
        sections.forEach { section ->
            section.tokens().drop(1).forEach { token ->
                val (packageName, action) = token.packageAction() ?: return@forEach
                if (packageName == managerPackage) return@forEach
                if (!PACKAGE_NAME.matches(packageName)) return@forEach
                when {
                    action == DISABLED_ACTION -> {
                        disabled += packageName
                        enabled -= packageName
                    }
                    action.allowsFcm() -> {
                        enabled += packageName
                        disabled -= packageName
                    }
                }
            }
        }
        return ManagedState(enabled, disabled)
    }

    private fun parseBroadcastState(sections: List<String>): BroadcastState {
        var enabled = true
        val packageActions = linkedMapOf<String, String>()
        sections.forEach { section ->
            val tokens = section.tokens()
            val header = tokens.firstOrNull() ?: return@forEach
            if (!header.startsWith(BROADCAST_PREFIX)) return@forEach
            enabled = when {
                header.contains("false") -> false
                header.contains("true") -> true
                else -> enabled
            }
            tokens.drop(1).forEach { token ->
                val (packageName, action) = token.packageAction() ?: return@forEach
                packageActions[packageName] = action
            }
        }
        return BroadcastState(enabled, packageActions)
    }

    private fun String.isManagedSection(managerPackage: String): Boolean {
        val tokens = tokens()
        if (tokens.firstOrNull()?.startsWith(BROADCAST_PREFIX) != true) return false
        return tokens.drop(1).any { token ->
            token.packageAction() == (managerPackage to MARKER_ACTION)
        }
    }

    private fun String.tokens(): List<String> = split('#')

    private fun String.packageAction(): Pair<String, String>? {
        val slash = indexOf('/')
        if (slash <= 0 || slash == lastIndex) return null
        return substring(0, slash) to substring(slash + 1)
    }

    private fun String?.allowsFcm(): Boolean =
        this == "ALL" || this?.split(',')?.any { it == FCM_RECEIVE_ACTION } == true

    private fun String?.normalizedSettingValue(): String =
        this?.removeSuffix("\n")?.removeSuffix("\r")?.takeUnless { it == "null" }.orEmpty()

    private fun splitSections(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        value.forEachIndexed { index, char ->
            if (char == ';') {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        result += value.substring(start)
        return result
    }

    private fun joinSections(sections: List<String>): String = sections.joinToString(";")

    private data class BroadcastState(
        val enabled: Boolean,
        val packageActions: Map<String, String>,
    )

    private data class ManagedState(
        val enabled: Set<String>,
        val disabled: Set<String>,
    ) {
        val known: Set<String>
            get() = enabled + disabled
    }
}
