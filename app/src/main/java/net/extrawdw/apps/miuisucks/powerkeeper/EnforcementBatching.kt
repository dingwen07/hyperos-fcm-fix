package net.extrawdw.apps.miuisucks.powerkeeper

object EnforcementBatching {
    const val MAX_POLICIES_PER_BATCH = 16

    fun split(policies: Collection<AppPolicy>): List<List<AppPolicy>> =
        policies
            .filter(AppPolicy::appEnabled)
            .sortedBy(AppPolicy::packageName)
            .chunked(MAX_POLICIES_PER_BATCH)
}
