package net.extrawdw.apps.miuisucks.powerkeeper

internal object GmsUidCommand {
    fun build(userId: String): List<String> = listOf(
        "/system/bin/cmd",
        "package",
        "list",
        "packages",
        "--user",
        userId,
        "-U",
        MilletNoRestrictList.GMS_PACKAGE,
    )

    fun parse(output: String): Int? = output.lineSequence()
        .map { line -> line.trim().split(WHITESPACE) }
        .firstOrNull { fields -> PACKAGE_FIELD in fields }
        ?.firstNotNullOfOrNull { field ->
            field.takeIf { it.startsWith(UID_PREFIX) }
                ?.removePrefix(UID_PREFIX)
                ?.toIntOrNull()
        }

    private const val PACKAGE_FIELD = "package:${MilletNoRestrictList.GMS_PACKAGE}"
    private const val UID_PREFIX = "uid:"
    private val WHITESPACE = Regex("\\s+")
}
