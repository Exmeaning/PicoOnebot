@file:Suppress("DEPRECATION")

package pico.onebot.hook

import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.util.Log
import com.tencent.mobileqq.msf.sdk.AppNetConnInfo
import com.tencent.qqnt.kernel.api.impl.KernelServiceImpl
import com.tencent.qqnt.kernel.nativeinterface.NetStatusType
import momoi.anno.mixin.Mixin
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 适配 Redroid / Android 容器的 Ethernet (`ConnectivityManager.TYPE_ETHERNET == 9`) 网络。
 *
 * 背景：Watch QQ 9.0.7 的两处网络状态判断只识别 WiFi (`type == 1`) 与移动网络 (`type == 0/50`)，
 * 导致容器内 Ethernet (`type == 9`) 落入未知分支：
 * 1. `com.tencent.mobileqq.msf.sdk.q.a`（`:MSF` 的 `core.r` 与主进程 `AppNetConnInfo` 共用）
 *    将 Ethernet 判为 `k = 4 (UNKNOWN)` 并打印 `Unexcepted activeNetInfo type:9`，从而：
 *    - `net.k.d()` 恒返 `0`，每条上行 SSO 包携带 `SSOReserveField.network_type = 0`，且发包前每 60s 强制同步 `checkConnInfo`；
 *    - `INetInfoHandler`（`KernelSetterImpl` / `KernelNetInfoCB`）永不收到连接/断网/恢复回调；
 *    - `core.r.a(oldState)` 永不执行，`SsoListManager` 永不刷新。
 * 2. `KernelServiceImpl$getIKernelSessionListener$1.onSessionInitComplete()` 按 `NetworkInfo.getType()`
 *    映射初始 `NetStatusType` 时将 `type == 9` 落入 `KREACHABLEUNKNOWN` 并传给 `KernelServiceImpl.setOnNetworkChanged()`。
 *
 * 本 Hook 完全不依赖 Android 13 受限隐藏 API（不构造 `@hide` `NetworkInfo`），
 * 仅在精确命中 `TYPE_ETHERNET` 时复用宿主原有 WiFi 状态机路径；真 WiFi、移动网与无网路径 100% 透传原实现。
 */
@Mixin
abstract class PicoMsfNetProviderHook : com.tencent.mobileqq.msf.sdk.q.a() {

    /**
     * 替换宿主 `private void a(NetworkInfo activeNetInfo, NetworkInfo extraNetInfo)`。
     *
     * 注意：
     * 1. 目标方法在原类中为 `private`（由 `a(Context, NetworkInfo, NetworkInfo)` 以 `invoke-direct` 调用），
     *    故此处保持 `private` 且不加 `@PrivateCall`（避免重复生成 `private private` 修饰符）。
     * 2. `MixinProcessor` 会将原方法重命名为 `private a_0(NetworkInfo, NetworkInfo)`；
     *    由于 `BaksmaliWriter` 对同类调用省略类名前缀，`MixinProcessor` 不会自动改写同类 `invoke-direct a(...)`，
     *    因此此处严禁直接写自调用 `a(...)`，而是统一交由 [PicoNetCompat.dispatchRefresh] 路由至 `a_0`。
     */
    @Suppress("UNUSED")
    private fun a(activeNetInfo: NetworkInfo?, extraNetInfo: NetworkInfo?) {
        PicoNetCompat.dispatchRefresh(this, activeNetInfo, extraNetInfo)
    }
}

/**
 * 拦截 `KernelServiceImpl.setOnNetworkChanged(NetStatusType)`，
 * 将 `KernelServiceImpl$getIKernelSessionListener$1.onSessionInitComplete()` 等处因 Ethernet (`type == 9`)
 * 误判传入的 `KREACHABLEUNKNOWN` 规范化为 `KREACHABLEVIAWIFI`。
 */
@Mixin
abstract class PicoKernelNetHook : KernelServiceImpl() {

