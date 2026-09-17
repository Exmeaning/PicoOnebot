# app/mixin/ — APK 混合构建输入

| 文件 | 来源 | 说明 |
|---|---|---|
| `source.apk` | [QQ 手表版 9.0.7](https://download.wearstore.asia/APK/QQ%E6%89%8B%E8%A1%A8%E7%89%88%209.0.7%20%28Android%204.4%20%2B%29.apk) | ApkMixin 的注入目标，可用 `scripts/fetch-base.sh` 下载 |
| `testkey.pk8` / `testkey.x509.pem` | AOSP 公开测试签名 | 重签混合包;签名与官方不同 |

同时需要 `app/libs/source.jar`(宿主类,只参与编译):`source.apk` → dex2jar → `buildtools/gen-dep/raw.jar` → `./gradlew :gen-dep:run` → `app/libs/source.jar`。

