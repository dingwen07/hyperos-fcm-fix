package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

private const val GRANT_RUNTIME_PERMISSIONS = "android.permission.GRANT_RUNTIME_PERMISSIONS"

/** Uses the same permission detection as Shizuku */
internal fun isAdbPermissionLimited(): Boolean =
    Shizuku.checkRemotePermission(GRANT_RUNTIME_PERMISSIONS) != PackageManager.PERMISSION_GRANTED

private fun openDeveloperOptions(context: Context) {
    val developerOptionsOpened = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }.isSuccess
    if (!developerOptionsOpened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

@Composable
internal fun AdbPermissionWarningCard() {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.adb_permission_limited_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.adb_permission_limited_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { openDeveloperOptions(context) }) {
                Text(stringResource(R.string.open_developer_options))
            }
        }
    }
}