    override fun setOnNetworkChanged(type: NetStatusType) {
        super.setOnNetworkChanged(PicoNetCompat.normalizeKernelNetStatus(type))
    }
}

/**
 * 独立于 `@Mixin` 目标类的辅助逻辑（同时在 `:MSF` 与主进程中运行，因此只使用 `android.util.Log`，
 * 避免在 `:MSF` 子进程触发主进程日志环或业务初始化，且绝不让异常冒泡回宿主）。
 */
object PicoNetCompat {

    private const val TAG = "PicoOB"

    /** -1 = 未记录，0 = 断开，1 = 已连接（按进程去重日志，避免刷屏）。 */
    private val lastProviderLogState = AtomicInteger(-1)
    private val reflectionFailedLogged = AtomicBoolean(false)
    private val originalFailedLogged = AtomicBoolean(false)
    private val kernelNormalizedLogged = AtomicBoolean(false)

    private class ProviderAccess(
        val clearOther: Method,
        val setWifi: Method,
        val clearWifi: Method,
        val subtypeField: Field?
    )

    @Volatile
    private var accessResolved = false

    @Volatile
    private var cachedAccess: ProviderAccess? = null

    @Volatile
    private var originalResolved = false

    @Volatile
    private var cachedOriginal: Method? = null

    private fun resolveOriginalRefresh(): Method? {
        if (originalResolved) return cachedOriginal
        return try {
            com.tencent.mobileqq.msf.sdk.q.a::class.java
                .getDeclaredMethod("a_0", NetworkInfo::class.java, NetworkInfo::class.java)
                .apply { isAccessible = true }
                .also {
                    cachedOriginal = it
                    originalResolved = true
                }
        } catch (t: Throwable) {
            originalResolved = true
            if (originalFailedLogged.compareAndSet(false, true)) {
                Log.e(TAG, "PicoNetCompat: failed to resolve sdk.q.a.a_0", t)
            }
            null
        }
    }

