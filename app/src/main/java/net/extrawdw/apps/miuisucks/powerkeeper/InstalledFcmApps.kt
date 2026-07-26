package net.extrawdw.apps.miuisucks.powerkeeper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

data class InstalledFcmApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val installed: Boolean,
)

object InstalledFcmApps {
    private const val ICON_SIZE_PX = 128

    @SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION")
    fun load(context: Context, savedPackages: Collection<String>): List<InstalledFcmApp> {
        val pm = context.packageManager
        val discovered = pm.queryBroadcastReceivers(
            Intent(AurogonConfig.FCM_RECEIVE_ACTION),
            android.content.pm.PackageManager.MATCH_ALL,
        ).mapNotNull { it.activityInfo?.packageName }.toMutableSet()
        discovered += savedPackages
        discovered -= context.packageName
        discovered -= ANDROID_SYSTEM_PACKAGE

        return discovered.map { packageName ->
            runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                InstalledFcmApp(
                    packageName = packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = runCatching { pm.getApplicationIcon(info).toImageBitmap(ICON_SIZE_PX) }.getOrNull(),
                    installed = true,
                )
            }.getOrElse {
                InstalledFcmApp(packageName, packageName, null, installed = false)
            }
        }
    }

    private const val ANDROID_SYSTEM_PACKAGE = "android"

    private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
        (this as? BitmapDrawable)?.bitmap?.let { return it.scale(sizePx, sizePx).asImageBitmap() }
        val bitmap = createBitmap(sizePx, sizePx)
        setBounds(0, 0, sizePx, sizePx)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}
