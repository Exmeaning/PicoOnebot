package momoi.plugin.apkmixin

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import momoi.plugin.apkmixin.utils.Smali
import momoi.plugin.apkmixin.utils.toClassDef
import momoi.plugin.apkmixin.utils.toClassDefOrThrow
import momoi.plugin.apkmixin.utils.toSmali
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
