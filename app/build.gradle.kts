plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("momoi.plugin.apkmixin") apply true
}

fun escapeJavaString(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

// 版本号唯一来源:gradle.properties 的 picoVersion(CI 打 tag 时用 -PpicoVersion=... 覆盖)
val picoVersionRegex = Regex("^v?[0-9]+\\.[0-9]+(\\.[0-9]+)?([-.][0-9A-Za-z]+)*$")
val picoVersionRaw: String = ((project.findProperty("picoVersion") as String?) ?: "0.0.0-dev").trim()
require(picoVersionRegex.matches(picoVersionRaw)) {
    "Invalid picoVersion '$picoVersionRaw': must match SemVer format (e.g. 0.20, 1.2.3, 1.2.3-rc.1)"
}
val picoVersion: String = picoVersionRaw.removePrefix("v")

val picoGitShaRaw: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")
val picoGitSha: String = if (Regex("^[0-9a-fA-F]{7,40}$").matches(picoGitShaRaw)) picoGitShaRaw else "unknown"

// 可选签名密钥覆盖：优先读取 Gradle 属性 (-PPICO_SIGN_KEY/-PpicoSignKey)，其次读取环境变量 PICO_SIGN_KEY / PICO_SIGN_CERT。
// 未配置时保留默认 testkey.pk8 以兼容既有用户的原地 APK 升级。
val signKeyPath: String? = ((project.findProperty("picoSignKey") as String?)
    ?: (project.findProperty("PICO_SIGN_KEY") as String?)
    ?: System.getenv("PICO_SIGN_KEY"))?.trim()?.takeIf { it.isNotEmpty() }
val signCertPath: String? = ((project.findProperty("picoSignCert") as String?)
    ?: (project.findProperty("PICO_SIGN_CERT") as String?)
    ?: System.getenv("PICO_SIGN_CERT"))?.trim()?.takeIf { it.isNotEmpty() }
require((signKeyPath == null) == (signCertPath == null)) {
    "Both PICO_SIGN_KEY and PICO_SIGN_CERT must be provided together (or neither to use default testkey)."
}

android {
    // namespace 只影响 BuildConfig / R 的包名;真正的 applicationId 必须等于宿主包名
    namespace = "pico.onebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tencent.qqlite"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = picoVersion

        buildConfigField("String", "PICO_VERSION", "\"${escapeJavaString(picoVersion)}\"")
        buildConfigField("String", "PICO_GIT_SHA", "\"${escapeJavaString(picoGitSha)}\"")
        buildConfigField("String", "PICO_REPO", "\"https://github.com/Exmeaning/PicoOnebot\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":apkmixin-annotation"))
    // 宿主 APK 的类(dex2jar → gen-dep 处理后的 source.jar),只参与编译,不打进包
    compileOnly(fileTree("./libs"))
    // 宿主类的 androidx 父类(Fragment / AppCompatButton):gen-dep 把 androidx 从 source.jar 里剔掉了,
    // 编译时从官方 artifact 拿;ViewBinding 官方没有独立 artifact,用 src/main/java/androidx/viewbinding 的存根
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.androidx.fragment)
    compileOnly(libs.androidx.core)
    testImplementation(libs.junit)
}

apkMixin {
    targetApk = "source.apk"
    applicationLabel = "PicoOnebot"
    usesPermissions.add("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
    removeUsesPermissions.add("android.permission.VIBRATE")
    accessibilityServiceClass = "pico.onebot.keepalive.PicoKeepAliveService"
    accessibilityServiceLabel = "PicoOnebot 保活服务"
    useProcessorCountAsThreadCount = project.properties["useProcessorCountAsThreadCount"] == "true"

    // 编译期把 vite 打出的 WebUI 单文件内嵌成 assets/pico/webui.html(见 docs/design §3.3)
    extraFiles["assets/pico/webui.html"] = file("../web/dist/index.html")
    extraFiles["res/rRE.png"] = file("mixin/pico_icon_144.png")
    extraFiles["res/7vh.png"] = file("mixin/pico_icon_144.png")
    extraFiles["res/cRs.png"] = file("mixin/pico_icon_144.png")

    signing {
        keyFile = signKeyPath?.let { file(it) } ?: file("mixin/testkey.pk8")
        certFile = signCertPath?.let { file(it) } ?: file("mixin/testkey.x509.pem")
    }

    output {
        signedFileName = "PicoOnebot_${picoVersion}.apk"
    }
}
