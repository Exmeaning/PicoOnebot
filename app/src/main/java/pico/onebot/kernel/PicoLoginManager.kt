package pico.onebot.kernel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.tencent.qqnt.account.login.ui.LoginWithStateFragment
import com.tencent.qqnt.account.login.ui.QrLoginFragment
import org.json.JSONObject
import pico.onebot.core.PicoLog
import java.lang.ref.WeakReference
import java.util.Collections

object PicoLoginManager {

    @Volatile
    var latestQrUrl: String? = null
        private set

    @Volatile
    var latestQrBytes: ByteArray? = null
        private set

    @Volatile
    var latestQrTime: Long = 0L
        private set

    @Volatile
    var isExpired: Boolean = false
        private set

    @Volatile
    var autoSwitchAccount: Boolean = false

    /** 手机已扫码、等待确认(QrLoginFragment 的 qr_scanned_tips 回调);收到新码时复位。 */
    @Volatile
    var isScanned: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var qrFragmentRef: WeakReference<QrLoginFragment>? = null
        private set

    @Volatile
    var stateFragmentRef: WeakReference<LoginWithStateFragment>? = null
        private set

    fun onQrFragmentCreated(frag: QrLoginFragment) {
        PicoLog.i("PicoLoginManager: QrLoginFragment created: $frag")
        qrFragmentRef = WeakReference(frag)
    }

    fun onQrFragmentDestroyed(frag: QrLoginFragment) {
        PicoLog.i("PicoLoginManager: QrLoginFragment destroyed: $frag")
        if (qrFragmentRef?.get() === frag) {
            qrFragmentRef = null
        }
    }

    fun onStateViewCreated(frag: LoginWithStateFragment, view: View) {
        PicoLog.i("PicoLoginManager: LoginWithStateFragment onViewCreated")
        stateFragmentRef = WeakReference(frag)
        if (autoSwitchAccount) {
            autoSwitchAccount = false
            PicoLog.i("PicoLoginManager: autoSwitchAccount is true, auto-navigating to QrLoginFragment...")
            view.post {
                try {
                    val btn = switchAccountButton(view) ?: frag.f?.d
                    if (btn != null) {
                        btn.performClick()
                        PicoLog.i("PicoLoginManager: switch account button clicked")
                    } else {
                        PicoLog.w("PicoLoginManager: switch account button not found")
                    }
                } catch (t: Throwable) {
                    PicoLog.w("PicoLoginManager: autoSwitchAccount click failed: ${t.message}")
                }
            }
        }
    }

    /**
     * "切换账号"按钮。资源 id 随宿主版本漂移(NWear 改版包 2114520422,官方 187005 是 2114520411),
     * 所以按名字 `id/more` 运行时解析,解析不到再回退到 binding 字段 `f.d`。
     */
    private fun switchAccountButton(root: View): View? = try {
        val id = root.resources.getIdentifier("more", "id", root.context.packageName)
        if (id != 0) root.findViewById(id) else null
    } catch (t: Throwable) {
        null
    }

    fun onStateFragmentDestroyed(frag: LoginWithStateFragment) {
        if (stateFragmentRef?.get() === frag) {
            stateFragmentRef = null
        }
    }

    fun onQrReceived(frag: QrLoginFragment, picBuf: ByteArray) {
        qrFragmentRef = WeakReference(frag)
        latestQrBytes = picBuf
        latestQrTime = System.currentTimeMillis()
        isScanned = false
        isExpired = false

        Thread({
            try {
                val url = decodeQrFromBytes(picBuf)
                if (!url.isNullOrEmpty()) {
                    latestQrUrl = url
                    PicoLog.i("PicoLoginManager: Decoded QR URL successfully: $url")
                } else {
                    PicoLog.w("PicoLoginManager: QR bytes received (${picBuf.size}B) but ZXing decode returned null")
                }
            } catch (t: Throwable) {
                PicoLog.e("PicoLoginManager: decodeQrFromBytes error", t)
            }
        }, "pico-qr-decode").start()
    }

