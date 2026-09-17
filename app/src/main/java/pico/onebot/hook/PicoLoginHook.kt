package pico.onebot.hook

import android.os.Bundle
import android.view.View
import com.tencent.qqnt.account.login.ui.LoginWithStateFragment
import com.tencent.qqnt.account.login.ui.QrLoginFragment
import momoi.anno.mixin.Mixin
import pico.onebot.core.PicoLog
import pico.onebot.kernel.PicoLoginManager

@Mixin
abstract class PicoQrLoginHook : QrLoginFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            PicoLoginManager.onQrFragmentCreated(this)
        } catch (t: Throwable) {
            PicoLog.e("PicoQrLoginHook.onCreate error", t)
        }
    }

    override fun onDestroy() {
        try {
            PicoLoginManager.onQrFragmentDestroyed(this)
        } catch (t: Throwable) {
            PicoLog.e("PicoQrLoginHook.onDestroy error", t)
        }
        super.onDestroy()
    }

    // LoginQrCodeStateCallback:二维码图片字节到达。混淆名随宿主版本漂移:官方 9.0.7(2563) 是 S,官方 9.0.1(2340) 是 J,NWear 9.0.3 改版是 P;
    // 认法:接口里唯一带 byte[] 参数的方法,体内是 ThreadManagerV2.excute。
    override fun S(picBuf: ByteArray) {
        super.S(picBuf)
        try {
            PicoLoginManager.onQrReceived(this, picBuf)
        } catch (t: Throwable) {
            PicoLog.e("PicoQrLoginHook.S error", t)
        }
    }

    // LoginQrCodeStateCallback:手机已扫码、等待确认。官方 9.0.7 是 x();认法:体内 post 的提示串是 string/qr_scanned_tips。
    override fun x() {
        super.x()
        try {
            PicoLoginManager.onQrScanned(this)
        } catch (t: Throwable) {
            PicoLog.e("PicoQrLoginHook.x error", t)
        }
    }

    // LoginQrCodeStateCallback:二维码过期。官方 9.0.7 是 C,官方 9.0.1 是 K,NWear 9.0.3 是 Q;
    // 认法:被 LoginQrCode$queryTask 的到期检查调用的那个无参方法(体内 binding.d.post 提示"已过期")。
    override fun C() {
        super.C()
        try {
            PicoLoginManager.onQrExpired(this)
        } catch (t: Throwable) {
            PicoLog.e("PicoQrLoginHook.C error", t)
        }
    }
}

@Mixin
abstract class PicoLoginWithStateHook : LoginWithStateFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            PicoLoginManager.onStateViewCreated(this, view)
        } catch (t: Throwable) {
            PicoLog.e("PicoLoginWithStateHook.onViewCreated error", t)
        }
    }
}
