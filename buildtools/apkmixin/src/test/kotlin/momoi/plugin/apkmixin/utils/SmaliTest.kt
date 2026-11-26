package momoi.plugin.apkmixin.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmaliTest {

    @Test
    fun parsesConstructorsStaticInitPackagePrivateAndDollarFields() {
        val src = """
            .class public Lcom/example/Sample${'$'}Inner;
            .super Ljava/lang/Object;

            .field packageCount:I

            .field private final synthetic this${'$'}0:Lcom/example/Sample;

            .field public static final ${'$'}VALUES:[Ljava/lang/String;

            .method static constructor <clinit>()V
                .registers 0

                return-void
            .end method

            .method public constructor <init>()V
                .registers 1

                invoke-direct {p0}, Ljava/lang/Object;-><init>()V

                return-void
            .end method

            .method public declared-synchronized compute${'$'}impl(I)Ljava/lang/String;
                .registers 3

                const/4 v0, 0x0

                return-object v0
            .end method
        """.trimIndent()

        val smali = Smali(src)

        assertEquals("Lcom/example/Sample${'$'}Inner;", smali.type)
        assertEquals("Ljava/lang/Object;", smali.superClass)

        assertEquals(3, smali.fields.size)
        val pkgField = smali.fields.first { it.name == "packageCount" }
        assertTrue(pkgField.modifiers.isEmpty(), "Package-private field should have no modifiers")
        assertEquals("I", pkgField.type)
        assertEquals(".field packageCount:I", pkgField.toCode().trim())

        val outerRef = smali.fields.first { it.name == "this${'$'}0" }
        assertEquals(listOf("private", "final", "synthetic"), outerRef.modifiers)
        assertEquals("Lcom/example/Sample;", outerRef.type)

        val dollarValues = smali.fields.first { it.name == "${'$'}VALUES" }
        assertEquals(listOf("public", "static", "final"), dollarValues.modifiers)
        assertEquals("[Ljava/lang/String;", dollarValues.type)

        assertEquals(3, smali.methods.size)
        val clinit = smali.methods.firstOrNull { it.name == "<clinit>" }
        assertNotNull(clinit)
        assertEquals(listOf("static", "constructor"), clinit.modifiers)
        assertEquals("", clinit.args)
        assertEquals("V", clinit.returnType)

        val init = smali.methods.firstOrNull { it.name == "<init>" }
        assertNotNull(init)
        assertEquals(listOf("public", "constructor"), init.modifiers)
        assertEquals("V", init.returnType)

        val impl = smali.methods.firstOrNull { it.name == "compute${'$'}impl" }
        assertNotNull(impl)
        assertEquals(listOf("public", "declared-synchronized"), impl.modifiers)
        assertEquals("I", impl.args)
        assertEquals("Ljava/lang/String;", impl.returnType)

        // Re-rendered smali must assemble cleanly back to a ClassDef
        val assembled = smali.toText().toClassDefOrThrow(apiLevel = 21, classHint = smali.type)
        assertEquals("Lcom/example/Sample${'$'}Inner;", assembled.type)
        assertEquals(3, assembled.fields.count())
        assertEquals(3, assembled.methods.count())
    }

    @Test
    fun distinguishesMethodsByReturnTypeAndFormatsMethodCall() {
        val src = """
            .class public Lcom/example/Overloads;
            .super Ljava/lang/Object;

            .method public value(I)I
                .registers 2

                return p1
            .end method

            .method public value(I)Ljava/lang/String;
                .registers 3

                const/4 v0, 0x0

                return-object v0
            .end method
        """.trimIndent()

        val smali = Smali(src)
        assertEquals(2, smali.methods.size)

        val intQuery = SmaliMethod(mutableListOf("public"), "value", "I", "I", "")
        val strQuery = SmaliMethod(mutableListOf("public"), "value", "I", "Ljava/lang/String;", "")
        val boolQuery = SmaliMethod(mutableListOf("public"), "value", "I", "Z", "")

        val intMatch = smali.findMethod(intQuery)
        val strMatch = smali.findMethod(strQuery)
        assertNotNull(intMatch)
        assertNotNull(strMatch)
        assertEquals("I", intMatch.returnType)
        assertEquals("Ljava/lang/String;", strMatch.returnType)
        assertNull(smali.findMethod(boolQuery))

        assertTrue(intMatch.isEquals(intQuery))
        assertFalse(intMatch.isEquals(strQuery))
        assertEquals("Lcom/example/Overloads;->value(I)I", smali.getMethodCall(intMatch))
        assertEquals(
            "Lcom/example/Overloads;->value(I)Ljava/lang/String;",
            smali.getMethodCall(strMatch)
        )
    }

    @Test
    fun deduplicatesModifiersInFieldsAndMethods() {
        assertEquals(
            listOf("public", "static", "final"),
            Smali.parseModifiers("public   static public final static ")
        )

        val field = SmaliField(
            modifiers = listOf("private", "static", "private", "", "final"),
            name = "flag",
            type = "Z",
            body = ""
        )
        assertEquals(".field private static final flag:Z", field.toCode())

        val method = SmaliMethod(
            modifiers = mutableListOf("private", "static", "private", "  ", "static"),
            name = "run",
            args = "",
            returnType = "V",
            body = ""
        )
        assertTrue(method.isPrivate)
        assertEquals(".method private static run()V", method.sign)
    }
}
