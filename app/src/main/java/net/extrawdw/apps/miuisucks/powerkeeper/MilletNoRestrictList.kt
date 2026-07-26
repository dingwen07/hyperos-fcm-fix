package net.extrawdw.apps.miuisucks.powerkeeper

object MilletNoRestrictList {
    const val SETTING_NAME = "MILLET_NO_RESTRICT_APP"
    const val GMS_PACKAGE = "com.google.android.gms"

    fun parse(rawValue: String?): List<String> {
        if (rawValue.isNullOrBlank() || rawValue.trim() == "null") return emptyList()

        val seen = linkedSetOf<String>()
        rawValue.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(seen::add)
        return seen.toList()
    }

    fun appendGms(rawValue: String?): List<String> = parse(rawValue).let { existing ->
        if (GMS_PACKAGE in existing) existing else existing + GMS_PACKAGE
    }

    fun serialize(packages: List<String>): String = packages.joinToString(", ")
}
