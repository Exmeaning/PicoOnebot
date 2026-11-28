package momoi.plugin.apkmixin.utils

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.smali.SmaliOptions
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.StringReader
import java.io.StringWriter

fun ClassDef.toSmali(apiLevel: Int = 21): String {
    val writer = StringWriter()
    val options = BaksmaliOptions().apply {
        this.apiLevel = apiLevel
    }
    BaksmaliWriter(writer, this.type).use {
        ClassDefinition(options, this)
            .writeTo(it)
    }
    return writer.toString()
}

fun String.toClassDefOrThrow(apiLevel: Int = 21, classHint: String = "<unknown>"): ClassDef {
    val options = SmaliOptions().apply {
        this.apiLevel = apiLevel
    }
    StringReader(this).use { reader ->
        try {
            val lexer = smaliFlexLexer(reader, apiLevel)
            val tokens = CommonTokenStream(lexer)
            val parser = smaliParser(tokens)
            parser.setApiLevel(apiLevel)
            parser.setAllowOdex(options.allowOdexOpcodes)
            parser.setVerboseErrors(options.verboseErrors)

            val result = parser.smali_file()
            if (lexer.numberOfSyntaxErrors > 0 || parser.numberOfSyntaxErrors > 0) {
                throw IllegalStateException(
                    "Smali parse failed for $classHint (apiLevel=$apiLevel, " +
                        "lexerErrors=${lexer.numberOfSyntaxErrors}, parserErrors=${parser.numberOfSyntaxErrors})"
                )
            }
            val tree = result.tree
            val treeStream = CommonTreeNodeStream(tree)
            treeStream.tokenStream = tokens
            val dexGen = smaliTreeWalker(treeStream)
            dexGen.setApiLevel(apiLevel)
            dexGen.setVerboseErrors(options.verboseErrors)
            val dexBuilder = DexBuilder(Opcodes.forApi(apiLevel))
            dexGen.setDexBuilder(dexBuilder)
            val classDef = dexGen.smali_file()
            if (dexGen.numberOfSyntaxErrors > 0 || classDef == null) {
                throw IllegalStateException(
                    "Smali assembly failed for $classHint (apiLevel=$apiLevel, " +
                        "walkerErrors=${dexGen.numberOfSyntaxErrors})"
                )
            }
            return classDef
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to assemble smali for $classHint (apiLevel=$apiLevel)", e)
        }
    }
}

fun String.toClassDef(apiLevel: Int = 21): ClassDef? =
    runCatching { toClassDefOrThrow(apiLevel) }.getOrNull()
