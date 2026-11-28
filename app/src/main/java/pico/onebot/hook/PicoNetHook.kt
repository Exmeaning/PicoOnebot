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
 */
@Mixin
abstract class PicoMsfNetProviderHook : com.tencent.mobileqq.msf.sdk.q.a() {

    /**
     * 替换宿主 `private void a(NetworkInfo activeNetInfo, NetworkInfo extraNetInfo)`。
     *
     * 注意：
     * 1. 目标方法在原类中为 `private`（由 `a(Context, NetworkInfo, NetworkInfo)` 以 `invoke-direct` 调用），
     *    故此处保持 `private` 且不加 `@PrivateCall`（避免重复生成 `private private` 修饰符）。
     * 2. `MixinProcessor` 会将原方法重命名为 `private a_<N>(NetworkInfo, NetworkInfo)`；
     *    由于 `BaksmaliWriter` 对同类调用省略类名前缀，`MixinProcessor` 不会自动改写同类 `invoke-direct a(...)`，
     *    因此此处严禁直接写自调用 `a(...)`，而是统一交由 [PicoNetCompat.dispatchRefresh] 路由至重命名后的原方法。
     */
    @Suppress("UNUSED")
    private fun a(activeNetInfo: NetworkInfo?, extraNetInfo: NetworkInfo?) {
        PicoNetCompat.dispatchRefresh(this, activeNetInfo, extraNetInfo)
    }
}

/**
 * 拦截 `KernelServiceImpl.setOnNetworkChanged(NetStatusType)`，
 * 将因 Ethernet (`type == 9`) 误判传入的 `KREACHABLEUNKNOWN` 规范化为 `KREACHABLEVIAWIFI`。
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
    private val RENAMED_ORIGINAL_REGEX = Regex("^a_(\\d+)$")

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
        val clazz = com.tencent.mobileqq.msf.sdk.q.a::class.java
        return try {
            val direct = try {
                clazz.getDeclaredMethod("a_0", NetworkInfo::class.java, NetworkInfo::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
            val found = direct ?: clazz.declaredMethods
                .mapNotNull { m ->
                    val match = RENAMED_ORIGINAL_REGEX.matchEntire(m.name) ?: return@mapNotNull null
                    val params = m.parameterTypes
                    if (params.size == 2 &&
                        params[0] == NetworkInfo::class.java &&
                        params[1] == NetworkInfo::class.java
                    ) {
                        (match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) to m
                    } else null
                }
                .minByOrNull { it.first }
                ?.second
            if (found != null) {
                found.isAccessible = true
                cachedOriginal = found
                originalResolved = true
                if (found.name != "a_0") {
                    Log.i(TAG, "PicoNetCompat: resolved renamed original refresh method sdk.q.a.${found.name}")
                }
                found
            } else {
                originalResolved = true
                if (originalFailedLogged.compareAndSet(false, true)) {
                    Log.e(TAG, "PicoNetCompat: failed to resolve sdk.q.a.a_<N>(NetworkInfo, NetworkInfo)")
                }
                null
            }
        } catch (t: Throwable) {
            originalResolved = true
            if (originalFailedLogged.compareAndSet(false, true)) {
                Log.e(TAG, "PicoNetCompat: failed to resolve sdk.q.a original refresh method", t)
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
     * 先尝试按 WiFi 语义处理 Ethernet；非 Ethernet、中间态或异常时回落调用被重命名的原方法 `a_<N>`。
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
            Log.e(TAG, "PicoNetCompat: invoke sdk.q.a.${original.name} failed", cause)
        }
    }

    /**
     * 处理 `com.tencent.mobileqq.msf.sdk.q.a.a(activeNetInfo, extraNetInfo)` 中的 Ethernet 分支。
     *
     * @return `true` 表示已按 WiFi 语义完成处理（跳过原方法）；`false` 表示交给原方法完整执行。
     */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun handleEthernetRefresh(
        provider: com.tencent.mobileqq.msf.sdk.q.a,
        activeNetInfo: NetworkInfo?,
        extraNetInfo: NetworkInfo?
    ): Boolean {
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
            if (!isConnected && !isDisconnected) return false

            val access = resolveAccess() ?: return false

            try {
                access.subtypeField?.set(provider, activeNetInfo.subtypeName)
            } catch (_: Throwable) {
            }

            if (isConnected) {
                access.clearOther.invoke(provider)
                access.setWifi.invoke(provider)
                if (lastProviderLogState.getAndSet(1) != 1) {
                    Log.i(TAG, "PicoNetCompat: mapped Ethernet($typeName) CONNECTED -> WiFi in MSF NetworkProvider")
                }
            } else {
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
