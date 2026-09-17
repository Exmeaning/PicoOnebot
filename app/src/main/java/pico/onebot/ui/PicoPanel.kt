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

    @Volatile
    private var shownInProcess = false
    private var rootRef: WeakReference<View>? = null
    private var unsubState: (() -> Unit)? = null
    private var lastActivity: WeakReference<Activity>? = null

    /**
     * 布局单位:把短边当 360 个单位来铺,而不是用系统 density。
     * Waydroid 1440x2512 实测 density≈5.4,按 dp 铺出来一屏只剩半个二维码;真机 454px 圆/方屏 density≈2 又嫌小。
     */
    @Volatile
    private var unit = 1f

    /** 供 `_pico_show_panel` 动作 / 调试用:在最近一个 MainActivity 上立刻弹出(或收起)面板。 */
    fun showNow(): Boolean {
        val act = lastActivity?.get() ?: return false
        if (act.isFinishing || act.isDestroyed) return false
        act.runOnUiThread {
            try {
                show(act)
            } catch (t: Throwable) {
                PicoLog.e("panel: showNow failed", t)
            }
        }
        return true
    }

    fun hideNow() {
        val act = lastActivity?.get() ?: return
        act.runOnUiThread { dismiss() }
    }

    private var pendingUnsub: (() -> Unit)? = null

    /**
     * MainActivity 起来后调用，一个进程只自动弹一次。
     *
     * 官方 9.0.7 的 MainActivity **登录前就已创建**(欢迎页 / 扫码页都挂在它上面,Waydroid 实测),
     * 立刻盖上去会把"登录"按钮和二维码遮住。所以:已 ONLINE 直接弹;否则挂状态监听,到 ONLINE 再弹。
     */
    fun maybeShow(activity: Activity) {
        lastActivity = WeakReference(activity)
        if (shownInProcess) return
        if (KernelGate.state == KernelGate.State.ONLINE) {
            shownInProcess = true
            show(activity)
            return
        }
        if (pendingUnsub != null) return
        PicoLog.d("panel: not online yet, will show after ONLINE")
        val ref = WeakReference(activity)
        var unsub: (() -> Unit)? = null
        unsub = KernelGate.addStateListener { st ->
            if (st != KernelGate.State.ONLINE) return@addStateListener
            unsub?.invoke()
            pendingUnsub = null
            val act = ref.get() ?: return@addStateListener
            act.runOnUiThread {
                try {
                    if (!act.isFinishing && !act.isDestroyed && !shownInProcess) {
                        shownInProcess = true
                        show(act)
                    }
                } catch (t: Throwable) {
                    PicoLog.e("panel: deferred show failed", t)
                }
            }
        }
        pendingUnsub = unsub
    }

    fun show(activity: Activity) {
        dismiss()
        val root = build(activity)
        activity.addContentView(
            root,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        rootRef = WeakReference(root)
        PicoLog.i("panel: shown on " + activity.javaClass.simpleName + ", url=" + NetUtil.webuiUrl())
    }

    fun dismiss() {
        unsubState?.invoke()
        unsubState = null
        val v = rootRef?.get() ?: return
        rootRef = null
        (v.parent as? ViewGroup)?.removeView(v)
    }

    fun onActivityDestroyed(activity: Activity) {
        // 这个实例还没等到 ONLINE 就销毁了:撤掉挂着的监听,下一个 MainActivity 会重新挂
        pendingUnsub?.invoke()
        pendingUnsub = null
        val v = rootRef?.get()
        if (v != null && v.context === activity) dismiss()
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
        val qr = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = pill(Color.WHITE, dp(16))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            try {
                setImageBitmap(qrBitmap(url, dp(150)))
            } catch (t: Throwable) {
                PicoLog.w("panel: qr encode failed: " + t.message)
            }
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
        root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) refreshKeepAliveState()
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
        unsubState = KernelGate.addStateListener {
            val t = statusRef.get() ?: return@addStateListener
            t.post { t.text = statusText() }
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
