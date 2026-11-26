package momoi.plugin.apkmixin.utils

class Smali(src: String) {
    var otherLines: String
    val superClass: String
    val type: String
    val fields = mutableListOf<SmaliField>()
    val methods = mutableListOf<SmaliMethod>()

    fun findMethod(other: SmaliMethod) = methods.find {
        it.name == other.name && it.args == other.args && it.returnType == other.returnType
    }

    fun getMethodCall(method: SmaliMethod) = "$type->${method.name}(${method.args})${method.returnType}"

    fun toText(): String {
        return "$otherLines\n${
            fields.join { field -> "\n${field.toCode()}" }
        }\n${
            methods.join { method -> "\n${method.sign}${method.body}" }
        }"
    }

    init {
        var text = src
        type = CLASS_REGEX.find(text)?.groupValues?.get(1)
            ?: text.removeBefore("L", true).removeAfter("\n").trimIndent()
        superClass = SUPER_REGEX.find(text)?.groupValues?.get(1) ?: ""

        FIELD_REGEX.findAll(text).toList().forEach {
            fields.add(
                SmaliField(
                    modifiers = parseModifiers(it.groupValues[1]),
                    name = it.groupValues[2],
                    type = it.groupValues[3],
                    body = it.groupValues[4]
                )
            )
            text = text.replaceFirst(it.groupValues[0], "")
        }

        METHOD_REGEX.findAll(text).toList().forEach {
            methods.add(
                SmaliMethod(
                    modifiers = parseModifiers(it.groupValues[1]).toMutableList(),
                    name = it.groupValues[2],
                    args = it.groupValues[3],
                    returnType = it.groupValues[4],
                    body = it.groupValues[5]
                )
            )
            text = text.replaceFirst(it.groupValues[0], "")
        }
        otherLines = text
    }

    companion object {
        private val CLASS_REGEX = Regex("^\\.class\\s+.*?\\s+(L[^;\\s]+;)", RegexOption.MULTILINE)
        private val SUPER_REGEX = Regex("^\\.super\\s+(L[^;\\s]+;)", RegexOption.MULTILINE)
        private val FIELD_REGEX = Regex(
            "^\\.field ((?:[\\w-]+ )*)([\\w$-]+):(\\S+)((?:.(?!^\\.(?!end)))*)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        private val METHOD_REGEX = Regex(
            "^\\.method ((?:[\\w-]+ )*)([\\w$<>-]+)\\((\\S*)\\)(\\S+)(.*?^\\.end method)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )

        internal fun parseModifiers(raw: String): List<String> =
            raw.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .distinct()
    }
}

class SmaliField(
    val modifiers: List<String>,
    val name: String,
    val type: String,
    val body: String
) {
    fun toCode(): String {
        val modPrefix = modifiers.filter { it.isNotBlank() }.distinct().join { "$it " }
        return ".field $modPrefix$name:$type$body"
    }
}

data class SmaliMethod(
    var modifiers: MutableList<String>,
    var name: String,
    var args: String,
    val returnType: String,
    var body: String
) {
    val isPrivate get() = modifiers.contains("private")
    val sign: String
        get() {
            val modPrefix = modifiers.filter { it.isNotBlank() }.distinct().join { "$it " }
            return ".method $modPrefix$name($args)$returnType"
        }

    fun isEquals(other: SmaliMethod) =
        this.name == other.name && this.args == other.args && this.returnType == other.returnType
}