    private fun resolveAccess(): ProviderAccess? {
        if (accessResolved) return cachedAccess
        return try {
            val clazz = com.tencent.mobileqq.msf.sdk.q.a::class.java
            val r = clazz.getDeclaredMethod("r").apply { isAccessible = true }
            val w = clazz.getDeclaredMethod("w").apply { isAccessible = true }
            val x = clazz.getDeclaredMethod("x").apply { isAccessible = true }
            val c = try {
                clazz.getDeclaredField("c").apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
            ProviderAccess(r, w, x, c).also {
                cachedAccess = it
                accessResolved = true
            }
        } catch (t: Throwable) {
            accessResolved = true
            if (reflectionFailedLogged.compareAndSet(false, true)) {
                Log.e(TAG, "PicoNetCompat: failed to resolve sdk.q.a WiFi state methods, falling back", t)
            }
            null
        }
    }

    /**
     * `sdk.q.a.a(activeNetInfo, extraNetInfo)` 的统一分发入口：
     * 先尝试按 WiFi 语义处理 Ethernet；非 Ethernet、中间态或异常时回落调用被重命名的原方法 `a_0`。
     */
    @JvmStatic
    fun dispatchRefresh(
        provider: com.tencent.mobileqq.msf.sdk.q.a,
        activeNetInfo: NetworkInfo?,
        extraNetInfo: NetworkInfo?
    ) {
        if (handleEthernetRefresh(provider, activeNetInfo, extraNetInfo)) {
            return
        }
        val original = resolveOriginalRefresh() ?: return
        try {
            original.invoke(provider, activeNetInfo, extraNetInfo)
        } catch (t: Throwable) {
            val cause = if (t is InvocationTargetException && t.targetException != null) t.targetException else t
            Log.e(TAG, "PicoNetCompat: invoke sdk.q.a.a_0 failed", cause)
        }
    }

    /**
     * 处理 `com.tencent.mobileqq.msf.sdk.q.a.a(activeNetInfo, extraNetInfo)` 中的 Ethernet 分支。
     *
     * @return `true` 表示已按 WiFi 语义完成处理（跳过原 `a_0`）；`false` 表示交给原 `a_0` 完整执行。
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun handleEthernetRefresh(
        provider: com.tencent.mobileqq.msf.sdk.q.a,
        activeNetInfo: NetworkInfo?,
        extraNetInfo: NetworkInfo?
    ): Boolean {
        // activeNetInfo == null 时原 a_0 对 extraNetInfo (含 Ethernet DISCONNECTED) 已会执行 p()/x()/r() 清空全部位。
        if (activeNetInfo == null) return false
        return try {
            if (activeNetInfo.type != ConnectivityManager.TYPE_ETHERNET) return false

            val typeName = activeNetInfo.typeName ?: return false
            if (typeName.lowercase(Locale.ROOT).contains("mobile_mms")) return false

            val state = activeNetInfo.detailedState ?: return false
            val isConnected = state == NetworkInfo.DetailedState.CONNECTED
            val isDisconnected = state == NetworkInfo.DetailedState.DISCONNECTED ||
                state == NetworkInfo.DetailedState.FAILED ||
                state == NetworkInfo.DetailedState.IDLE ||
                state == NetworkInfo.DetailedState.SUSPENDED
            // BLOCKED 及中间态（CONNECTING / OBTAINING_IPADDR 等）仍交原 a_0 处理。
            if (!isConnected && !isDisconnected) return false

            val access = resolveAccess() ?: return false

            try {
                access.subtypeField?.set(provider, activeNetInfo.subtypeName)
            } catch (_: Throwable) {
                // 与原实现一致：读 subtypeName 异常不阻塞状态更新。
            }

            if (isConnected) {
                // 等价于原 WiFi CONNECTED 分支：r() 清 other 位，w() 置 WiFi 位 (k |= 2)。
                access.clearOther.invoke(provider)
                access.setWifi.invoke(provider)
                if (lastProviderLogState.getAndSet(1) != 1) {
                    Log.i(TAG, "PicoNetCompat: mapped Ethernet($typeName) CONNECTED -> WiFi in MSF NetworkProvider")
                }
            } else {
                // 等价于原 WiFi 断开分支：r() 清 other 位，x() 清 WiFi 位 (k &= ~2)，触发 onNetWifi2None。
                access.clearOther.invoke(provider)
                access.clearWifi.invoke(provider)
                if (lastProviderLogState.getAndSet(0) != 0) {
                    Log.i(TAG, "PicoNetCompat: mapped Ethernet($typeName) $state -> WiFi disconnect in MSF NetworkProvider")
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "PicoNetCompat: handleEthernetRefresh failed, falling back to original", t)
            false
        }
    }

    /**
     * 将 NT 内核因 Ethernet (`type == 9`) 得到的 `KREACHABLEUNKNOWN` 规范化为 `KREACHABLEVIAWIFI`。
     * 仅在 `AppNetConnInfo.isNetSupport()` 为真且活跃网络确为已连接 Ethernet 时生效，绝不影响无网/移动网/真 WiFi。
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun normalizeKernelNetStatus(status: NetStatusType): NetStatusType {
        if (status != NetStatusType.KREACHABLEUNKNOWN) return status
        return try {
            if (!AppNetConnInfo.isNetSupport()) return status
            val recent = AppNetConnInfo.getRecentNetworkInfo()
            if (recent != null &&
                recent.type == ConnectivityManager.TYPE_ETHERNET &&
                recent.detailedState == NetworkInfo.DetailedState.CONNECTED
            ) {
                if (kernelNormalizedLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "PicoNetCompat: promoted NT kernel NetStatusType KREACHABLEUNKNOWN -> KREACHABLEVIAWIFI (Ethernet)")
                }
                NetStatusType.KREACHABLEVIAWIFI
            } else {
                status
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PicoNetCompat: normalizeKernelNetStatus failed, keeping original status", t)
            status
        }
    }
}
