package momoi.plugin.apkmixin

import com.android.tools.smali.dexlib2.ValueType
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.rewriter.DexFileRewriter
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.google.common.base.Stopwatch
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexIO
import momoi.plugin.apkmixin.utils.Smali
import momoi.plugin.apkmixin.utils.SmaliMethod
import momoi.plugin.apkmixin.utils.ZipUtil
import momoi.plugin.apkmixin.utils.child
import momoi.plugin.apkmixin.utils.info
import momoi.plugin.apkmixin.utils.findClass
import momoi.plugin.apkmixin.utils.getDexCount
import momoi.plugin.apkmixin.utils.lifecycle
import momoi.plugin.apkmixin.utils.toClassDef
import momoi.plugin.apkmixin.utils.toSmali
import org.gradle.api.Project
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.getValue

class MixinProcessor(
    private val project: Project,
    private val inputDexDir: File = project.layout.buildDirectory.dir("intermediates/dex/release/mergeDexRelease").get().asFile,
    private val targetApkFile: File = project.projectDir.child("mixin").child(extension.targetApk.orEmpty()),
    private val extension: ApkMixinExtension = project.extensions.getByType(ApkMixinExtension::class.java)
) {

    fun processMixin() {
        val (srcDex, trgDex, targetZipFile) = loadDexFiles()
        val mixinClasses = findMixinClasses(srcDex, trgDex)
        lifecycle("Processing Mixin...")
        val stopwatch = Stopwatch.createStarted()
        val (newClassesDex, modifiedClasses) = processClasses(srcDex, trgDex, mixinClasses)
        lifecycle("Mixin processed in ${stopwatch.elapsed(TimeUnit.MILLISECONDS)}ms")
        writeDexOutput(trgDex, newClassesDex, modifiedClasses, targetZipFile)
    }

    private fun loadDexFiles(): Triple<DexFile, DexFile, ZipFile> {
        val srcDex = MultiDexIO.readDexFile(
            /* multiDex = */ true,
            /* file = */ inputDexDir,
            /* namer = */ BasicDexFileNamer(),
            /* opcodes = */ null,
            /* logger = */ null
        )

        val trgDex = MultiDexIO.readDexFile(
            /* multiDex = */ true,
            /* file = */ targetApkFile,
            /* namer = */ BasicDexFileNamer(),
            /* opcodes = */ null,
            /* logger = */ null
        )
        val targetZipFile = ZipFile(targetApkFile)

        return Triple(srcDex, trgDex, targetZipFile)
    }

    private fun findMixinClasses(srcDex: DexFile, targetDex: DexFile): Map<ClassDef, ClassDef> {
        val mixinClasses = mutableMapOf<ClassDef, ClassDef>()
        var mixinClassCount = 0
        srcDex.classes.forEach { srcDef ->
            if (srcDef.annotations.any { it.type == "Lmomoi/anno/mixin/Mixin;" }) {
                mixinClasses[srcDef] = srcDef.superclass?.let { s -> targetDex.findClass(s) }
                    ?: throw FileNotFoundException(
                        "Can not find mixin ${srcDef.type} target class ${srcDef.superclass}"
                    )
                info("Found Mixin Class: ${srcDef.type} to ${srcDef.superclass}")
                mixinClassCount++
            } else {
                srcDef.methods.forEach { m ->
                    m.annotations.forEach { a ->
                        if (a.type == "Lmomoi/anno/mixin/StaticHook;") {
                            val ref = (a.elements.first().value as TypeEncodedValue).value
                            mixinClasses[srcDef] = targetDex.findClass(ref)!!
                            mixinClassCount++
                        }
                    }
                }
            }
        }
        lifecycle("Found $mixinClassCount mixin classes")
        return mixinClasses
    }

    private fun processClasses(
        srcDex: DexFile,
        targetDex: DexFile,
        mixinClasses: Map<ClassDef, ClassDef>
    ): Pair<MutableDexFile, Map<String, ClassDef>> {
        val newClassesDex = MutableDexFile()
        val changedDef = mutableMapOf<ClassDef, ClassDef>()
        val modifiedClasses = mutableMapOf<String, ClassDef>()

        srcDex.classes.forEach { srcDef ->
            var content = srcDef.toSmali()
            mixinClasses.forEach { (rs, rt) ->
                content = content.replace(rs.type, rt.type)
            }

            when {
                !mixinClasses.containsKey(srcDef) -> {
                    if (targetDex.findClass(srcDef.type) == null) {
                        newClassesDex.classes.add(content.toClassDef()!!)
                    }
                }
                else -> mixinToTargetClass(srcDef, content, mixinClasses, changedDef, modifiedClasses)
            }
        }

        return newClassesDex to modifiedClasses
    }

    private fun mixinToTargetClass(
        srcDef: ClassDef,
        content: String,
        mixinClasses: Map<ClassDef, ClassDef>,
        lastClassesMap: MutableMap<ClassDef, ClassDef>,
        modifiedClasses: MutableMap<String, ClassDef>
    ) {
        val rawTrgDef = mixinClasses[srcDef]!!
        val trgDef = lastClassesMap[rawTrgDef] ?: rawTrgDef
        val srcSmali = Smali(content)
        val trgSmali = Smali(trgDef.toSmali())

        processMethods(srcSmali, trgSmali)
        processFields(srcSmali, trgSmali)

        val newTrgDef = trgSmali.toText().toClassDef()!!
        lastClassesMap[trgDef] = newTrgDef
        modifiedClasses[newTrgDef.type] = newTrgDef
    }

    private fun processMethods(srcSmali: Smali, trgSmali: Smali) {
        srcSmali.methods.forEach { srcMethod ->
            processMethodStaticHook(srcMethod)
            trgSmali.findMethod(srcMethod)?.let { trgMethod ->
                processMethodBody(srcMethod, trgMethod, trgSmali)
            }
            trgSmali.methods.add(srcMethod)
        }
    }

    private fun processMethodStaticHook(srcMethod: SmaliMethod) {
        if (srcMethod.body.contains("Lmomoi/anno/mixin/StaticHook;")) {
            srcMethod.modifiers.add("static")
        }
    }

    private fun processMethodBody(srcMethod: SmaliMethod, trgMethod: SmaliMethod, trgSmali: Smali) {
        if (srcMethod.body.contains("Lmomoi/anno/mixin/PrivateCall;")) {
            srcMethod.modifiers.remove("public")
            srcMethod.modifiers.remove("protected")
            srcMethod.modifiers.add("private")
        }
        srcMethod.body = srcMethod.body.replace("invoke-super", "invoke-virtual")
        val rawCall = trgSmali.getMethodCall(trgMethod)
        var index = 0
        do {
            trgMethod.name += "_${index++}"
        } while (trgSmali.methods.filter { it.name == trgMethod.name }.size > 1)
        srcMethod.body = srcMethod.body.replace(rawCall, trgSmali.getMethodCall(trgMethod))
    }

    private fun processFields(srcSmali: Smali, trgSmali: Smali) {
        srcSmali.fields.forEach { srcField ->
            if (!trgSmali.fields.any { it.name == srcField.name }) {
                trgSmali.fields.add(srcField)
            }
        }
    }

    private fun writeDexOutput(trgDex: DexFile, newDex: DexFile, modifiedClasses: Map<String, ClassDef>, targetZipFile: ZipFile) {
        val rewriter = createDexRewriter(newDex, modifiedClasses)
        lifecycle("Writing dex...")
        val stopWatchWriteDex = Stopwatch.createStarted()
        val namer = BasicDexFileNamer()
        val outputDexDir = project.layout.buildDirectory.dir("mixinDex").get().asFile.also {
            if (!it.isDirectory && ((it.exists() && !it.delete()) || !it.mkdirs())) {
                throw IOException("Failed to create ${it.absolutePath}")
            }
        }
        
        MultiDexIO.writeDexFile(
            /* multiDex = */ true,
            /* threadCount = */ if (extension.useProcessorCountAsThreadCount) Runtime.getRuntime().availableProcessors() else targetZipFile.getDexCount(namer),
            /* file = */ outputDexDir,
            /* namer = */ namer,
            /* dexFile = */ rewriter.dexFileRewriter.rewrite(trgDex),
            /* maxDexPoolSize = */ DexIO.DEFAULT_MAX_DEX_POOL_SIZE,
            /* logger = */ null
        )

        stopWatchWriteDex.stop()
        lifecycle("Dex written in ${stopWatchWriteDex.elapsed(TimeUnit.MILLISECONDS)}ms")

        lifecycle("Zip to mixin apk...")
        val stopWatchZipToApk = Stopwatch.createStarted()
        val mixinApkFile = project.outputDir(extension).child(extension.output.mixinApkFileName)
        if (mixinApkFile.length() != targetApkFile.length()) {
            targetApkFile.copyTo(mixinApkFile, overwrite = true)
        }
        val filesToAdd = LinkedHashMap<String, File>()
        outputDexDir.listFiles()?.forEach { filesToAdd[it.name] = it }
        // 额外内嵌文件(如 assets/pico/webui.html),按 zip 内路径为键
        extension.extraFiles.forEach { (entryName, file) ->
            if (file.isFile) {
                filesToAdd[entryName] = file
            } else {
                lifecycle("extraFiles: 跳过不存在的文件 $entryName -> ${file.absolutePath}")
            }
        }
        ZipUtil.addOrReplaceFilesInZip(mixinApkFile, filesToAdd)

        stopWatchZipToApk.stop()
        lifecycle("Mixin apk written in ${stopWatchZipToApk.elapsed(TimeUnit.MILLISECONDS)}ms")
    }

    private fun createDexRewriter(newClassesDex: DexFile, modifiedClasses: Map<String, ClassDef>): DexRewriter {
        return DexRewriter(object : RewriterModule() {
            override fun getDexFileRewriter(rewriters: Rewriters): Rewriter<DexFile?> {
                return object : DexFileRewriter(rewriters) {
                    override fun rewrite(value: DexFile): DexFile {
                        return super.rewrite(
                            ImmutableDexFile(
                                value.opcodes,
                                buildList {
                                    addAll(value.classes)
                                    addAll(newClassesDex.classes)

                                    val types = modifiedClasses.keys
                                    removeAll { it.type in types }
                                    addAll(modifiedClasses.values)
                                }
                            )
                        )
                    }
                }
            }
        })
    }
}
