package momoi.plugin.apkmixin

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.rewriter.DexFileRewriter
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.MethodRewriter
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
import momoi.plugin.apkmixin.utils.findClass
import momoi.plugin.apkmixin.utils.getDexCount
import momoi.plugin.apkmixin.utils.info
import momoi.plugin.apkmixin.utils.lifecycle
import momoi.plugin.apkmixin.utils.toClassDefOrThrow
import momoi.plugin.apkmixin.utils.toSmali
import org.gradle.api.Project
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

class MixinProcessor(
    private val project: Project,
    private val inputDexDir: File = project.layout.buildDirectory.dir("intermediates/dex/release/mergeDexRelease").get().asFile,
    private val targetApkFile: File = project.projectDir.child("mixin").child(
        project.extensions.getByType(ApkMixinExtension::class.java).targetApk.orEmpty()
    ),
    private val extension: ApkMixinExtension = project.extensions.getByType(ApkMixinExtension::class.java)
) {

    fun processMixin() {
        val (srcDex, trgDex) = loadDexFiles()
        val apiLevel = resolveApiLevel(srcDex, trgDex)
        val mixinClasses = findMixinClasses(srcDex, trgDex)
        lifecycle("Processing Mixin (apiLevel=$apiLevel)...")
        val stopwatch = Stopwatch.createStarted()
        val (newClassesDex, modifiedClasses) = processClasses(srcDex, trgDex, mixinClasses, apiLevel)
        lifecycle("Mixin processed in ${stopwatch.elapsed(TimeUnit.MILLISECONDS)}ms")
        ZipFile(targetApkFile).use { targetZipFile ->
            writeDexOutput(trgDex, newClassesDex, modifiedClasses, targetZipFile, apiLevel)
        }
    }

    private fun resolveApiLevel(srcDex: DexFile, targetDex: DexFile): Int =
        listOf(srcDex.opcodes.api, targetDex.opcodes.api)
            .filter { it > 0 }
            .maxOrNull()
            ?.coerceAtLeast(21)
            ?: 34

    private fun loadDexFiles(): Pair<DexFile, DexFile> {
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

        return srcDex to trgDex
    }

    private fun findMixinClasses(srcDex: DexFile, targetDex: DexFile): Map<ClassDef, ClassDef> {
        val mixinClasses = LinkedHashMap<ClassDef, ClassDef>()
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
                            val ref = (a.elements.firstOrNull()?.value as? TypeEncodedValue)?.value
                                ?: throw IllegalStateException(
                                    "Malformed @StaticHook annotation on ${srcDef.type}->${m.name}"
                                )
                            val targetClass = targetDex.findClass(ref)
                                ?: throw FileNotFoundException(
                                    "Can not find @StaticHook target class $ref referenced by ${srcDef.type}->${m.name}"
                                )
                            val existing = mixinClasses[srcDef]
                            if (existing != null && existing.type != targetClass.type) {
                                throw IllegalStateException(
                                    "Class ${srcDef.type} declares @StaticHook targeting multiple classes " +
                                        "(${existing.type} and ${targetClass.type}); split into separate hook classes."
                                )
                            }
                            if (existing == null) {
                                mixinClasses[srcDef] = targetClass
                                info("Found StaticHook Class: ${srcDef.type} to $ref")
                                mixinClassCount++
                            }
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
        mixinClasses: Map<ClassDef, ClassDef>,
        apiLevel: Int
    ): Pair<MutableDexFile, Map<String, ClassDef>> {
        val newClassesDex = MutableDexFile()
        val changedDef = mutableMapOf<ClassDef, ClassDef>()
        val modifiedClasses = mutableMapOf<String, ClassDef>()

        srcDex.classes.forEach { srcDef ->
            var content = srcDef.toSmali(apiLevel)
            mixinClasses.forEach { (rs, rt) ->
                content = content.replace(rs.type, rt.type)
            }

            when {
                !mixinClasses.containsKey(srcDef) -> {
                    if (targetDex.findClass(srcDef.type) == null) {
                        newClassesDex.classes.add(
                            content.toClassDefOrThrow(apiLevel, classHint = srcDef.type)
                        )
                    }
                }
                else -> mixinToTargetClass(srcDef, content, mixinClasses, changedDef, modifiedClasses, apiLevel)
            }
        }

        return newClassesDex to modifiedClasses
    }

    private fun mixinToTargetClass(
        srcDef: ClassDef,
        content: String,
        mixinClasses: Map<ClassDef, ClassDef>,
        lastClassesMap: MutableMap<ClassDef, ClassDef>,
        modifiedClasses: MutableMap<String, ClassDef>,
        apiLevel: Int
    ) {
        val rawTrgDef = mixinClasses[srcDef]
            ?: throw IllegalStateException("Missing target class mapping for mixin ${srcDef.type}")
        val trgDef = lastClassesMap[rawTrgDef] ?: rawTrgDef
        val srcSmali = Smali(content)
        val trgSmali = Smali(trgDef.toSmali(apiLevel))

        processMethods(srcSmali, trgSmali)
        processFields(srcSmali, trgSmali)

        val newTrgDef = trgSmali.toText().toClassDefOrThrow(
            apiLevel = apiLevel,
            classHint = "${rawTrgDef.type} (mixin ${srcDef.type})"
        )
        // 必须以 rawTrgDef 为键，确保 >=3 个 Mixin 注入同一目标类时依次叠加而非覆盖丢弃前序修改
        lastClassesMap[rawTrgDef] = newTrgDef
        modifiedClasses[newTrgDef.type] = newTrgDef
    }

    private fun processMethods(srcSmali: Smali, trgSmali: Smali) {
        srcSmali.methods.forEach { srcMethod ->
            // 跳过 Mixin 类自身的默认构造/静态初始化器，避免覆写宿主类构造链
            if (srcMethod.name == "<init>" || srcMethod.name == "<clinit>") {
                return@forEach
            }
            processMethodStaticHook(srcMethod)
            trgSmali.findMethod(srcMethod)?.let { trgMethod ->
                processMethodBody(srcMethod, trgMethod, trgSmali)
            }
            trgSmali.methods.add(srcMethod)
        }
    }

    private fun processMethodStaticHook(srcMethod: SmaliMethod) {
        if (srcMethod.body.contains("Lmomoi/anno/mixin/StaticHook;") && "static" !in srcMethod.modifiers) {
            srcMethod.modifiers.add("static")
        }
    }

    private fun processMethodBody(srcMethod: SmaliMethod, trgMethod: SmaliMethod, trgSmali: Smali) {
        if (srcMethod.body.contains("Lmomoi/anno/mixin/PrivateCall;")) {
            srcMethod.modifiers.removeAll(setOf("public", "protected", "private"))
            srcMethod.modifiers.add(0, "private")
        }
        val rawCall = trgSmali.getMethodCall(trgMethod)
        val rawShortCall = "${trgMethod.name}(${trgMethod.args})${trgMethod.returnType}"
        val isStatic = trgMethod.isStatic || srcMethod.isStatic
        val baseName = trgMethod.name
        var index = 0
        var candidateName: String
        do {
            candidateName = "${baseName}_${index++}"
        } while (trgSmali.methods.any { it !== trgMethod && it.name == candidateName })
        trgMethod.name = candidateName
        val renamedCall = trgSmali.getMethodCall(trgMethod)

        // 精准重写当前被覆盖方法的委托调用，避免全局字符串替换误伤无关调用或字符串字面量：
        // - 静态方法（如 @StaticHook）：将指向 rawCall / 隐式 rawShortCall 的 invoke-static(/range) 重定向至重命名原实现，防止无限递归；
        // - 实例方法：将指向 rawCall 的 invoke-super(/range) 重定向至重命名原实现；当目标原方法为 private 时使用合法的 invoke-direct(/range)。
        if (isStatic) {
            val staticCallRegex = Regex(
                """^([ \t]*)invoke-(?:static|super)(/range)?([ \t]+\{[^}]*\},[ \t]*)(?:${Regex.escape(rawCall)}|${Regex.escape(rawShortCall)})([ \t]*(?:#.*)?)$""",
                RegexOption.MULTILINE
            )
            srcMethod.body = staticCallRegex.replace(srcMethod.body) { match ->
                val indent = match.groupValues[1]
                val rangeSuffix = match.groupValues[2]
                val registers = match.groupValues[3]
                val trailing = match.groupValues[4]
                "${indent}invoke-static${rangeSuffix}${registers}${renamedCall}${trailing}"
            }
        } else {
            val targetInvoke = if (trgMethod.isPrivate) "invoke-direct" else "invoke-virtual"
            val sourceInvokePattern = if (trgMethod.isPrivate) "invoke-(?:super|direct)" else "invoke-super"
            val instanceCallRegex = Regex(
                """^([ \t]*)${sourceInvokePattern}(/range)?([ \t]+\{[^}]*\},[ \t]*)${Regex.escape(rawCall)}([ \t]*(?:#.*)?)$""",
                RegexOption.MULTILINE
            )
            srcMethod.body = instanceCallRegex.replace(srcMethod.body) { match ->
                val indent = match.groupValues[1]
                val rangeSuffix = match.groupValues[2]
                val registers = match.groupValues[3]
                val trailing = match.groupValues[4]
                "${indent}${targetInvoke}${rangeSuffix}${registers}${renamedCall}${trailing}"
            }
        }
    }

    private fun processFields(srcSmali: Smali, trgSmali: Smali) {
        srcSmali.fields.forEach { srcField ->
            if (!trgSmali.fields.any { it.name == srcField.name }) {
                trgSmali.fields.add(srcField)
            }
        }
    }

    private fun writeDexOutput(
        trgDex: DexFile,
        newDex: DexFile,
        modifiedClasses: Map<String, ClassDef>,
        targetZipFile: ZipFile,
        apiLevel: Int
    ) {
        val stripVibrationCalls = PERMISSION_VIBRATE in extension.removeUsesPermissions
        val strippedVibrationMethods = ConcurrentHashMap<String, Int>()
        val strippedVibrationCalls = AtomicInteger(0)
        val rewriter = createDexRewriter(newDex, modifiedClasses, stripVibrationCalls, strippedVibrationMethods, strippedVibrationCalls, apiLevel)
        lifecycle("Writing dex...")
        val stopWatchWriteDex = Stopwatch.createStarted()
        val namer = BasicDexFileNamer()
        val outputDexDir = project.layout.buildDirectory.dir("mixinDex").get().asFile
        if (outputDexDir.exists() && !outputDexDir.deleteRecursively()) {
            throw IOException("Failed to clean ${outputDexDir.absolutePath}")
        }
        if (!outputDexDir.mkdirs()) {
            throw IOException("Failed to create ${outputDexDir.absolutePath}")
        }

        val threadCount = if (extension.useProcessorCountAsThreadCount) {
            Runtime.getRuntime().availableProcessors()
        } else {
            targetZipFile.getDexCount(namer)
        }.coerceAtLeast(1)

        MultiDexIO.writeDexFile(
            /* multiDex = */ true,
            /* threadCount = */ threadCount,
            /* file = */ outputDexDir,
            /* namer = */ namer,
            /* dexFile = */ rewriter.dexFileRewriter.rewrite(trgDex),
            /* maxDexPoolSize = */ DexIO.DEFAULT_MAX_DEX_POOL_SIZE,
            /* logger = */ null
        )

        if (stripVibrationCalls) {
            lifecycle("Stripped ${strippedVibrationCalls.get()} vibration call(s) across ${strippedVibrationMethods.size} method(s)")
        }

        stopWatchWriteDex.stop()
        lifecycle("Dex written in ${stopWatchWriteDex.elapsed(TimeUnit.MILLISECONDS)}ms")

        lifecycle("Zip to mixin apk...")
        val stopWatchZipToApk = Stopwatch.createStarted()
        val mixinApkFile = project.outputDir(extension).child(extension.output.mixinApkFileName)
        mixinApkFile.parentFile?.mkdirs()
        // 每次从干净的宿主 APK 复制，避免残留旧构建中的多余 dex 或已移除的 extraFiles 条目
        targetApkFile.copyTo(mixinApkFile, overwrite = true)

        val filesToAdd = LinkedHashMap<String, File>()
        outputDexDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            ?.forEach { filesToAdd[it.name] = it }

        // 额外内嵌文件(如 assets/pico/webui.html),按 zip 内路径为键；文件缺失时快速失败
        extension.extraFiles.entries.sortedBy { it.key }.forEach { (entryName, file) ->
            if (!file.isFile) {
                throw FileNotFoundException(
                    "extraFiles entry '$entryName' is missing or not a regular file: ${file.absolutePath}"
                )
            }
            filesToAdd[entryName] = file
        }
        // 写入新 DEX 前剔除根目录下所有旧 classes*.dex，避免多 DEX 数量缩减 (M < K) 时残留旧宿主 DEX
        ZipUtil.addOrReplaceFilesInZip(
            zipFile = mixinApkFile,
            filesToAdd = filesToAdd,
            removePredicate = { entryName -> ROOT_DEX_REGEX.matches(entryName) }
        )

        stopWatchZipToApk.stop()
        lifecycle("Mixin apk written in ${stopWatchZipToApk.elapsed(TimeUnit.MILLISECONDS)}ms")
    }

    private fun createDexRewriter(
        newClassesDex: DexFile,
        modifiedClasses: Map<String, ClassDef>,
        stripVibrationCalls: Boolean,
        strippedVibrationMethods: ConcurrentHashMap<String, Int>,
        strippedVibrationCalls: AtomicInteger,
        apiLevel: Int
    ): DexRewriter {
        val targetOpcodes = Opcodes.forApi(apiLevel)
        return DexRewriter(object : RewriterModule() {
            override fun getDexFileRewriter(rewriters: Rewriters): Rewriter<DexFile?> {
                return object : DexFileRewriter(rewriters) {
                    override fun rewrite(value: DexFile): DexFile {
                        return super.rewrite(
                            ImmutableDexFile(
                                targetOpcodes,
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

            override fun getMethodRewriter(rewriters: Rewriters): Rewriter<Method> {
                if (!stripVibrationCalls) return super.getMethodRewriter(rewriters)
                return object : MethodRewriter(rewriters) {
                    override fun rewrite(value: Method): Method {
                        val impl = value.implementation ?: return super.rewrite(value)
                        if (impl.instructions.none(::isVibrationCall)) return super.rewrite(value)
                        val baseImpl = rewriters.methodImplementationRewriter.rewrite(impl)
                        val mutable = MutableMethodImplementation(baseImpl)
                        val targetIndices = mutable.instructions.withIndex()
                            .filter { isVibrationCall(it.value) }
                            .map { it.index }
                        if (targetIndices.isEmpty()) return super.rewrite(value)
                        for (index in targetIndices) {
                            mutable.replaceInstruction(index, BuilderInstruction10x(Opcode.NOP))
                        }
                        val frozenImpl: MethodImplementation = ImmutableMethodImplementation.of(mutable) ?: mutable
                        val methodKey = "${value.definingClass}->${value.name}(${value.parameterTypes.joinToString("")})${value.returnType}"
                        if (strippedVibrationMethods.putIfAbsent(methodKey, targetIndices.size) == null) {
                            strippedVibrationCalls.addAndGet(targetIndices.size)
                            info("Stripped ${targetIndices.size} vibration call(s) in $methodKey")
                        }
                        return object : RewrittenMethod(value) {
                            override fun getImplementation(): MethodImplementation = frozenImpl
                        }
                    }
                }
            }
        })
    }

    private fun isVibrationCall(instruction: Instruction): Boolean {
        if (instruction !is ReferenceInstruction || instruction.referenceType != ReferenceType.METHOD) {
            return false
        }
        when (instruction.opcode) {
            Opcode.INVOKE_VIRTUAL,
            Opcode.INVOKE_VIRTUAL_RANGE,
            Opcode.INVOKE_SUPER,
            Opcode.INVOKE_SUPER_RANGE,
            Opcode.INVOKE_DIRECT,
            Opcode.INVOKE_DIRECT_RANGE -> Unit
            else -> return false
        }
        val ref = instruction.reference as? MethodReference ?: return false
        if (ref.returnType != "V") return false
        return when (ref.definingClass) {
            "Landroid/os/Vibrator;",
            "Landroid/os/VibratorManager;" -> ref.name == "vibrate" || ref.name == "cancel"
            "Landroid/app/NotificationChannel;" -> ref.name == "enableVibration" || ref.name == "setVibrationPattern"
            else -> false
        }
    }

    companion object {
        private const val PERMISSION_VIBRATE = "android.permission.VIBRATE"
        internal val ROOT_DEX_REGEX = Regex("^classes\\d*\\.dex$")
    }
}
