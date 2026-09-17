plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("momoi.plugin.apkmixin") apply true
}

// 版本号唯一来源:gradle.properties 的 picoVersion(CI 打 tag 时用 -PpicoVersion=... 覆盖)
val picoVersion: String = (project.findProperty("picoVersion") as String?) ?: "0.0.0-dev"
val picoGitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

android {
    // namespace 只影响 BuildConfig / R 的包名;真正的 applicationId 必须等于宿主包名
    namespace = "pico.onebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tencent.qqlite"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = picoVersion

        buildConfigField("String", "PICO_VERSION", "\"$picoVersion\"")
        buildConfigField("String", "PICO_GIT_SHA", "\"$picoGitSha\"")
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
}

apkMixin {
    targetApk = "source.apk"
    applicationLabel = "PicoOnebot"
    usesPermissions.add("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
    accessibilityServiceClass = "pico.onebot.keepalive.PicoKeepAliveService"
    accessibilityServiceLabel = "PicoOnebot 保活服务"
    useProcessorCountAsThreadCount = project.properties["useProcessorCountAsThreadCount"] == "true"

    // 编译期把 vite 打出的 WebUI 单文件内嵌成 assets/pico/webui.html(见 docs/design §3.3)
    extraFiles["assets/pico/webui.html"] = file("../web/dist/index.html")
    extraFiles["res/rRE.png"] = file("mixin/pico_icon_144.png")
    extraFiles["res/7vh.png"] = file("mixin/pico_icon_144.png")
    extraFiles["res/cRs.png"] = file("mixin/pico_icon_144.png")

    signing {
        keyFile = file("mixin/testkey.pk8")
        certFile = file("mixin/testkey.x509.pem")
    }

    output {
        signedFileName = "PicoOnebot_${picoVersion}.apk"
    }
}
