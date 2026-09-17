package momoi.plugin.apkmixin.utils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path

object ZipUtil {
    fun addOrReplaceFilesInZip(zipFile: File, filesToAdd: Map<String, File>) {
        // 创建临时文件
        val tempFile = File.createTempFile("tempZip", ".zip", zipFile.parentFile)
        var originalZip: ZipFile? = null
        
        try {
            ZipOutputStream(FileOutputStream(tempFile).buffered()).use { zos ->
                // 处理原始 ZIP 条目，跳过需替换的文件
                originalZip = ZipFile(zipFile)
                originalZip.use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (!filesToAdd.containsKey(entry.name)) {
                            // 复制条目到临时 ZIP
                            zos.putNextEntry(ZipEntry(entry.name).apply {
                                time = entry.time
                                comment = entry.comment
                            })
                            zip.getInputStream(entry).use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }

                // 添加或替换新文件
                filesToAdd.forEach { (entryName, file) ->
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }

            originalZip?.close()
            originalZip = null

            try {
                Files.deleteIfExists(zipFile.toPath())
                Files.move(
                    tempFile.toPath(),
                    zipFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                Files.copy(
                    tempFile.toPath(),
                    zipFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            originalZip?.close()
        }
    }
}