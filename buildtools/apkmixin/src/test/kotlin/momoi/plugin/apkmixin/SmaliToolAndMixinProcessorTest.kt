package momoi.plugin.apkmixin

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.MultiDexIO
import momoi.plugin.apkmixin.utils.Smali
import momoi.plugin.apkmixin.utils.toClassDef
import momoi.plugin.apkmixin.utils.toClassDefOrThrow
import momoi.plugin.apkmixin.utils.toSmali
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmaliToolAndMixinProcessorTest {

    private fun newProcessor(tempDir: File): MixinProcessor {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val ext = ApkMixinExtension()
        return MixinProcessor(
            project = project,
            inputDexDir = File(tempDir, "inDex"),
            targetApkFile = File(tempDir, "target.apk"),
            extension = ext
        )
    }

    private fun invokePrivate(target: Any, name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val method = target.javaClass.getDeclaredMethod(name, *paramTypes)
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun toClassDefOrThrowReportsClearErrorWithClassHintAndApiLevel() {
        val brokenSmali = """
            .class public Lcom/example/Broken;
            .super Ljava/lang/Object;

            .method public bad()V
                .registers 1
                not-a-valid-dalvik-opcode v0, v1
            .end method
        """.trimIndent()

        val ex = assertFailsWith<IllegalStateException> {
            brokenSmali.toClassDefOrThrow(apiLevel = 29, classHint = "Lcom/example/Broken; (unit-test)")
        }
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains("Lcom/example/Broken; (unit-test)"), "Error must contain classHint: $msg")
        assertTrue(msg.contains("apiLevel=29"), "Error must contain apiLevel: $msg")
        assertTrue(
            msg.contains("parserErrors=") || msg.contains("walkerErrors=") || msg.contains("Failed to assemble"),
            "Error must include actionable error summary: $msg"
        )

        assertNull(brokenSmali.toClassDef(apiLevel = 29))
    }

    @Test
    fun scopedInvokeSuperRewritingAndModifierDeduplicationAndConstructorSkip() {
        val tempDir = Files.createTempDirectory("mixin-proc-test").toFile()
        try {
            val processor = newProcessor(tempDir)

            val targetSmaliText = """
                .class public Lcom/example/Target;
                .super Lcom/example/Base;

                .method public constructor <init>()V
                    .registers 1

                    invoke-direct {p0}, Lcom/example/Base;-><init>()V

                    return-void
                .end method

                .method public greet(Ljava/lang/String;)Ljava/lang/String;
                    .registers 2

                    return-object p1
                .end method

                .method public helper()V
                    .registers 1

                    return-void
                .end method

                .method public static staticWork()V
                    .registers 0

                    return-void
                .end method
            """.trimIndent()

            val mixinSmaliText = """
                .class public Lcom/example/TargetMixin;
                .super Lcom/example/Target;

                .annotation runtime Lmomoi/anno/mixin/Mixin;
                .end annotation

                .method public constructor <init>()V
                    .registers 1

                    invoke-direct {p0}, Lcom/example/Target;-><init>()V

                    return-void
                .end method

                .method public greet(Ljava/lang/String;)Ljava/lang/String;
                    .registers 5

                    const-string v0, "invoke-super {p0, p1}, Lcom/example/Target;->greet(Ljava/lang/String;)Ljava/lang/String;"

                    invoke-super {p0, p1}, Lcom/example/Target;->greet(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1

                    invoke-super/range {p0 .. p1}, Lcom/example/Target;->greet(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v2

                    invoke-super {p0}, Lcom/example/Target;->helper()V

                    return-object v1
                .end method

                .method public helper()V
                    .registers 1
                    .annotation runtime Lmomoi/anno/mixin/PrivateCall;
                    .end annotation

                    invoke-super {p0}, Lcom/example/Target;->helper()V

                    return-void
                .end method

                .method public static staticWork()V
                    .registers 0
                    .annotation runtime Lmomoi/anno/mixin/StaticHook;
                        value = Lcom/example/Target;
                    .end annotation

                    return-void
                .end method
            """.trimIndent()

            val targetDef = targetSmaliText.toClassDefOrThrow(21, "Lcom/example/Target;")
            val mixinDef = mixinSmaliText.toClassDefOrThrow(21, "Lcom/example/TargetMixin;")

            val srcDex = MutableDexFile().apply { classes.add(mixinDef) }
            val trgDex = MutableDexFile().apply { classes.add(targetDef) }

            @Suppress("UNCHECKED_CAST")
            val mixinClasses = invokePrivate(
                processor,
                "findMixinClasses",
                arrayOf(DexFile::class.java, DexFile::class.java),
                srcDex,
                trgDex
            ) as Map<ClassDef, ClassDef>
            assertEquals(1, mixinClasses.size)
            assertEquals("Lcom/example/Target;", mixinClasses[mixinDef]?.type)

            @Suppress("UNCHECKED_CAST")
            val resultPair = invokePrivate(
                processor,
                "processClasses",
                arrayOf(DexFile::class.java, DexFile::class.java, Map::class.java, Int::class.javaPrimitiveType!!),
                srcDex,
                trgDex,
                mixinClasses,
                21
            ) as Pair<MutableDexFile, Map<String, ClassDef>>

            val modified = assertNotNull(resultPair.second["Lcom/example/Target;"])
            val outSmali = Smali(modified.toSmali(21))

            // Original <init>()V was NOT duplicated or overwritten by mixin's <init>()V
            val constructors = outSmali.methods.filter { it.name == "<init>" }
            assertEquals(1, constructors.size, "Mixin <init> must be skipped")
            assertTrue(constructors.single().body.contains("Lcom/example/Base;-><init>()V"))

            // Overridden greet() renamed original to greet_0 and scoped invoke-super rewrite accurately.
            // Note: BaksmaliWriter emits implicit self-class references (without "Lcom/example/Target;->").
            val greetOrig = assertNotNull(outSmali.methods.find { it.name == "greet_0" })
            assertEquals("Ljava/lang/String;", greetOrig.returnType)

            val greetNew = assertNotNull(outSmali.methods.find { it.name == "greet" })
            assertTrue(
                greetNew.body.contains("invoke-virtual {p0, p1}, greet_0(Ljava/lang/String;)Ljava/lang/String;"),
                "Expected invoke-super on overridden method to rewrite to invoke-virtual greet_0:\n${greetNew.body}"
            )
            assertTrue(
                greetNew.body.contains("invoke-virtual/range {p0 .. p1}, greet_0(Ljava/lang/String;)Ljava/lang/String;"),
                "Expected invoke-super/range on overridden method to rewrite to invoke-virtual/range greet_0:\n${greetNew.body}"
            )
            assertTrue(
                greetNew.body.contains("invoke-super {p0}, helper()V"),
                "Unrelated invoke-super call MUST NOT be rewritten:\n${greetNew.body}"
            )
            assertTrue(
                greetNew.body.contains("const-string v0, \"invoke-super {p0, p1}, Lcom/example/Target;->greet(Ljava/lang/String;)Ljava/lang/String;\""),
                "String literal containing invoke-super text MUST NOT be rewritten:\n${greetNew.body}"
            )

            // @PrivateCall replaced public with a single private modifier (no duplicate visibility)
            val helperNew = assertNotNull(outSmali.methods.find { it.name == "helper" })
            assertEquals(listOf("private"), helperNew.modifiers)

            // @StaticHook on already-static method did NOT duplicate static modifier
            val staticWorkNew = assertNotNull(outSmali.methods.find { it.name == "staticWork" })
            assertEquals(1, staticWorkNew.modifiers.count { it == "static" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun staticHookTargetingMultipleClassesFailsFastAndMissingTargetThrows() {
        val tempDir = Files.createTempDirectory("mixin-errors-test").toFile()
        try {
            val processor = newProcessor(tempDir)
            val targetA = """
                .class public Lcom/example/A;
                .super Ljava/lang/Object;
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/A;")
            val targetB = """
                .class public Lcom/example/B;
                .super Ljava/lang/Object;
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/B;")

            val multiHook = """
                .class public Lcom/example/MultiHook;
                .super Ljava/lang/Object;

                .method public static hookA()V
                    .registers 0
                    .annotation runtime Lmomoi/anno/mixin/StaticHook;
                        value = Lcom/example/A;
                    .end annotation

                    return-void
                .end method

                .method public static hookB()V
                    .registers 0
                    .annotation runtime Lmomoi/anno/mixin/StaticHook;
                        value = Lcom/example/B;
                    .end annotation

                    return-void
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/MultiHook;")

            val srcDex = MutableDexFile().apply { classes.add(multiHook) }
            val trgDex = MutableDexFile().apply {
                classes.add(targetA)
                classes.add(targetB)
            }

            val multiErr = assertFailsWith<IllegalStateException> {
                invokePrivate(
                    processor,
                    "findMixinClasses",
                    arrayOf(DexFile::class.java, DexFile::class.java),
                    srcDex,
                    trgDex
                )
            }
            assertTrue(multiErr.message.orEmpty().contains("targeting multiple classes"))

            val emptyTargetDex = MutableDexFile()
            assertFailsWith<FileNotFoundException> {
                invokePrivate(
                    processor,
                    "findMixinClasses",
                    arrayOf(DexFile::class.java, DexFile::class.java),
                    srcDex,
                    emptyTargetDex
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun threeMixinsStackOnSameTargetClassWithoutDroppingPreviousHooks() {
        val tempDir = Files.createTempDirectory("mixin-stack3-test").toFile()
        try {
            val processor = newProcessor(tempDir)
            val targetDef = """
                .class public Lcom/example/StackTarget;
                .super Ljava/lang/Object;

                .method public ping()V
                    .registers 1

                    return-void
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/StackTarget;")

            fun mixinClass(name: String) = """
                .class public Lcom/example/$name;
                .super Lcom/example/StackTarget;

                .annotation runtime Lmomoi/anno/mixin/Mixin;
                .end annotation

                .method public ping()V
                    .registers 1

                    invoke-super {p0}, Lcom/example/StackTarget;->ping()V

                    return-void
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/$name;")

            val srcDex = MutableDexFile().apply {
                classes.add(mixinClass("MixinOne"))
                classes.add(mixinClass("MixinTwo"))
                classes.add(mixinClass("MixinThree"))
            }
            val trgDex = MutableDexFile().apply { classes.add(targetDef) }

            @Suppress("UNCHECKED_CAST")
            val mixinClasses = invokePrivate(
                processor,
                "findMixinClasses",
                arrayOf(DexFile::class.java, DexFile::class.java),
                srcDex,
                trgDex
            ) as Map<ClassDef, ClassDef>
            assertEquals(3, mixinClasses.size)

            @Suppress("UNCHECKED_CAST")
            val resultPair = invokePrivate(
                processor,
                "processClasses",
                arrayOf(DexFile::class.java, DexFile::class.java, Map::class.java, Int::class.javaPrimitiveType!!),
                srcDex,
                trgDex,
                mixinClasses,
                21
            ) as Pair<MutableDexFile, Map<String, ClassDef>>

            val modified = assertNotNull(resultPair.second["Lcom/example/StackTarget;"])
            val names = modified.methods.map { it.name }.toSet()
            assertEquals(setOf("ping", "ping_0", "ping_1", "ping_2"), names)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun staticHookDelegatesToRenamedOriginalAndPrivateTargetUsesInvokeDirect() {
        val tempDir = Files.createTempDirectory("mixin-static-private-test").toFile()
        try {
            val processor = newProcessor(tempDir)

            val targetDef = """
                .class public Lcom/example/Host;
                .super Ljava/lang/Object;

                .method public static compute(I)I
                    .registers 1

                    return p0
                .end method

                .method public static helperStatic(I)I
                    .registers 1

                    return p0
                .end method

                .method private secret(I)I
                    .registers 2

                    return p1
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/Host;")

            val staticHookDef = """
                .class public Lcom/example/HostStaticHook;
                .super Ljava/lang/Object;

                .method public static compute(I)I
                    .registers 5
                    .annotation runtime Lmomoi/anno/mixin/StaticHook;
                        value = Lcom/example/Host;
                    .end annotation

                    const-string v0, "invoke-static {p0}, Lcom/example/Host;->compute(I)I"

                    invoke-static {p0}, Lcom/example/Host;->compute(I)I
                    move-result v1

                    invoke-static/range {p0 .. p0}, Lcom/example/Host;->compute(I)I
                    move-result v2

                    invoke-static {p0}, Lcom/example/Host;->helperStatic(I)I
                    move-result v3

                    add-int/2addr v1, v2
                    add-int/2addr v1, v3

                    return v1
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/HostStaticHook;")

            val privateMixinDef = """
                .class public Lcom/example/HostPrivateMixin;
                .super Lcom/example/Host;

                .annotation runtime Lmomoi/anno/mixin/Mixin;
                .end annotation

                .method private secret(I)I
                    .registers 4

                    invoke-super {p0, p1}, Lcom/example/Host;->secret(I)I
                    move-result v0

                    invoke-super/range {p0 .. p1}, Lcom/example/Host;->secret(I)I
                    move-result v1

                    add-int/2addr v0, v1

                    return v0
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/HostPrivateMixin;")

            val srcDex = MutableDexFile().apply {
                classes.add(staticHookDef)
                classes.add(privateMixinDef)
            }
            val trgDex = MutableDexFile().apply { classes.add(targetDef) }

            @Suppress("UNCHECKED_CAST")
            val mixinClasses = invokePrivate(
                processor,
                "findMixinClasses",
                arrayOf(DexFile::class.java, DexFile::class.java),
                srcDex,
                trgDex
            ) as Map<ClassDef, ClassDef>
            assertEquals(2, mixinClasses.size)

            @Suppress("UNCHECKED_CAST")
            val resultPair = invokePrivate(
                processor,
                "processClasses",
                arrayOf(DexFile::class.java, DexFile::class.java, Map::class.java, Int::class.javaPrimitiveType!!),
                srcDex,
                trgDex,
                mixinClasses,
                21
            ) as Pair<MutableDexFile, Map<String, ClassDef>>

            val modified = assertNotNull(resultPair.second["Lcom/example/Host;"])
            val outSmali = Smali(modified.toSmali(21))

            // 1. @StaticHook rewrote invoke-static & invoke-static/range to compute_0 (no infinite recursion)
            assertNotNull(outSmali.methods.find { it.name == "compute_0" })
            val computeNew = assertNotNull(outSmali.methods.find { it.name == "compute" })
            assertTrue(
                computeNew.body.contains("invoke-static {p0}, compute_0(I)I"),
                "Expected invoke-static to delegate to compute_0:\n${computeNew.body}"
            )
            assertTrue(
                computeNew.body.contains("invoke-static/range {p0 .. p0}, compute_0(I)I"),
                "Expected invoke-static/range to delegate to compute_0:\n${computeNew.body}"
            )
            assertTrue(
                computeNew.body.contains("invoke-static {p0}, helperStatic(I)I"),
                "Unrelated static call must NOT be rewritten:\n${computeNew.body}"
            )
            assertTrue(
                computeNew.body.contains("const-string v0, \"invoke-static {p0}, Lcom/example/Host;->compute(I)I\""),
                "String literal must NOT be rewritten:\n${computeNew.body}"
            )
            assertFalse(
                computeNew.body.contains("invoke-static {p0}, compute(I)I"),
                "Must not recurse into compute(I)I:\n${computeNew.body}"
            )

            // 2. Private target method rewrote invoke-super(/range) to legal invoke-direct(/range), NOT invoke-virtual
            val secretOrig = assertNotNull(outSmali.methods.find { it.name == "secret_0" })
            assertTrue(secretOrig.isPrivate)
            val secretNew = assertNotNull(outSmali.methods.find { it.name == "secret" })
            assertTrue(
                secretNew.body.contains("invoke-direct {p0, p1}, secret_0(I)I"),
                "Private target delegation must use invoke-direct:\n${secretNew.body}"
            )
            assertTrue(
                secretNew.body.contains("invoke-direct/range {p0 .. p1}, secret_0(I)I"),
                "Private target range delegation must use invoke-direct/range:\n${secretNew.body}"
            )
            assertFalse(
                secretNew.body.contains("invoke-virtual"),
                "Must NEVER emit illegal invoke-virtual for private target:\n${secretNew.body}"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun privateTargetRewritesShortFormInvokeDirectAndRangeFromRealDex() {
        val tempDir = Files.createTempDirectory("mixin-private-short-direct-test").toFile()
        try {
            val processor = newProcessor(tempDir)

            val targetDef = """
                .class public Lcom/example/Host;
                .super Ljava/lang/Object;

                .method private secret(I)I
                    .registers 2

                    return p1
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/Host;")

            // Real compiled DEX invokes private methods on the mixin class via invoke-direct(/range),
            // which BaksmaliWriter(writer, this.type) disassembles in short form (secret(I)I).
            val privateDirectMixinDef = """
                .class public Lcom/example/HostPrivateDirectMixin;
                .super Lcom/example/Host;

                .annotation runtime Lmomoi/anno/mixin/Mixin;
                .end annotation

                .method private helperPrivate(I)I
                    .registers 2

                    return p1
                .end method

                .method private secret(I)I
                    .registers 6

                    const-string v0, "invoke-direct {p0, p1}, secret(I)I"

                    invoke-direct {p0, p1}, Lcom/example/HostPrivateDirectMixin;->secret(I)I
                    move-result v1

                    invoke-direct/range {p0 .. p1}, Lcom/example/HostPrivateDirectMixin;->secret(I)I
                    move-result v2

                    invoke-direct {p0, p1}, Lcom/example/HostPrivateDirectMixin;->helperPrivate(I)I
                    move-result v3

                    add-int/2addr v1, v2
                    add-int/2addr v1, v3

                    return v1
                .end method
            """.trimIndent().toClassDefOrThrow(21, "Lcom/example/HostPrivateDirectMixin;")

            // Confirm Baksmali indeed produces the short-form invoke-direct(/range) that caused R3-P1-3.
            val disassembledMixin = privateDirectMixinDef.toSmali(21)
            assertTrue(
                disassembledMixin.contains("invoke-direct {p0, p1}, secret(I)I"),
                "Expected Baksmali short-form invoke-direct in test fixture:\n$disassembledMixin"
            )
            assertTrue(
                disassembledMixin.contains("invoke-direct/range {p0 .. p1}, secret(I)I"),
                "Expected Baksmali short-form invoke-direct/range in test fixture:\n$disassembledMixin"
            )

            val srcDex = MutableDexFile().apply { classes.add(privateDirectMixinDef) }
            val trgDex = MutableDexFile().apply { classes.add(targetDef) }

            @Suppress("UNCHECKED_CAST")
            val mixinClasses = invokePrivate(
                processor,
                "findMixinClasses",
                arrayOf(DexFile::class.java, DexFile::class.java),
                srcDex,
                trgDex
            ) as Map<ClassDef, ClassDef>
            assertEquals(1, mixinClasses.size)

            @Suppress("UNCHECKED_CAST")
            val resultPair = invokePrivate(
                processor,
                "processClasses",
                arrayOf(DexFile::class.java, DexFile::class.java, Map::class.java, Int::class.javaPrimitiveType!!),
                srcDex,
                trgDex,
                mixinClasses,
                21
            ) as Pair<MutableDexFile, Map<String, ClassDef>>

            val modified = assertNotNull(resultPair.second["Lcom/example/Host;"])
            val outSmali = Smali(modified.toSmali(21))

            val secretOrig = assertNotNull(outSmali.methods.find { it.name == "secret_0" })
            assertTrue(secretOrig.isPrivate)
            val secretNew = assertNotNull(outSmali.methods.find { it.name == "secret" })
            assertTrue(
                secretNew.body.contains("invoke-direct {p0, p1}, secret_0(I)I"),
                "Short-form invoke-direct on private target must rewrite to secret_0:\n${secretNew.body}"
            )
            assertTrue(
                secretNew.body.contains("invoke-direct/range {p0 .. p1}, secret_0(I)I"),
                "Short-form invoke-direct/range on private target must rewrite to secret_0:\n${secretNew.body}"
            )
            assertFalse(
                secretNew.body.lines().any { it.trimStart().startsWith("invoke-direct") && it.contains(", secret(I)I") },
                "Must NOT leave recursive invoke-direct to secret(I)I:\n${secretNew.body}"
            )
            assertTrue(
                secretNew.body.contains("invoke-direct {p0, p1}, helperPrivate(I)I"),
                "Unrelated private invoke-direct must NOT be rewritten:\n${secretNew.body}"
            )
            assertTrue(
                secretNew.body.contains("const-string v0, \"invoke-direct {p0, p1}, secret(I)I\""),
                "String literal must NOT be rewritten:\n${secretNew.body}"
            )
            assertFalse(
                secretNew.body.contains("invoke-virtual"),
                "Must NEVER emit illegal invoke-virtual for private target:\n${secretNew.body}"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun writeDexOutputStripsStaleRootDexAndUsesResolvedApiLevel() {
        val tempDir = Files.createTempDirectory("mixin-write-dex-test").toFile()
        try {
            val targetApk = File(tempDir, "target.apk")
            ZipOutputStream(FileOutputStream(targetApk)).use { zos ->
                for (name in listOf("classes.dex", "classes2.dex", "classes3.dex", "assets/classes2.dex", "resources.arsc")) {
                    zos.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.DEFLATED
                        time = 0L
                    })
                    zos.write("orig-$name".toByteArray())
                    zos.closeEntry()
                }
            }

            val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
            val ext = ApkMixinExtension().apply {
                output.outputDir = "dist"
                output.mixinApkFileName = "mixin-out.apk"
            }
            val processor = MixinProcessor(
                project = project,
                inputDexDir = File(tempDir, "inDex"),
                targetApkFile = targetApk,
                extension = ext
            )

            val keepClass = """
                .class public Lcom/example/KeepMe;
                .super Ljava/lang/Object;

                .method public constructor <init>()V
                    .registers 1

                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

                    return-void
                .end method
            """.trimIndent().toClassDefOrThrow(28, "Lcom/example/KeepMe;")
            val trgDex = ImmutableDexFile(Opcodes.forApi(21), listOf(keepClass))
            val newDex = MutableDexFile()

            ZipFile(targetApk).use { targetZip ->
                invokePrivate(
                    processor,
                    "writeDexOutput",
                    arrayOf(DexFile::class.java, DexFile::class.java, Map::class.java, ZipFile::class.java, Int::class.javaPrimitiveType!!),
                    trgDex,
                    newDex,
                    emptyMap<String, ClassDef>(),
                    targetZip,
                    28
                )
            }

            val outApk = File(tempDir, "dist/mixin-out.apk")
            assertTrue(outApk.isFile, "Expected output mixin APK at ${outApk.absolutePath}")
            ZipFile(outApk).use { zip ->
                assertNotNull(zip.getEntry("classes.dex"), "New classes.dex must exist")
                assertNull(zip.getEntry("classes2.dex"), "Stale classes2.dex must be removed when DEX count shrinks")
                assertNull(zip.getEntry("classes3.dex"), "Stale classes3.dex must be removed when DEX count shrinks")
                assertNotNull(zip.getEntry("assets/classes2.dex"), "Non-root asset must remain untouched")
                assertNotNull(zip.getEntry("resources.arsc"))
            }

            // Output DEX must be rewritten with resolved apiLevel (28 -> DEX 039), not trgDex's lower API 21 (DEX 035)
            ZipFile(outApk).use { zip ->
                val dexEntry = assertNotNull(zip.getEntry("classes.dex"))
                val magic = zip.getInputStream(dexEntry).use { it.readNBytes(8) }
                assertEquals("dex\n039\u0000", String(magic, Charsets.US_ASCII))
            }
            val writtenDex = MultiDexIO.readDexFile(true, outApk, BasicDexFileNamer(), null, null)
            assertEquals(Opcodes.forDexVersion(39).api, writtenDex.opcodes.api)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildToolsVersionComparatorSortsSemVerNumericallyAndRanksStableAbovePrerelease() {
        val versions = listOf("9.0.0", "35.0.0-rc2", "35.0.0-rc10", "35.0.0", "34.0.0", "33.0.2")
        val sortedDesc = versions.sortedWith { a, b -> MixinPlugin.compareBuildToolsVersion(b, a) }
        assertEquals(
            listOf("35.0.0", "35.0.0-rc10", "35.0.0-rc2", "34.0.0", "33.0.2", "9.0.0"),
            sortedDesc
        )
    }
}