    fun onQrScanned(frag: QrLoginFragment? = null) {
        PicoLog.i("PicoLoginManager: QR scanned, waiting for confirm on phone")
        isScanned = true
    }

    fun onQrExpired(frag: QrLoginFragment? = null) {
        PicoLog.i("PicoLoginManager: QR code expired callback received")
        isExpired = true
    }

    fun clearQr() {
        latestQrUrl = null
        latestQrBytes = null
        latestQrTime = 0L
        isScanned = false
        isExpired = false
    }

    /**
     * 刷新二维码:调用 LoginQrCode.b() 静默请求全新二维码(QrLoginFragment 持有它的字段名随版本漂移:9.0.7 是 h,9.0.1 是 g)。
     * 若当前在 LoginWithStateFragment，则自动触发“切换账号”转到扫码界面。
     */
    fun refreshQr(): Boolean {
        val frag = qrFragmentRef?.get()
        if (frag != null) {
            mainHandler.post {
                try {
                    PicoLog.i("PicoLoginManager: Invoking LoginQrCode.b() to refresh QR")
                    frag.h?.b()
                } catch (t: Throwable) {
                    PicoLog.e("PicoLoginManager: frag.h.b() failed", t)
                }
            }
            return true
        }

        val stateFrag = stateFragmentRef?.get()
        if (stateFrag != null && stateFrag.isAdded) {
            PicoLog.i("PicoLoginManager: on LoginWithStateFragment, switching to QR login...")
            mainHandler.post {
                try {
                    val btn = stateFrag.view?.let { switchAccountButton(it) } ?: stateFrag.f?.d
                    btn?.performClick()
                } catch (t: Throwable) {
                    PicoLog.e("PicoLoginManager: performClick on switch account failed", t)
                }
            }
            return true
        }

        PicoLog.w("PicoLoginManager: No active QrLoginFragment or LoginWithStateFragment found")
        return false
    }

    /**
     * 获取或刷新二维码。若已过期或为空，尝试刷新并等待至多 timeoutMs。
     */
    fun getOrRefreshQr(timeoutMs: Long = 3000): String? {
        val now = System.currentTimeMillis()
        val ageSec = if (latestQrTime > 0) ((now - latestQrTime) / 1000).toInt() else 9999
        val needsRefresh = latestQrUrl == null || isExpired || ageSec > 80

        if (needsRefresh) {
            PicoLog.i("PicoLoginManager: QR needs refresh (url=${latestQrUrl != null}, expired=$isExpired, age=${ageSec}s)")
            val oldTime = latestQrTime
            if (refreshQr()) {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    if (latestQrTime > oldTime && !isExpired && !latestQrUrl.isNullOrEmpty()) {
                        return latestQrUrl
                    }
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        return latestQrUrl
    }

    fun decodeQrFromBytes(picBuf: ByteArray): String? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(picBuf, 0, picBuf.size, options) ?: return null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            val hints = Collections.singletonMap(DecodeHintType.c, java.lang.Boolean.TRUE)
            val result = reader.a(binaryBitmap, hints)
            result?.a
        } catch (t: Throwable) {
            PicoLog.w("PicoLoginManager: decodeQrFromBytes failed: ${t.message}")
            null
        }
    }

    fun info(): JSONObject {
        val now = System.currentTimeMillis()
        val ageSec = if (latestQrTime > 0) ((now - latestQrTime) / 1000).toInt() else -1
        val expired = isExpired || (ageSec > 85)
        return JSONObject()
            .put("url", latestQrUrl ?: "")
            .put("expired", expired)
            .put("age_seconds", ageSec)
            .put("has_qr", !latestQrUrl.isNullOrEmpty())
            .put("scanned", isScanned)
            .put("has_fragment", qrFragmentRef?.get() != null)
            .put("has_state_fragment", stateFragmentRef?.get() != null)
    }
}
