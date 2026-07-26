package net.extrawdw.apps.miuisucks.powerkeeper

import java.nio.charset.StandardCharsets
import java.util.Base64

data class AndroidUserSelection(
    val userId: Int,
    val name: String,
    val enabled: Boolean,
)

data class DiscoveredAndroidUser(
    val userId: Int,
    val name: String,
)

object AndroidUserSelections {
    val DEFAULT_ENABLED_USER_IDS = setOf(0, 999)

    private val userInfoPattern = Regex("""UserInfo\{(\d+):(.*):[0-9a-fA-F]+\}""")

    fun parsePmListUsers(output: String): List<DiscoveredAndroidUser> = output
        .lineSequence()
        .mapNotNull { line ->
            val match = userInfoPattern.find(line) ?: return@mapNotNull null
            val userId = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val parsedName = match.groupValues[2]
            val name = parsedName.takeUnless { it.isBlank() || it == "null" } ?: "User $userId"
            DiscoveredAndroidUser(userId, name)
        }
        .distinctBy(DiscoveredAndroidUser::userId)
        .sortedBy(DiscoveredAndroidUser::userId)
        .toList()

    fun merge(
        discovered: List<DiscoveredAndroidUser>,
        persisted: List<AndroidUserSelection>,
    ): List<AndroidUserSelection> {
        val persistedById = persisted.associateBy(AndroidUserSelection::userId)
        return discovered
            .distinctBy(DiscoveredAndroidUser::userId)
            .sortedBy(DiscoveredAndroidUser::userId)
            .map { user ->
                AndroidUserSelection(
                    userId = user.userId,
                    name = user.name,
                    enabled = persistedById[user.userId]?.enabled
                        ?: (user.userId in DEFAULT_ENABLED_USER_IDS),
                )
            }
    }

    fun encode(users: List<AndroidUserSelection>): String = users
        .distinctBy(AndroidUserSelection::userId)
        .sortedBy(AndroidUserSelection::userId)
        .joinToString("\n") { user ->
            val encodedName = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(user.name.toByteArray(StandardCharsets.UTF_8))
            "${user.userId}|${if (user.enabled) 1 else 0}|$encodedName"
        }

    fun decode(value: String?): List<AndroidUserSelection> {
        if (value.isNullOrBlank()) return emptyList()
        return value.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val userId = parts[0].toIntOrNull() ?: return@mapNotNull null
                val enabled = when (parts[1]) {
                    "1" -> true
                    "0" -> false
                    else -> return@mapNotNull null
                }
                val name = runCatching {
                    String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)
                }.getOrNull() ?: return@mapNotNull null
                AndroidUserSelection(userId, name.ifBlank { "User $userId" }, enabled)
            }
            .distinctBy(AndroidUserSelection::userId)
            .sortedBy(AndroidUserSelection::userId)
            .toList()
    }
}
