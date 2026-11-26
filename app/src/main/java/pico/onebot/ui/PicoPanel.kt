package pico.onebot.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import pico.onebot.PicoBoot
import pico.onebot.core.Config
import pico.onebot.core.NetUtil
import pico.onebot.core.PicoLog
import pico.onebot.core.QrEncoder
import pico.onebot.keepalive.KeepAliveSettings
import pico.onebot.kernel.KernelGate
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 登录后盖在 QQ 首页上的引导面板(设计 §3.2 方案 ①):状态 / 地址 / Token / 地址二维码 / 打开控制台。
 *
 * 纯代码构建 View(注入的 dex 里没有资源),只做方形屏 / 模拟器布局(圆屏不在目标内)。
 * 每个进程最多自动弹一次。不整页替换 QQ 的 UI,点"进入 QQ"就移除,
 * 风控弹窗 / 切号等原功能全部保留。
 */
object PicoPanel {

    private const val INK = 0xFF3B2A3A.toInt()
    private const val MUTED = 0xFF8A7A88.toInt()
    private const val PINK = 0xFFE2568E.toInt()
    private const val PINK_SOFT = 0xFFFFE0EC.toInt()
    private const val BG = 0xFFFFF4F8.toInt()
    private const val AMBER = 0xFFB45309.toInt()
    private const val AMBER_SOFT = 0xFFFEF3C7.toInt()

    private val stateLock = Any()

    @Volatile
    private var shownInProcess = false
    private var rootRef: WeakReference<View>? = null
    private var unsubState: (() -> Unit)? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var qrImageRef: WeakReference<ImageView>? = null
    private var qrBitmapRef: Bitmap? = null
    private var lastActivity: WeakReference<Activity>? = null
    private val scheduledShows = WeakHashMap<Activity, Pair<WeakReference<View>, Runnable>>()

    /**
     * 布局单位:把短边当 360 个单位来铺,而不是用系统 density。
     * Waydroid 1440x2512 实测 density≈5.4,按 dp 铺出来一屏只剩半个二维码;真机 454px 圆/方屏 density≈2 又嫌小。
     */
    @Volatile
    private var unit = 1f

    /** 供 `_pico_show_panel` 动作 / 调试用:在最近一个 MainActivity 上立刻弹出(或收起)面板。 */
    fun showNow(): Boolean {
        val act = synchronized(stateLock) { lastActivity?.get() } ?: return false
        if (act.isFinishing || act.isDestroyed) return false
        act.runOnUiThread {
            try {
                if (!act.isFinishing && !act.isDestroyed) show(act)
            } catch (t: Throwable) {
                PicoLog.e("panel: showNow failed", t)
            }
        }
        return true
    }

    fun hideNow() {
        val act = synchronized(stateLock) { lastActivity?.get() } ?: return
        act.runOnUiThread { dismiss() }
    }

    private var pendingUnsub: (() -> Unit)? = null
    private var pendingActivityRef: WeakReference<Activity>? = null

