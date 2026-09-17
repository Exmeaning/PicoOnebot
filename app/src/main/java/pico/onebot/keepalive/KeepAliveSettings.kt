package pico.onebot.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

object KeepAliveSettings {

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, PicoKeepAliveService::class.java)
        val enabled = try {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.let { component ->
                component.packageName == expected.packageName &&
                    component.className == expected.className
            } == true
        }
    }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val manager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            manager.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    fun openAccessibilitySettings(ctx: Context) {
        open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(ctx, "当前系统无需单独设置电池优化", Toast.LENGTH_SHORT).show()
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        )
        if (!open(ctx, direct, showError = false)) {
            open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openBackgroundSettings(ctx: Context) {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi" -> listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            "huawei", "honor" -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
            "oppo", "oneplus", "realme" -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ),
                ComponentName(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            )
            "vivo", "iqoo" -> listOf(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            )
            "samsung" -> listOf(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
            else -> emptyList()
        }
        for (component in candidates) {
            if (open(ctx, Intent().setComponent(component), showError = false)) return
        }
        open(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }

    private fun open(ctx: Context, intent: Intent, showError: Boolean = true): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(ctx.packageManager) == null) throw IllegalStateException("unsupported")
            ctx.startActivity(intent)
            true
        } catch (_: Throwable) {
            if (showError) Toast.makeText(ctx, "系统没有可用的设置页面", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
