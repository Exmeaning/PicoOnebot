package pico.onebot.hook;

import com.tencent.common.config.AppSetting;

import momoi.anno.mixin.StaticHook;

/** Fields expected reflectively by FEKit but absent from the watch QQ build. */
public final class AppSettingCompatHook {
    public static boolean isDebugVersion;
    public static boolean isPublicVersion;

    private AppSettingCompatHook() {
    }

    @StaticHook(AppSetting.class)
    public static void picoBindAppSetting() {
    }
}