    /** 延迟在 MainActivity 上尝试弹面板,并在该 Activity 销毁时可精确取消回调避免泄漏。 */
    fun scheduleMaybeShow(activity: Activity, delayMs: Long = 1500L) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView ?: return
        val actRef = WeakReference(activity)
        val task = Runnable {
            val act = actRef.get() ?: return@Runnable
            synchronized(stateLock) {
                scheduledShows.remove(act)
            }
            try {
                if (!act.isFinishing && !act.isDestroyed) {
                    maybeShow(act)
                }
            } catch (t: Throwable) {
                PicoLog.e("PicoPanel show failed", t)
            }
        }
        val prev = synchronized(stateLock) {
            scheduledShows.put(activity, WeakReference(decor) to task)
        }
        prev?.let { (oldDecorRef, oldTask) ->
            oldDecorRef.get()?.removeCallbacks(oldTask)
        }
        decor.postDelayed(task, delayMs)
    }

    /**
     * MainActivity 起来后调用，一个进程只自动弹一次。
     *
     * 官方 9.0.7 的 MainActivity **登录前就已创建**(欢迎页 / 扫码页都挂在它上面,Waydroid 实测),
     * 立刻盖上去会把"登录"按钮和二维码遮住。所以:已 ONLINE 直接弹;否则挂状态监听,到 ONLINE 再弹。
     */
    fun maybeShow(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val oldUnsub: (() -> Unit)?
        val shouldShowNow: Boolean
        synchronized(stateLock) {
            lastActivity = WeakReference(activity)
            if (shownInProcess) return
            if (KernelGate.state == KernelGate.State.ONLINE) {
                shownInProcess = true
                oldUnsub = pendingUnsub
                pendingUnsub = null
                pendingActivityRef = null
                shouldShowNow = true
            } else {
                if (pendingUnsub != null && pendingActivityRef?.get() === activity) return
                oldUnsub = pendingUnsub
                pendingUnsub = null
                pendingActivityRef = null
                shouldShowNow = false
            }
        }
        oldUnsub?.invoke()
        if (shouldShowNow) {
            show(activity)
            return
        }

        PicoLog.d("panel: not online yet, will show after ONLINE")
        val ref = WeakReference(activity)
        val holder = arrayOfNulls<() -> Unit>(1)
        var firedBeforeAssign = false
        val listener: (KernelGate.State) -> Unit = { st ->
            if (st == KernelGate.State.ONLINE) {
                var toCancel: (() -> Unit)? = null
                val shouldDispatch = synchronized(stateLock) {
                    val isOwner = (holder[0] != null && pendingUnsub === holder[0]) ||
                        (holder[0] == null && pendingActivityRef?.get() === activity)
                    if (!isOwner && holder[0] != null) {
                        false
                    } else {
                        toCancel = holder[0]
                        if (toCancel == null) firedBeforeAssign = true
                        if (pendingUnsub === holder[0] || pendingActivityRef?.get() === activity) {
                            pendingUnsub = null
                            pendingActivityRef = null
                        }
                        !shownInProcess
                    }
                }
                toCancel?.invoke()
                if (shouldDispatch) {
                    val act = ref.get()
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        act.runOnUiThread {
                            try {
                                val canShow = synchronized(stateLock) {
                                    if (!act.isFinishing && !act.isDestroyed && !shownInProcess) {
                                        shownInProcess = true
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (canShow) show(act)
                            } catch (t: Throwable) {
                                PicoLog.e("panel: deferred show failed", t)
                            }
                        }
                    }
                }
            }
        }
        val registered = KernelGate.addStateListener(listener)
        val cancelNow = synchronized(stateLock) {
            holder[0] = registered
            if (firedBeforeAssign || shownInProcess || activity.isFinishing || activity.isDestroyed) {
                true
            } else {
                pendingUnsub = registered
                pendingActivityRef = ref
                false
            }
        }
        if (cancelNow) registered()
    }

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()
        val root = build(activity)
        activity.addContentView(
            root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        synchronized(stateLock) {
            rootRef = WeakReference(root)
        }
        PicoLog.i("panel: shown on " + activity.javaClass.simpleName + ", url=" + NetUtil.webuiUrl())
    }

    fun dismiss() {
        val unsub: (() -> Unit)?
        val v: View?
        val fl: ViewTreeObserver.OnWindowFocusChangeListener?
        val qrIv: ImageView?
        val qrBmp: Bitmap?
        synchronized(stateLock) {
            unsub = unsubState
            unsubState = null
            v = rootRef?.get()
            rootRef = null
            fl = focusListener
            focusListener = null
            qrIv = qrImageRef?.get()
            qrImageRef = null
            qrBmp = qrBitmapRef
            qrBitmapRef = null
        }
        unsub?.invoke()
        if (v != null && fl != null) {
            try {
                val vto = v.viewTreeObserver
                if (vto.isAlive) vto.removeOnWindowFocusChangeListener(fl)
            } catch (_: Throwable) {
            }
        }
        if (v != null) {
            (v.parent as? ViewGroup)?.removeView(v)
        }
        try {
            qrIv?.setImageDrawable(null)
            if (qrBmp != null && !qrBmp.isRecycled) qrBmp.recycle()
        } catch (_: Throwable) {
        }
    }

    fun onActivityDestroyed(activity: Activity) {
        val scheduled: Pair<WeakReference<View>, Runnable>?
        val unsubForThisActivity: (() -> Unit)?
        val shouldDismiss: Boolean
        synchronized(stateLock) {
            scheduled = scheduledShows.remove(activity)
            val pendingOwner = pendingActivityRef?.get()
            if (pendingOwner === activity || pendingOwner == null) {
                unsubForThisActivity = pendingUnsub
                pendingUnsub = null
                pendingActivityRef = null
            } else {
                unsubForThisActivity = null
            }
            if (lastActivity?.get() === activity) {
                lastActivity = null
            }
            val v = rootRef?.get()
            shouldDismiss = v != null && v.context === activity
        }
        scheduled?.let { (decorRef, task) ->
            decorRef.get()?.removeCallbacks(task)
        }
        unsubForThisActivity?.invoke()
        if (shouldDismiss) dismiss()
    }

    // ---------------- 构建 ----------------

    private fun build(ctx: Activity): View {
        val dm = ctx.resources.displayMetrics
        unit = minOf(dm.widthPixels, dm.heightPixels) / 360f
        fun dp(v: Int) = (v * unit + 0.5f).toInt()
        val url = NetUtil.webuiUrl()

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(BG)
            isClickable = true
            isFocusable = true
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        col.addView(tv(ctx, "PicoOnebot", 20f, PINK, bold = true))
        col.addView(tv(ctx, "PICOPICO · OneBot 11 控制台", 11f, MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })

        val status = tv(ctx, statusText(), 12f, INK, bold = true).apply {
            background = pill(PINK_SOFT, dp(999))
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        col.addView(status)

        // 控制台地址的二维码(自带编码器)
        var builtBmp: Bitmap? = null
        val qr = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = pill(Color.WHITE, dp(16))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            try {
                builtBmp = qrBitmap(url, dp(150))
                setImageBitmap(builtBmp)
            } catch (t: Throwable) {
                PicoLog.w("panel: qr encode failed: " + t.message)
            }
        }
        synchronized(stateLock) {
            qrImageRef = WeakReference(qr)
            qrBitmapRef = builtBmp
        }
        col.addView(qr, LinearLayout.LayoutParams(dp(166), dp(166)).apply { topMargin = dp(12) })

        col.addView(tv(ctx, url, 14f, INK, bold = true, mono = true).apply {
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        })
        col.addView(tv(ctx, "在电脑 / 手机浏览器打开上面的地址,或直接扫码", 11f, MUTED).apply { gravity = Gravity.CENTER })

        // 初始密码只在尚未初始化时显示；设置后配置中只保留密码派生值。
        val isDefault = !Config.webuiPasswordInitialized
        val tokenBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(if (isDefault) AMBER_SOFT else PINK_SOFT, dp(14))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (isDefault) {
            tokenBox.addView(tv(ctx, "初始密码: " + Config.DEFAULT_WEBUI_PASSWORD, 13f, AMBER, bold = true, mono = true))
            tokenBox.addView(tv(ctx, "首次进入控制台时必须设置新密码", 11f, AMBER))
        } else {
            tokenBox.addView(tv(ctx, "控制台密码已设置", 13f, INK, bold = true))
            tokenBox.addView(tv(ctx, "配置文件不保存明文密码", 11f, MUTED))
        }
        col.addView(
            tokenBox,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )

        // 按钮
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            button(ctx, "打开控制台", PINK, Color.WHITE, dp(14)) { openBrowser(ctx, url) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) }
        )
        row.addView(
            button(ctx, "复制地址", PINK_SOFT, PINK, dp(14)) { copy(ctx, url) },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        col.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )

        col.addView(tv(ctx, "保活设置", 14f, INK, bold = true).apply {
            setPadding(0, dp(14), 0, dp(2))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(tv(ctx, "建议完成以下设置，减少系统在后台清理 PicoOnebot", 10f, MUTED).apply {
            setPadding(0, 0, 0, dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val backgroundItem = keepAliveItem(
            ctx,
            "锁定后台",
            "在最近任务中下拉或长按应用卡片并锁定，同时允许后台运行和自启动。",
            "需手动",
            "后台设置"
        ) {
            Toast.makeText(ctx, "还需在最近任务中锁定 PicoOnebot 卡片", Toast.LENGTH_LONG).show()
            KeepAliveSettings.openBackgroundSettings(ctx)
        }
        col.addView(
            backgroundItem.first,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val accessibilityItem = keepAliveItem(
            ctx,
            "无障碍保活服务",
            "在无障碍设置中开启“PicoOnebot 保活服务”。",
            "检查中",
            "去开启"
        ) { KeepAliveSettings.openAccessibilitySettings(ctx) }
        col.addView(
            accessibilityItem.first,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )

        val batteryItem = keepAliveItem(
            ctx,
            "忽略电池优化",
            "允许 PicoOnebot 在息屏和待机时继续运行。",
            "检查中",
            "去允许"
        ) { KeepAliveSettings.requestIgnoreBatteryOptimizations(ctx) }
        col.addView(
            batteryItem.first,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )

        fun refreshKeepAliveState() {
            val accessibilityEnabled = KeepAliveSettings.isAccessibilityEnabled(ctx)
            accessibilityItem.second.text = if (accessibilityEnabled) "已开启" else "未开启"
            accessibilityItem.second.setTextColor(if (accessibilityEnabled) PINK else AMBER)
            val batteryAllowed = KeepAliveSettings.isIgnoringBatteryOptimizations(ctx)
            batteryItem.second.text = if (batteryAllowed) "已允许" else "未允许"
            batteryItem.second.setTextColor(if (batteryAllowed) PINK else AMBER)
        }
        refreshKeepAliveState()
        val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) refreshKeepAliveState()
        }
        root.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        synchronized(stateLock) {
            focusListener = windowFocusListener
        }

        col.addView(
            button(ctx, "给 PICOPICO 点一个免费的小星星 ★", PINK_SOFT, PINK, dp(14)) {
                openBrowser(ctx, PicoBoot.REPO)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) }
        )
        col.addView(
            button(ctx, "进入 QQ", Color.WHITE, INK, dp(14)) { dismiss() }.apply {
                background = outline(Color.WHITE, PINK_SOFT, dp(14), dp(2))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) }
        )

        scroll.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 状态行跟着状态机走(监听器回调在任意线程,post 回 UI 线程)
        val statusRef = WeakReference(status)
        val stateUnsub = KernelGate.addStateListener {
            val t = statusRef.get() ?: return@addStateListener
            t.post { t.text = statusText() }
        }
        synchronized(stateLock) {
            unsubState = stateUnsub
        }
        return root
    }

    private fun statusText(): String = when (KernelGate.state) {
        KernelGate.State.ONLINE -> "●  在线  QQ " + KernelGate.selfUin()
        KernelGate.State.LOGGED_IN, KernelGate.State.SESSION_READY -> "●  内核就绪,正在接管消息"
        KernelGate.State.LOGGED_OUT -> "●  已退出登录,请扫码"
        else -> "●  等待登录 / 内核初始化"
    }

    // ---------------- 小部件 ----------------

    private fun tv(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit)
        t.setTextColor(color)
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        t.typeface = if (mono) Typeface.create(Typeface.MONOSPACE, style) else Typeface.defaultFromStyle(style)
        return t
    }

    private fun pill(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun outline(fill: Int, stroke: Int, radius: Int, width: Int): GradientDrawable =
        pill(fill, radius).apply { setStroke(width, stroke) }

    private fun button(ctx: Context, label: String, bg: Int, fg: Int, radius: Int, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * unit)
        b.setTextColor(fg)
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.background = pill(bg, radius)
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener {
            try {
                onClick()
            } catch (t: Throwable) {
                PicoLog.w("panel: click failed: " + t.message)
            }
        }
        return b
    }

    private fun keepAliveItem(
        ctx: Context,
        title: String,
        description: String,
        initialStatus: String,
        actionLabel: String,
        onClick: () -> Unit
    ): Pair<View, TextView> {
        fun dp(v: Int) = (v * unit + 0.5f).toInt()
        val status = tv(ctx, initialStatus, 10f, AMBER, bold = true)
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(ctx, title, 12f, INK, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
        }
        val copy = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tv(ctx, description, 10f, MUTED).apply { setPadding(0, dp(2), dp(6), 0) })
        }
        val item = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(Color.WHITE, dp(10))
            setPadding(dp(10), dp(8), dp(8), dp(8))
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                button(ctx, actionLabel, PINK_SOFT, PINK, dp(10), onClick).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * unit)
                },
                LinearLayout.LayoutParams(dp(72), dp(34)).apply { leftMargin = dp(6) }
            )
        }
        return item to status
    }

    private fun openBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Toast.makeText(ctx, "本机没有浏览器,请在电脑上打开 " + url, Toast.LENGTH_LONG).show()
        }
    }

    private fun copy(ctx: Context, s: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PicoOnebot", s))
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(ctx, "复制失败: " + t.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 把文本编成二维码位图:白底黑模块,四周留 2 个模块的静区。 */
    fun qrBitmap(content: String, px: Int): Bitmap {
        val sym = QrEncoder.encode(content)
        val margin = 2
        val n = sym.size + margin * 2
        val scale = maxOf(1, px / n)
        val side = n * scale
        val pixels = IntArray(side * side) { Color.WHITE }
        for (r in 0 until sym.size) for (c in 0 until sym.size) {
            if (!sym.dark(r, c)) continue
            val y0 = (r + margin) * scale
            val x0 = (c + margin) * scale
            for (dy in 0 until scale) {
                val base = (y0 + dy) * side + x0
                for (dx in 0 until scale) pixels[base + dx] = Color.BLACK
            }
        }
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, side, 0, 0, side, side)
        return bmp
    }
}
