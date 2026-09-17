package momoi.plugin.apkmixin.utils

import java.io.File

class Smali(src: String) {
    var otherLines: String
    val superClass: String
    val type: String
    val fields = mutableListOf<SmaliField>()
    val methods = mutableListOf<SmaliMethod>()
    fun findMethod(other: SmaliMethod) = methods.find { it.name == other.name && it.args == other.args }
    fun getMethodCall(method: SmaliMethod) = "$type->${method.name}(${method.args})${method.returnType}"

    fun toText(): String {
        return "$otherLines\n${
            fields.join { field -> "\n.field ${field.modifiers.join { "$it " }}${field.name}:${field.type}${field.body}" }
        }\n${
            methods.join { method -> "\n${method.sign}${method.body}" }
        }"
    }

    init {
        var text = src
        type = text.removeBefore("L", true).removeAfter("\n").trimIndent()
        superClass = text.removeBefore("\n.super ").removeAfter("\n").trimIndent()
        Regex(
            "^\\.field ((?:\\w+ )+)(\\w+):(\\S+)((?:.(?!^\\.(?!end)))*)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(text).forEach {
            fields.add(
                SmaliField(
                    modifiers = it.groupValues[1].trim().split(" "),
                    name = it.groupValues[2],
                    type = it.groupValues[3],
                    body = it.groupValues[4]
                )
            )
            text = text.replace(it.groupValues[0], "")
        }
        Regex(
            "\\.method ((?:\\w+ )*)([\\w$-]+)\\((\\S*)\\)(\\S+)(.*?^\\.end method)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(text).forEach {
            methods.add(
                SmaliMethod(
                    modifiers = it.groupValues[1].trim().split(" ").toMutableList(),
                    name = it.groupValues[2],
                    args = it.groupValues[3],
                    returnType = it.groupValues[4],
                    body = it.groupValues[5]
                )
            )
            text = text.replace(it.groupValues[0], "")
        }
        otherLines = text
    }
}

class SmaliField(
    val modifiers: List<String>,
    val name: String,
    val type: String,
    val body: String
) {
    fun toCode() = ".field ${modifiers.joinToString { "$it " }}$name:$type$body"
}

data class SmaliMethod(
    var modifiers: MutableList<String>,
    var name: String,
    var args: String,
    val returnType: String,
    var body: String
) {
    val isPrivate get() = modifiers.contains("private")
    val sign get() = ".method ${modifiers.join { "$it " }}$name($args)$returnType"
    fun isEquals(other: SmaliMethod) = this.name == other.name && this.args == other.args && this.returnType == other.returnType
}