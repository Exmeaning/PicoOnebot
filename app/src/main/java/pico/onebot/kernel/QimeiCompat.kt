package pico.onebot.kernel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.util.Base64
import android.util.Log
import com.tencent.mobileqq.dt.app.Dtc
import com.tencent.mobileqq.msf.core.o
import com.tencent.qimei.sdk.QimeiSDK
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Repairs identity fields expected by the QQ 9.0.7 MSF login stack. */
object QimeiCompat {
    private const val MAIN_APP_KEY = "0AND05WGZE38P5II"
    private const val QQ_PACKAGE = "com.tencent.qqlite"
    private const val PREFS_NAME = "pico_identity_compat"
    private const val PREF_QIMEI36 = "qimei36"
    private val signatureInstalled = AtomicBoolean()
    private val appSettingInitialized = AtomicBoolean()
    private val qimeiInitialized = AtomicBoolean()
    private val msfStarted = AtomicBoolean()

    // DER certificate from the Tencent-signed source APK (SHA-256 ea6e97ad...84ac7a38).
    private const val TENCENT_CERT =
        "MIICUzCCAbygAwIBAgIES7sDYTANBgkqhkiG9w0BAQUFADBtMQ4wDAYDVQQGEwVDaGluYTEP" +
            "MA0GA1UECAwG5YyX5LqsMQ8wDQYDVQQHDAbljJfkuqwxDzANBgNVBAoMBuiFvuiurzEb" +
            "MBkGA1UECwwS5peg57q/5Lia5Yqh57O757ufMQswCQYDVQQDEwJRUTAgFw0xMDA0MDYw" +
            "OTQ4MTdaGA8yMjg0MDEyMDA5NDgxN1owbTEOMAwGA1UEBhMFQ2hpbmExDzANBgNVBAgM" +
            "BuWMl+S6rDEPMA0GA1UEBwwG5YyX5LqsMQ8wDQYDVQQKDAbohb7orq8xGzAZBgNVBAsM" +
            "EuaXoOe6v+S4muWKoeezu+e7nzELMAkGA1UEAxMCUVEwgZ8wDQYJKoZIhvcNAQEBBQAD" +
            "gY0AMIGJAoGBAKFel1Yhb2lMWRXgtSkJUlQ2fE5k+u/weuE0iNlGYVpY3cMaQV9xfQGe" +
            "3G0wuWA9Pip7PeCrfgz1Lf7jk3O8Ry+plwJ9eY1Z+B1SWmns8Vbohf0eJ5CSQ4ayIwzJ" +
            "Djt63JVgPdz0xAvccvItsPIWqZw3HTv4nLpleMYGmeig1TaVAgMBAAEwDQYJKoZIhvcN" +
            "AQEFBQADgYEAlKm4DoBpFkXdQtZhF3WoVfcbzU13y2Co4pQEA1peALIbzF1KViSCEmvZ" +
            "G2sOUHCTd86574wu/RLMixav2aFZ81C7JwsUIE/wZdhDgycgcC4otBSR+8OiBfXy9CUm" +
            "1n8XYU2Kl03mSHsshm7+3jtOSaD5FrqjwTNv0u4bFillIEk="

    fun startMsf(ctx: Context) {
        prepareEarly(ctx, true)
        if (!msfStarted.compareAndSet(false, true)) return
        if (!qimeiInitialized.get()) {
            Thread({ initializeMsfCache(ctx) }, "pico-qimei-compat").apply { isDaemon = true }.start()
        }
    }

    fun prepareEarly(ctx: Context, isMsf: Boolean) {
        installSignatureCompat(ctx)
        if (isMsf) {
            initializeAppSettingFlags()
            restorePersistedQimei(ctx)
        }
    }

    fun installSignatureCompat(ctx: Context) {
        if (!signatureInstalled.compareAndSet(false, true)) return
        try {
            val packageManager = ctx.packageManager
            val pmField = findField(packageManager.javaClass, "mPM")
            val original = pmField.get(packageManager)
            val interfaceClass = Class.forName("android.content.pm.IPackageManager")
            val tencentSignature = Signature(Base64.decode(TENCENT_CERT, Base64.DEFAULT))
            val proxy = Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass)) {
                    _, method, args ->
                val result = try {
                    method.invoke(original, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
                if (result is PackageInfo && result.packageName == QQ_PACKAGE) {
                    @Suppress("DEPRECATION")
                    result.signatures = arrayOf(tencentSignature)
                }
                result
            }

            pmField.set(packageManager, proxy)
            val activityThread = Class.forName("android.app.ActivityThread")
            val staticPmField = findField(activityThread, "sPackageManager")
            if (staticPmField.get(null) === original) staticPmField.set(null, proxy)
            Log.i("PicoOB", "Package signature compatibility installed")
        } catch (t: Throwable) {
            signatureInstalled.set(false)
            Log.e("PicoOB", "Package signature compatibility failed", t)
        }
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }

    private fun initializeAppSettingFlags() {
        if (!appSettingInitialized.compareAndSet(false, true)) return
        try {
            val appSetting = Class.forName("com.tencent.common.config.AppSetting")
            appSetting.getField("isDebugVersion").setBoolean(null, false)
            appSetting.getField("isPublicVersion").setBoolean(null, true)
            Log.i("PicoOB", "MSF FEKit AppSetting flags initialized")
        } catch (t: Throwable) {
            appSettingInitialized.set(false)
            Log.e("PicoOB", "MSF FEKit AppSetting compatibility failed", t)
        }
    }

    private fun restorePersistedQimei(ctx: Context): Boolean {
        if (qimeiInitialized.get()) return true
        return try {
            val qimei36 = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_QIMEI36, null)
            if (!isValidQimei36(qimei36)) return false
            if (applyQimei(qimei36!!)) {
                Log.i("PicoOB", "MSF QIMEI caches restored synchronously from PicoOnebot persistence")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.e("PicoOB", "MSF persisted QIMEI restore failed", t)
            false
        }
    }

    private fun initializeMsfCache(ctx: Context) {
        repeat(40) {
            if (qimeiInitialized.get()) return
            try {
                val qimei36 = QimeiSDK.getInstance(MAIN_APP_KEY).qimei?.qimei36
                if (isValidQimei36(qimei36) && applyQimei(qimei36!!)) {
                    val saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_QIMEI36, qimei36).commit()
                    Log.i(
                        "PicoOB",
                        "MSF and FEKit QIMEI caches initialized from main QQ device identity; persisted=$saved"
                    )
                    return
                }
            } catch (t: Throwable) {
                if (it == 39) Log.e("PicoOB", "MSF QIMEI compatibility failed", t)
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
        }
        Log.w("PicoOB", "MSF QIMEI compatibility timed out waiting for main QIMEI")
    }

    private fun applyQimei(qimei36: String): Boolean {
        if (!qimeiInitialized.compareAndSet(false, true)) return true
        return try {
            Dtc.setQ36(qimei36)
            o::class.java.getDeclaredMethod("a", String::class.java).invoke(null, qimei36)
            true
        } catch (t: Throwable) {
            qimeiInitialized.set(false)
            Log.e("PicoOB", "MSF QIMEI cache assignment failed", t)
            false
        }
    }

    private fun isValidQimei36(value: String?): Boolean = value != null && value.length == 36
}
