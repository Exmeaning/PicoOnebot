package momoi.plugin.apkmixin

import groovy.lang.Closure
import momoi.plugin.apkmixin.utils.child
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.util.internal.ConfigureUtil
import java.io.File
import kotlin.math.sign

const val DEFAULT_MIXIN_APK_NAME = "mixin.apk"
const val DEFAULT_UNSIGNED_APK_NAME = "unsigned.apk"
const val DEFAULT_SIGNED_APK_NAME = "signed.apk"

open class ApkMixinExtension {
    /** Optional manifest override. Leave blank to preserve the host APK version. */
    var versionName = ""
    /** Optional literal application label written to the host manifest. */
    var applicationLabel = ""
    /** Permissions appended to the host manifest after the injected dex is merged. */
    val usesPermissions: MutableSet<String> = linkedSetOf()
    /** Optional accessibility service implemented by the injected dex. */
    var accessibilityServiceClass = ""
    var accessibilityServiceLabel = ""
    var targetApk: String? = null
    var output = OutputExtension()
    var signing = SigningExtension()
    var useProcessorCountAsThreadCount = false

    /**
     * 编译期额外塞进 APK 的文件:`zip 内路径 -> 本地文件`。
     * 走与 dex 相同的 [utils.ZipUtil.addOrReplaceFilesInZip],再由 apksigner 统一重签。
     * 例:`extraFiles["assets/pico/webui.html"] = file("../web/dist/index.html")`。
     */
    val extraFiles: MutableMap<String, File> = mutableMapOf()

    fun output(action: Action<OutputExtension>) {
        action.execute(output)
    }

    fun signing(action: Action<SigningExtension>){
        action.execute(signing)
    }
}

open class SigningExtension {
    var enabled = true
    var keyFile: File? = null
    var certFile: File? = null
}

open class OutputExtension {
    var outputDir: String = "dist"
    var unsignedFileName: String = DEFAULT_UNSIGNED_APK_NAME
    var signedFileName: String = DEFAULT_SIGNED_APK_NAME
    var mixinApkFileName: String = DEFAULT_MIXIN_APK_NAME
}

internal fun Project.outputDir(extension: ApkMixinExtension) = projectDir.child(extension.output.outputDir)
