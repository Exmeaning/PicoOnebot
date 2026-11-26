# Preserve runtime annotations and metadata required by ApkMixin bytecode injection
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod

-keep interface * extends java.lang.annotation.Annotation { *; }
-keep class momoi.anno.mixin.** { *; }

# Keep all @Mixin target hook classes and their members
-keep @momoi.anno.mixin.Mixin class * {
    *;
}

# Keep classes and methods annotated with @StaticHook or @PrivateCall
-keepclasseswithmembers class * {
    @momoi.anno.mixin.StaticHook <methods>;
}
-keepclassmembers class * {
    @momoi.anno.mixin.StaticHook <methods>;
    @momoi.anno.mixin.PrivateCall <methods>;
}

# Keep PicoOnebot hook/mixin classes, @StaticHook field holders, and runtime compat helpers
-keep class pico.onebot.hook.** { *; }
-keep class pico.onebot.hook.PicoNetCompat { *; }
-keep class pico.onebot.PicoBoot { *; }

# Keep AccessibilityService declared via ApkMixin manifest injection
-keep class pico.onebot.keepalive.PicoKeepAliveService { *; }

# Keep host QQ & AndroidX classes referenced by hooks and reflection
-keep class com.tencent.** { *; }
-keep class androidx.lifecycle.** { *; }
