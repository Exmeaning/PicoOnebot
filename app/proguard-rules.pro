-keep interface * extends java.lang.annotation.Annotation { *; }
-keep @momoi.anno.mixin.Mixin class * {
    *;
}

-keep class com.tencent.** { *; }
-keep class androidx.lifecycle.** { *; }