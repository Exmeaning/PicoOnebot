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
import com.tencent.qqnt.account.login.ui.LoginWithoutStatePage
import com.tencent.qqnt.account.login.ui.QrLoginFragment
import org.json.JSONObject
import pico.onebot.core.PicoLog
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PicoLoginManager {

    data class QrSnapshot(
        val generation: Long = 0L,
        val url: String? = null,
        val bytes: ByteArray? = null,
        val receivedAtWallMs: Long = 0L,
        val receivedAtNs: Long = 0L,
        val scanned: Boolean = false,
        val expired: Boolean = false
    )

    private val qrLock = Any()
    private var qrGeneration: Long = 0L

    @Volatile
    private var qrSnapshot: QrSnapshot = QrSnapshot()

    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pico-qr-decode").apply { isDaemon = true }
    }

    /** 原子快照访问入口,避免并发分步读取 url / bytes / time 产生撕裂。 */
    fun currentQrSnapshot(): QrSnapshot = qrSnapshot

    val latestQrUrl: String?
        get() = qrSnapshot.url

    val latestQrBytes: ByteArray?
        get() = qrSnapshot.bytes

    val latestQrTime: Long
        get() = qrSnapshot.receivedAtWallMs

    val isExpired: Boolean
        get() = qrSnapshot.expired

    @Volatile
    var autoSwitchAccount: Boolean = false

    /** 手机已扫码、等待确认(QrLoginFragment 的 qr_scanned_tips 回调);收到新码时复位。 */
    val isScanned: Boolean
        get() = qrSnapshot.scanned

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var qrFragmentRef: WeakReference<QrLoginFragment>? = null
        private set

    @Volatile
    var stateFragmentRef: WeakReference<LoginWithStateFragment>? = null
        private set

    @Volatile
    private var welcomeFragmentRef: WeakReference<LoginWithoutStatePage>? = null

    val hasLoginEntry: Boolean
        get() = stateFragmentRef?.get()?.view != null || welcomeFragmentRef?.get()?.view != null

    fun onWelcomeViewCreated(fragment: LoginWithoutStatePage, view: View) {
        welcomeFragmentRef = WeakReference(fragment)
        view.post {
            if (welcomeFragmentRef?.get() === fragment && fragment.view === view && fragment.isAdded) {
                clickWelcomeLogin(view)
            }
        }
    }

    fun onWelcomeViewDestroyed(fragment: LoginWithoutStatePage) {
        if (welcomeFragmentRef?.get() === fragment) welcomeFragmentRef = null
    }

    private fun clickWelcomeLogin(view: View) {
        try {
            val resourceId = view.resources.getIdentifier("login", "id", view.context.packageName)
            val button = if (resourceId != 0) view.findViewById<View>(resourceId) else null
            if (button?.performClick() != true) PicoLog.w("PicoLoginManager: welcome login button unavailable")
        } catch (error: Throwable) {
            PicoLog.e("PicoLoginManager: welcome login click failed", error)
        }
    }

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
        val bytesCopy = picBuf.copyOf()
        val gen: Long
        synchronized(qrLock) {
            gen = ++qrGeneration
            val nowWall = maxOf(System.currentTimeMillis(), qrSnapshot.receivedAtWallMs + 1L)
            qrSnapshot = QrSnapshot(
                generation = gen,
                url = null,
                bytes = bytesCopy,
                receivedAtWallMs = nowWall,
                receivedAtNs = System.nanoTime(),
                scanned = false,
                expired = false
            )
        }

        decodeExecutor.execute {
            try {
                if (qrSnapshot.generation != gen) return@execute
                val url = decodeQrFromBytes(bytesCopy)
                if (!url.isNullOrEmpty()) {
                    val committed = synchronized(qrLock) {
                        if (qrSnapshot.generation == gen) {
                            qrSnapshot = qrSnapshot.copy(url = url)
                            true
                        } else {
                            false
                        }
                    }
                    if (committed) {
                        PicoLog.i("PicoLoginManager: Decoded QR URL successfully (${redactQrUrl(url)})")
                    }
                } else {
                    PicoLog.w("PicoLoginManager: QR bytes received (${bytesCopy.size}B) but ZXing decode returned null")
                }
            } catch (t: Throwable) {
                PicoLog.e("PicoLoginManager: decodeQrFromBytes error", t)
            }
        }
    }

    fun onQrScanned(frag: QrLoginFragment? = null) {
        PicoLog.i("PicoLoginManager: QR scanned, waiting for confirm on phone")
        synchronized(qrLock) {
            val cur = qrSnapshot
            if (cur.receivedAtWallMs > 0L) {
                qrSnapshot = cur.copy(scanned = true)
            }
        }
    }

    fun onQrExpired(frag: QrLoginFragment? = null) {
        PicoLog.i("PicoLoginManager: QR code expired callback received")
        synchronized(qrLock) {
            val cur = qrSnapshot
            if (cur.receivedAtWallMs > 0L) {
                qrSnapshot = cur.copy(expired = true)
            }
        }
    }

    fun clearQr() {
        synchronized(qrLock) {
            qrGeneration++
            qrSnapshot = QrSnapshot(generation = qrGeneration)
        }
    }

    /**
     * 刷新二维码:调用 LoginQrCode.b() 静默请求全新二维码(QrLoginFragment 持有它的字段名随版本漂移:9.0.7 是 h,9.0.1 是 g)。
     * 若当前在 LoginWithStateFragment，则自动触发“切换账号”转到扫码界面。
     */
    fun refreshQr(): Boolean {
        val frag = qrFragmentRef?.get()
        if (frag != null && frag.isAdded) {
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

        val welcome = welcomeFragmentRef?.get()
        if (welcome != null && welcome.isAdded && welcome.view != null) {
            mainHandler.post {
                if (welcome.isAdded) welcome.view?.let { clickWelcomeLogin(it) }
            }
            return true
        }

        PicoLog.w("PicoLoginManager: No active QR or login entry fragment found")
        return false
    }

    /**
     * 获取或刷新二维码。若已过期或为空，尝试刷新并等待至多 timeoutMs。
     */
    fun getOrRefreshQr(timeoutMs: Long = 3000): String? {
        val snap = qrSnapshot
        val ageSec = snapshotAgeSec(snap)
        val needsRefresh = snap.url.isNullOrEmpty() || snap.expired || ageSec < 0 || ageSec > 80

        if (needsRefresh) {
            PicoLog.i("PicoLoginManager: QR needs refresh (url=${snap.url != null}, expired=${snap.expired}, age=${ageSec}s)")
            val beforeGen = snap.generation
            if (refreshQr()) {
                val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
                while ((deadlineNs - System.nanoTime()) > 0L) {
                    val cur = qrSnapshot
                    if (cur.generation > beforeGen && !cur.expired && !cur.url.isNullOrEmpty()) {
                        return cur.url
                    }
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        return qrSnapshot.url
    }

    fun decodeQrFromBytes(picBuf: ByteArray): String? {
        var bitmap: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            bitmap = BitmapFactory.decodeByteArray(picBuf, 0, picBuf.size, options) ?: return null
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
        } finally {
            try {
                bitmap?.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    fun info(): JSONObject {
        val snap = qrSnapshot
        val ageSec = snapshotAgeSec(snap)
        val expired = snap.expired || (ageSec > 85)
        return JSONObject()
            .put("url", snap.url ?: "")
            .put("expired", expired)
            .put("age_seconds", ageSec)
            .put("has_qr", !snap.url.isNullOrEmpty())
            .put("scanned", snap.scanned)
            .put("has_fragment", qrFragmentRef?.get() != null)
            .put("has_state_fragment", hasLoginEntry)
    }

    private fun snapshotAgeSec(snap: QrSnapshot): Int {
        if (snap.receivedAtWallMs <= 0L) return -1
        return ((System.nanoTime() - snap.receivedAtNs).coerceAtLeast(0L) / 1_000_000_000L).toInt()
    }

    private fun redactQrUrl(url: String): String {
        val host = try { URI(url).host } catch (_: Throwable) { null }
        return if (!host.isNullOrEmpty()) "host=$host, len=${url.length}" else "len=${url.length}"
    }
}
