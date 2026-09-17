package momoi.plugin.apkmixin.utils

import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import java.io.StringReader
import java.io.StringWriter

fun ClassDef.toSmali(): String {
    val writer = StringWriter()
    BaksmaliWriter(writer, this.type).use {
        ClassDefinition(BaksmaliOptions(), this)
            .writeTo(it)
    }
    return writer.toString()
}

fun String.toClassDef(apiLevel: Int = 21): ClassDef? {
    val options = SmaliOptions()
    options.apiLevel = apiLevel
    val reader = StringReader(this)
    try {
        val lexer = smaliFlexLexer(reader, apiLevel)
        val tokens = CommonTokenStream(lexer)
        val parser = smaliParser(tokens)
        parser.setApiLevel(apiLevel)
        parser.setAllowOdex(options.allowOdexOpcodes)
        parser.setVerboseErrors(options.verboseErrors)

        val result = parser.smali_file()
        if (parser.numberOfSyntaxErrors > 0) {
            return null
        }
        val tree = result.tree
        val treeStream = CommonTreeNodeStream(tree)
        treeStream.tokenStream = tokens
        val dexGen = smaliTreeWalker(treeStream)
        dexGen.setApiLevel(apiLevel)
        dexGen.setVerboseErrors(options.verboseErrors)
        val dexBuilder = DexBuilder(Opcodes.forApi(apiLevel))
        dexGen.setDexBuilder(dexBuilder)
        return dexGen.smali_file()
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    } finally {
        reader.close()
    }
}