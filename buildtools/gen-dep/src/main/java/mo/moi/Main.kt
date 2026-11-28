package mo.moi

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun main() {
    val inputJar = File("./buildtools/gen-dep/raw.jar")
    val outputJar = File("./app/libs/source.jar")
    processJar(inputJar, outputJar)
}

val excluded = arrayOf("kotlin", "androidx")

fun processJar(inputJar: File, outputJar: File) {
    require(inputJar.isFile) { "Input JAR does not exist: ${inputJar.absolutePath}" }
    val parentDir = outputJar.absoluteFile.parentFile
        ?: error("Output JAR has no parent directory: ${outputJar.path}")
    parentDir.mkdirs()

    val tempPath = Files.createTempFile(parentDir.toPath(), "${outputJar.name}.", ".tmp")
    val renameCounter = AtomicInteger(0)
    try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempPath.toFile()))).use { zos ->
            ZipInputStream(BufferedInputStream(FileInputStream(inputJar))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    // kotlin/ 与 androidx/ 一律**不进** source.jar:编译时用官方 artifact。
                    // 宿主 APK 里的 kotlin stdlib 是裁剪过的,若混进 classpath,kotlinc 内联
                    // `synchronized {}` 之类 stdlib inline 函数时会取到它的字节码而崩掉
                    // ("Couldn't inline method call ... MONITOREXIT")。
                    if (!entry.isDirectory && !excluded.any { entry.name.startsWith("$it/") }) {
                        val entryName = entry.name
                        val bytes = zis.readBytes()
                        val modifiedBytes =
                            if (entryName.endsWith(".class")) modifyClass(bytes, renameCounter) else bytes
                        val outEntry = ZipEntry(entryName).apply {
                            time = 0L
                        }
                        zos.putNextEntry(outEntry)
                        zos.write(modifiedBytes)
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        try {
            Files.move(
                tempPath,
                outputJar.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempPath,
                outputJar.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    } catch (t: Throwable) {
        runCatching { Files.deleteIfExists(tempPath) }
        throw t
    }
}

fun modifyClass(classData: ByteArray, renameCounter: AtomicInteger = AtomicInteger(0)): ByteArray {
    val reader = ClassReader(classData)
    val writer = ClassWriter(reader, 0)
    val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visit(
            version: Int,
            access: Int,
            name: String?,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            val newAccess = (access and Opcodes.ACC_FINAL.inv()) or Opcodes.ACC_PUBLIC
            super.visit(version, newAccess, name, signature, superName, interfaces)
        }

        override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) {
            var newInnerName = innerName
            var newOuterName = outerName
            if (innerName == null && outerName == null && name != null) {
                newInnerName = name.substringAfterLast("/")
                newOuterName = name.substringBeforeLast("/") + "/" + newInnerName.substringBefore("$")
            }
            super.visitInnerClass(
                name,
                newOuterName,
                newInnerName,
                access and Opcodes.ACC_PRIVATE.inv()
                    and Opcodes.ACC_FINAL.inv()
                    or Opcodes.ACC_PUBLIC
            )
        }

        val renameMethods = listOf(
            "K(Lcom/tencent/mvi/base/mvi/MviUIState;)V",
            "e(Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
            "f(Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;Landroid/view/View\$OnClickListener;)V",
            "setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V"
        )

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            var newName = name
            if (renameMethods.contains("$name$descriptor")) {
                newName = "Rename${renameCounter.incrementAndGet()}"
            }
            var newAccess = access and Opcodes.ACC_FINAL.inv()
            if (name == "<init>" && (access and Opcodes.ACC_PRIVATE) != 0) {
                newAccess = newAccess or Opcodes.ACC_PUBLIC
                newAccess = newAccess and Opcodes.ACC_PRIVATE.inv()
            }
            return super.visitMethod(newAccess, newName, descriptor, signature, exceptions)
        }

        override fun visitField(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            value: Any?
        ): FieldVisitor {
            val newAccess = access and Opcodes.ACC_FINAL.inv()
            return super.visitField(newAccess, name, descriptor, signature, value)
        }

        val removed = arrayOf("Lkotlin/Metadata;", "Landroidx/annotation/RestrictTo;")
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
            return if (removed.contains(descriptor)) null
            else super.visitAnnotation(descriptor, visible)
        }
    }
    reader.accept(visitor, 0)
    return writer.toByteArray()
}
