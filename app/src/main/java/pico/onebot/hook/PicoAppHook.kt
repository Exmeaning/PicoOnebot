package pico.onebot.hook

import android.content.Context
import android.util.Log
import com.tencent.qqnt.watch.app.WatchApplication
import momoi.anno.mixin.Mixin
import pico.onebot.PicoBoot
import pico.onebot.kernel.QimeiCompat

/**
 * 唯一的注入点。
 *
 * 为什么挂 `attachBaseContext` 而不是 `onCreate`:`WatchApplication` 自己声明了
 * `attachBaseContext`,mixin 替换的是**已存在的方法体**(`super.` 调到原实现);
 * 而它并未声明 `onCreate`,挂上去会变成"新增方法",super 链的语义没那么稳。
 *
 * 这里只做一件事:把控制权交给 [PicoBoot],且**绝不让异常冒泡**回宿主。
 */
@Mixin
abstract class PicoAppHook : WatchApplication() {

    override fun attachBaseContext(base: Context?) {
        try {
            if (base != null) {
                QimeiCompat.prepareEarly(base, PicoBoot.currentProcessName()?.endsWith(":MSF") == true)
            }
        } catch (t: Throwable) {
            Log.e("PicoOB", "early identity compatibility failed", t)
        }
        super.attachBaseContext(base)
        try {
            PicoBoot.start(this)
        } catch (t: Throwable) {
            Log.e("PicoOB", "PicoBoot.start failed", t)
        }
    }
}
