package pico.onebot.hook

import android.os.Bundle
import com.tencent.qqnt.watch.mainframe.MainActivity
import momoi.anno.mixin.Mixin
import pico.onebot.core.PicoLog
import pico.onebot.ui.PicoPanel

/**
 * QQ 首页(登录成功后才会进到 MainActivity)。`onCreate` 是宿主自己声明的方法,mixin 替换方法体、
 * `super.` 调回原实现;等窗口贴好、宿主的启动页盖完再叠引导面板,所以延后一拍 post。
 * 与所有 hook 一样:绝不让异常冒泡回宿主。
 */
@Mixin
abstract class PicoMainHook : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.decorView.postDelayed({
                try {
                    if (!isFinishing) PicoPanel.maybeShow(this)
                } catch (t: Throwable) {
                    PicoLog.e("PicoPanel show failed", t)
                }
            }, 1500)
        } catch (t: Throwable) {
            PicoLog.e("PicoMainHook.onCreate error", t)
        }
    }

    override fun onDestroy() {
        try {
            PicoPanel.onActivityDestroyed(this)
        } catch (t: Throwable) {
            PicoLog.w("PicoMainHook.onDestroy: " + t.message)
        }
        super.onDestroy()
    }
}
