package momoi.plugin.apkmixin

import com.android.apksigner.ApkSignerTool
import com.wind.meditor.ManifestEditorMain
import momoi.plugin.apkmixin.utils.child
import momoi.plugin.apkmixin.utils.ensureDirExists
import momoi.plugin.apkmixin.utils.lifecycle
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.util.Properties

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
        project.tasks.register("MixinApk", MixinApkTask::class.java) { task ->
            task.mixinAppDexDir.set(project.layout.buildDirectory.dir("intermediates/dex/release/mergeDexRelease"))
            task.targetAppFile.set(
                project.layout.projectDirectory.file(
                    project.provider {
                        val targetApkName = extension.targetApk
                            ?: throw IllegalArgumentException("targetApk must not be null")
                        "mixin/$targetApkName"
                    }
                )
            )
            task.outputMixinFile.set(
                project.layout.projectDirectory.file(
                    project.provider {
                        "${extension.output.outputDir}/${extension.output.mixinApkFileName}"
                    }
                )
            )
            task.doLast {
                createMetadata(project)
            }
        }
    }

    private fun createMixinDebugTask(project: Project) {
        project.tasks.register("MixinApk-debug") { task ->
            task.dependsOn("MixinApk")
            task.doLast {
                processManifest(project, isDebug = true)
                sign(project)
                createMetadata(project, if (extension.signing.enabled) extension.output.signedFileName else extension.output.unsignedFileName)
            }
        }
    }

    private fun createMixinReleaseTask(project: Project) {
        project.tasks.register("MixinApk-release") { task ->
            task.dependsOn("MixinApk")
            task.doLast {
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
            extension.removeUsesPermissions,
            extension.accessibilityServiceClass,
            extension.accessibilityServiceLabel
        )
    }

    private fun sign(project: Project) {
        if (extension.signing.enabled) {
            val keyFile = extension.signing.keyFile
            val certFile = extension.signing.certFile
            require(keyFile != null && keyFile.isFile) {
                "Signing keyFile is missing or not a file: ${keyFile?.absolutePath}"
            }
            require(certFile != null && certFile.isFile) {
                "Signing certFile is missing or not a file: ${certFile?.absolutePath}"
            }

            val unsignedApkFile = project.outputDir(extension).child(extension.output.unsignedFileName)
            val signedApkFile = project.outputDir(extension).child(extension.output.signedFileName)
            val alignedApkFile = alignApkIfAvailable(project, unsignedApkFile)
            val apkToSign = alignedApkFile ?: unsignedApkFile

            lifecycle("Signing...")
            try {
                ApkSignerTool.main(arrayOf(
                    "sign",
                    "--key", keyFile.absolutePath,
                    "--cert", certFile.absolutePath,
                    "--out", signedApkFile.absolutePath,
                    apkToSign.absolutePath
                ))
            } finally {
                if (alignedApkFile != null && alignedApkFile.exists()) {
                    alignedApkFile.delete()
                }
            }
            unsignedApkFile.delete()
            lifecycle("Signed: ${signedApkFile.absolutePath}")
        }
    }

    private fun alignApkIfAvailable(project: Project, unsignedApkFile: File): File? {
        val zipalign = findZipalignExecutable(project)
        if (zipalign == null) {
            lifecycle("zipalign not found in Android SDK build-tools or PATH; signing without zipalign")
            return null
        }
        val alignedFile = File(unsignedApkFile.parentFile, "${unsignedApkFile.nameWithoutExtension}.aligned.apk")
        lifecycle("Aligning APK with ${zipalign.absolutePath}...")
        val process = ProcessBuilder(
            zipalign.absolutePath,
            "-f",
            "-p",
            "4",
            unsignedApkFile.absolutePath,
            alignedFile.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            alignedFile.delete()
            throw IOException("zipalign failed with exit code $exitCode:\n$output")
        }
        return alignedFile
    }

    private fun findZipalignExecutable(project: Project): File? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val exeName = if (isWindows) "zipalign.exe" else "zipalign"

        val sdkDirs = buildList {
            System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            val localProps = File(project.rootDir, "local.properties")
            if (localProps.isFile) {
                runCatching {
                    Properties().apply { localProps.inputStream().use(::load) }
                        .getProperty("sdk.dir")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(File(it)) }
                }
            }
        }

        for (sdkDir in sdkDirs) {
            val buildToolsDir = File(sdkDir, "build-tools")
            if (!buildToolsDir.isDirectory) continue
            val candidate = buildToolsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.map { File(it, exeName) }
                ?.firstOrNull { it.isFile && it.canExecute() }
            if (candidate != null) return candidate
        }

        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, exeName) }
            .firstOrNull { it.isFile && it.canExecute() }
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

abstract class MixinApkTask : DefaultTask() {

    private val extension: ApkMixinExtension
        get() = project.extensions.findByType(ApkMixinExtension::class.java) ?: ApkMixinExtension()

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mixinAppDexDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetAppFile: RegularFileProperty

    @get:Input
    val removeUsesPermissions: List<String>
        get() = extension.removeUsesPermissions.sorted()

    @get:Input
    val extraFileEntries: List<String>
        get() = extension.extraFiles.entries
            .sortedBy { it.key }
            .map { "${it.key}=${it.value.absolutePath}" }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val extraFiles: FileCollection
        get() = project.files(
            extension.extraFiles.entries
                .sortedBy { it.key }
                .map { it.value }
        )

    @get:OutputFile
    abstract val outputMixinFile: RegularFileProperty

    init {
        dependsOn("mergeDexRelease")
    }

    @TaskAction
    fun execute() {
        val processor = MixinProcessor(
            project = project,
            inputDexDir = mixinAppDexDir.get().asFile,
            targetApkFile = targetAppFile.get().asFile,
            extension = extension
        )
        processor.processMixin()
    }
}
