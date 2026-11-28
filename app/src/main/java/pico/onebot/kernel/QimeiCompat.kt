package pico.onebot.kernel

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
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
    private const val FAST_POLL_ATTEMPTS = 20
    private const val FAST_POLL_DELAY_MS = 250L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val MAX_COLD_ATTEMPTS = 120
    private const val MAX_REFRESH_ATTEMPTS = 28

    private val signatureInstalled = AtomicBoolean()
    private val appSettingInitialized = AtomicBoolean()
    private val qimeiInitialized = AtomicBoolean()
    private val msfStarted = AtomicBoolean()
    private val qimeiLock = Any()

    @Volatile
    private var appliedQimei: String? = null

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
        val appCtx = ctx.applicationContext ?: ctx
        try {
            Thread({ initializeMsfCache(appCtx) }, "pico-qimei-compat").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            msfStarted.set(false)
            Log.e("PicoOB", "MSF QIMEI thread start failed", t)
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
            if (original == null) {
                signatureInstalled.set(false)
                Log.w("PicoOB", "Package signature compatibility skipped: mPM is null")
                return
            }
            val interfaceClass = Class.forName("android.content.pm.IPackageManager")
            val tencentSignature = Signature(Base64.decode(TENCENT_CERT, Base64.DEFAULT))
            val spoofedSigningInfo: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Api28SigningInfo.buildSigningInfo(tencentSignature)
            } else {
                null
            }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Api28SigningInfo.applyToPackageInfo(result, spoofedSigningInfo, tencentSignature)
                    }
                }
                result
            }

            pmField.set(packageManager, proxy)
            try {
                val activityThread = Class.forName("android.app.ActivityThread")
                val staticPmField = findField(activityThread, "sPackageManager")
                if (staticPmField.get(null) === original) staticPmField.set(null, proxy)
            } catch (t: Throwable) {
                Log.w("PicoOB", "ActivityThread.sPackageManager patch failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            Log.i("PicoOB", "Package signature compatibility installed (signingInfo=${spoofedSigningInfo != null})")
        } catch (t: Throwable) {
            signatureInstalled.set(false)
            Log.e("PicoOB", "Package signature compatibility failed", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private object Api28SigningInfo {
        private val inPlaceFailureLogged = AtomicBoolean(false)

        fun buildSigningInfo(sig: Signature): Any? {
            val detailsClassNames = arrayOf(
                "android.content.pm.SigningDetails",
                "android.content.pm.PackageParser\$SigningDetails"
            )
            val sigArray = arrayOf(sig)
            val errors = ArrayList<String>(detailsClassNames.size)
            for (className in detailsClassNames) {
                try {
                    val detailsCls = Class.forName(className)
                    val details = constructSigningDetails(detailsCls, sigArray, errors) ?: continue
                    val ctor = SigningInfo::class.java.getDeclaredConstructor(detailsCls).apply { isAccessible = true }
                    val info = ctor.newInstance(details)
                    if (info.apkContentsSigners?.firstOrNull() == sig) {
                        return info
                    }
                    errors.add("$className: apkContentsSigners mismatch")
                } catch (t: Throwable) {
                    errors.add("$className: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            if (errors.isNotEmpty()) {
                Log.w("PicoOB", "SigningInfo synthesis failed (Hidden API restricted?): " + errors.joinToString("; "))
            }
            return null
        }

        fun applyToPackageInfo(info: PackageInfo, spoofed: Any?, sig: Signature) {
            if (spoofed is SigningInfo) {
                info.signingInfo = spoofed
            } else {
                info.signingInfo?.let { patchSigningInfoInPlace(it, sig) }
            }
        }

        private fun constructSigningDetails(
            detailsCls: Class<*>,
            sigArray: Array<Signature>,
            errors: MutableList<String>
        ): Any? {
            val sigArrayClass = Array<Signature>::class.java
            try {
                val ctor = detailsCls.getDeclaredConstructor(sigArrayClass, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                return ctor.newInstance(sigArray, 2)
            } catch (t: Throwable) {
                errors.add("${detailsCls.simpleName}(sig[],int): ${t.javaClass.simpleName}: ${t.message}")
            }
            try {
                val ctor = detailsCls.getDeclaredConstructor(
                    sigArrayClass,
                    Int::class.javaPrimitiveType,
                    sigArrayClass
                ).apply { isAccessible = true }
                return ctor.newInstance(sigArray, 2, null)
            } catch (t: Throwable) {
                errors.add("${detailsCls.simpleName}(sig[],int,sig[]): ${t.javaClass.simpleName}: ${t.message}")
            }
            return null
        }

        private fun patchSigningInfoInPlace(info: SigningInfo, sig: Signature) {
            try {
                val detailsField = findField(info.javaClass, "mSigningDetails")
                val details = detailsField.get(info) ?: return
                val sigArray = arrayOf(sig)
                var patchedSig = false
                for (name in arrayOf("mSignatures", "signatures")) {
                    try {
                        findField(details.javaClass, name).set(details, sigArray)
                        patchedSig = true
                        break
                    } catch (_: NoSuchFieldException) {
                    }
                }
                for (name in arrayOf("mPastSigningCertificates", "pastSigningCertificates")) {
                    try {
                        findField(details.javaClass, name).set(details, null)
                        break
                    } catch (_: NoSuchFieldException) {
                    }
                }
                if (!patchedSig && inPlaceFailureLogged.compareAndSet(false, true)) {
                    Log.w("PicoOB", "SigningInfo in-place patch: signature field not found on ${details.javaClass.name}")
                }
            } catch (t: Throwable) {
                if (inPlaceFailureLogged.compareAndSet(false, true)) {
                    Log.w("PicoOB", "SigningInfo in-place patch failed (Hidden API restricted?)", t)
                }
            }
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
            if (qimei36 == null || !isValidQimei36(qimei36)) return false
            if (applyQimei(qimei36)) {
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
        val hadPersisted = qimeiInitialized.get()
        val maxAttempts = if (hadPersisted) MAX_REFRESH_ATTEMPTS else MAX_COLD_ATTEMPTS
        var delayMs = FAST_POLL_DELAY_MS
        var errorLogs = 0
        try {
            for (attempt in 1..maxAttempts) {
                try {
                    val qimei36 = QimeiSDK.getInstance(MAIN_APP_KEY).qimei?.qimei36
                    if (qimei36 != null && isValidQimei36(qimei36)) {
                        val changed = appliedQimei != qimei36
                        if (applyQimei(qimei36)) {
                            if (changed) {
                                val saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(PREF_QIMEI36, qimei36).commit()
                                Log.i(
                                    "PicoOB",
                                    "MSF and FEKit QIMEI caches ${if (hadPersisted) "refreshed" else "initialized"} " +
                                        "from main QQ device identity; persisted=$saved"
                                )
                            }
                            return
                        }
                    }
                } catch (t: Throwable) {
                    if (errorLogs == 0 || errorLogs % 10 == 0) {
                        Log.w("PicoOB", "MSF QIMEI compatibility attempt #$attempt failed", t)
                    }
                    errorLogs++
                }
                if (attempt == FAST_POLL_ATTEMPTS && !qimeiInitialized.get()) {
                    Log.w("PicoOB", "MSF QIMEI not ready yet after fast poll, switching to bounded backoff")
                }
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    if (attempt >= FAST_POLL_ATTEMPTS) {
                        delayMs = minOf(delayMs * 2L, MAX_BACKOFF_MS)
                    }
                }
            }
            if (!qimeiInitialized.get()) {
                Log.w("PicoOB", "MSF QIMEI compatibility exhausted $maxAttempts backoff attempts")
            }
        } finally {
            if (!qimeiInitialized.get()) {
                msfStarted.set(false)
            }
        }
    }

    private fun applyQimei(qimei36: String): Boolean {
        synchronized(qimeiLock) {
            if (appliedQimei == qimei36) return true
            return try {
                Dtc.setQ36(qimei36)
                o::class.java.getDeclaredMethod("a", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, qimei36)
                appliedQimei = qimei36
                qimeiInitialized.set(true)
                true
            } catch (t: Throwable) {
                Log.e("PicoOB", "MSF QIMEI cache assignment failed", t)
                false
            }
        }
    }

    private fun isValidQimei36(value: String?): Boolean = value != null && value.length == 36
}
