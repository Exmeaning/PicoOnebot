package momoi.plugin.apkmixin

import com.android.apksigner.ApkSignerTool
import com.wind.meditor.ManifestEditorMain
import momoi.plugin.apkmixin.utils.child
import momoi.plugin.apkmixin.utils.ensureDirExists
import momoi.plugin.apkmixin.utils.lifecycle
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

const val APK_MIXIN_EXTENSION_NAME = "apkMixin"

class MixinPlugin : Plugin<Project> {

    private lateinit var extension: ApkMixinExtension

    override fun apply(project: Project) {
        extension = project.extensions.create(APK_MIXIN_EXTENSION_NAME, ApkMixinExtension::class.java)
        configureMixinTasks(project)
    }

    private fun configureMixinTasks(project: Project) {
        createMixinBaseTask(project)
        createMixinDebugTask(project)
        createMixinReleaseTask(project)
    }

    private fun createMixinBaseTask(project: Project) {
        project.tasks.register("MixinApk", MixinApkTask::class.java) {
            val targetApkName = extension.targetApk ?: throw IllegalArgumentException("targetApk must not be null")
            it.mixinAppDex = project.layout.buildDirectory.dir("intermediates/dex/release/mergeDexRelease").get().asFileTree
            it.targetAppFile = project.layout.projectDirectory.dir("mixin").file(targetApkName)
            it.doLast {
                createMetadata(project)
            }
        }
    }

    private fun createMixinDebugTask(project: Project) {
        project.tasks.create("MixinApk-debug") {
            it.dependsOn("MixinApk")
            it.doLast {
                processManifest(project, isDebug = true)
                sign(project)
                createMetadata(project, if (extension.signing.enabled) extension.output.signedFileName else extension.output.unsignedFileName)
            }
        }
    }

    private fun createMixinReleaseTask(project: Project) {
        project.tasks.create("MixinApk-release") {
            it.dependsOn("MixinApk")
            it.doLast {
                processManifest(project, isDebug = false)
                sign(project)
                createMetadata(project, if (extension.signing.enabled) extension.output.signedFileName else extension.output.unsignedFileName)
            }
        }
    }

    private fun processManifest(project: Project, isDebug: Boolean) {
        lifecycle("Processing manifest...")
        val args = mutableListOf(
            project.outputDir(extension).child(extension.output.mixinApkFileName).absolutePath,
            "-o",
            project.outputDir(extension).child(extension.output.unsignedFileName).absolutePath,
            "-d", if (isDebug) "1" else "0",
            "--force"
        )
        if (extension.versionName.isNotBlank()) {
            args.addAll(args.lastIndex, listOf("-vn", extension.versionName))
        }
        if (extension.applicationLabel.isNotBlank()) {
            args.addAll(args.lastIndex, listOf("-aa", "android-label:${extension.applicationLabel}"))
        }
        ManifestEditorMain.main(*args.toTypedArray())
        ManifestPatcher.patch(
            project.outputDir(extension).child(extension.output.unsignedFileName),
            extension.usesPermissions,
            extension.accessibilityServiceClass,
            extension.accessibilityServiceLabel
        )
    }

    private fun sign(project: Project) {
        if (extension.signing.enabled) {
            lifecycle("Signing...")
            val unsignedApkFile = project.outputDir(extension).child(extension.output.unsignedFileName)
            val signedApkFile = project.outputDir(extension).child(extension.output.signedFileName)
            ApkSignerTool.main(arrayOf(
                "sign",
                "--key", extension.signing.keyFile?.absolutePath,
                "--cert", extension.signing.certFile?.absolutePath,
                "--out", signedApkFile.absolutePath,
                unsignedApkFile.absolutePath
            ))
            unsignedApkFile.delete()
            lifecycle("Signed: ${signedApkFile.absolutePath}")
        }
    }

    private fun createMetadata(
        project: Project,
        fileName: String = extension.output.mixinApkFileName
    ) {
        createRedirectFile(project)
        createOutputMetadataFile(project, fileName)
    }

    private fun createRedirectFile(project: Project) {
        project.layout.buildDirectory.file("intermediates/apk_ide_redirect_file/debug/createDebugApkListingFileRedirect/redirect.txt").get().asFile
            .ensureDirExists()
            .writeText("""
                #- File Locator -
                listingFile=../../../../../dist/output-metadata.json
            """.trimIndent())
    }

    private fun createOutputMetadataFile(
        project: Project,
        fileName: String
    ) {
        project.outputDir(extension).child("output-metadata.json").writeText("""
            {
              "version": 3,
              "artifactType": {
                "type": "APK",
                "kind": "Directory"
              },
              "applicationId": "com.tencent.qqlite",
              "variantName": "debug",
              "elements": [
                {
                  "type": "SINGLE",
                  "filters": [],
                  "attributes": [],
                  "outputFile": "$fileName"
                }
              ],
              "elementType": "File",
              "minSdkVersionForDexing": 24
            }
        """.trimIndent())
    }
}

abstract class MixinApkTask: DefaultTask() {

    private val extension = project.extensions.findByType(ApkMixinExtension::class.java) ?: ApkMixinExtension()

    @get:InputFiles
    abstract var mixinAppDex: FileCollection

    @get:InputFile
    abstract var targetAppFile: RegularFile

    @get:OutputFile
    val outputMixinFile: RegularFile = project.layout.projectDirectory.dir(extension.output.outputDir).file(extension.output.mixinApkFileName)

    init {
        dependsOn("mergeDexRelease")
    }

    @TaskAction
    fun execute() {
        val processor = MixinProcessor(project, mixinAppDex.singleFile.parentFile, targetAppFile.asFile)
        processor.processMixin()
    }

}
