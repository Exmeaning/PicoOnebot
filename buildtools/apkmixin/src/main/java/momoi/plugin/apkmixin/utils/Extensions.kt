package momoi.plugin.apkmixin.utils

import lanchon.multidexlib2.DexFileNamer
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile
import kotlin.io.path.copyTo
import kotlin.io.path.pathString

/* Logging */

private val logger: Logger = Logging.getLogger("ApkMixin")

fun lifecycle(msg: String) = logger.lifecycle("[ApkMixin/L] $msg")
fun info(msg: String) = logger.info("[ApkMixin/I] $msg")

/* Files */

fun File.child(name: String) = File(this, name)

fun mergeDirectories(source: Path, target: Path) {
    require(Files.isDirectory(source)) { "Source must be a directory" }
    require(Files.isDirectory(target)) { "Target must be a directory" }
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // 在目标路径创建对应的目录
            val relativePath = source.relativize(dir)
            val targetDir = target.resolve(relativePath)
            Files.createDirectories(targetDir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            // 复制文件并覆盖已存在的文件
            val relativePath = source.relativize(file)
            val targetFile = target.resolve(relativePath)
            file.copyTo(targetFile, overwrite = true)
            lifecycle("recovery file: ${targetFile.pathString}")
            return FileVisitResult.CONTINUE
        }
    })
}

fun ZipFile.getDexCount(namer: DexFileNamer): Int {
    return generateSequence(0) { it + 1 }
        .takeWhile { getEntry(namer.getName(it)) != null }
        .count()
}

fun File.ensureDirExists() = apply {
    parentFile.mkdirs()
}

/* String */

fun String.removeBefore(s: String, keep: Boolean = false) =
    "${if (keep) s else ""}${split(s, limit = 2)[1]}"
fun String.removeAfter(s: String) = split(s, limit = 2)[0]
fun <E> List<E>.join(block: (E)->CharSequence) = joinToString("", transform = block)