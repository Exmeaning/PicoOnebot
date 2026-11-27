package momoi.plugin.apkmixin.utils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipUtil {
    fun addOrReplaceFilesInZip(
        zipFile: File,
        filesToAdd: Map<String, File>,
        removePredicate: (String) -> Boolean = { false }
    ) {
        val tempFile = File.createTempFile("tempZip", ".zip", zipFile.parentFile)
        val originalStoredEntries = mutableSetOf<String>()

        try {
            ZipOutputStream(FileOutputStream(tempFile).buffered()).use { zos ->
                // 处理原始 ZIP 条目，跳过需替换/剔除的文件，并保留 STORED 条目的无压缩方式、对齐 extra 与校验信息
                ZipFile(zipFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (filesToAdd.containsKey(entry.name)) {
                            if (entry.method == ZipEntry.STORED) {
                                originalStoredEntries.add(entry.name)
                            }
                        } else if (removePredicate(entry.name)) {
                            // 剔除已失效的旧条目（例如多 DEX 数量缩减后的冗余 classes(M+1..K).dex）
                        } else if (entry.method == ZipEntry.STORED) {
                            val preservedExtra = entry.extra?.let(::stripExtendedTimestampExtra)
                            if (entry.size >= 0 && entry.crc >= 0) {
                                zos.putNextEntry(ZipEntry(entry.name).apply {
                                    method = ZipEntry.STORED
                                    size = entry.size
                                    compressedSize = entry.size
                                    crc = entry.crc
                                    if (preservedExtra != null && preservedExtra.isNotEmpty()) {
                                        extra = preservedExtra
                                    }
                                    time = 0L
                                    comment = entry.comment
                                })
                                zip.getInputStream(entry).use { input ->
                                    input.copyTo(zos)
                                }
                                zos.closeEntry()
                            } else {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                val crc32 = CRC32().apply { update(bytes) }
                                zos.putNextEntry(ZipEntry(entry.name).apply {
                                    method = ZipEntry.STORED
                                    size = bytes.size.toLong()
                                    compressedSize = bytes.size.toLong()
                                    crc = crc32.value
                                    if (preservedExtra != null && preservedExtra.isNotEmpty()) {
                                        extra = preservedExtra
                                    }
                                    time = 0L
                                    comment = entry.comment
                                })
                                zos.write(bytes)
                                zos.closeEntry()
                            }
                        } else {
                            zos.putNextEntry(ZipEntry(entry.name).apply {
                                method = ZipEntry.DEFLATED
                                time = 0L
                                comment = entry.comment
                            })
                            zip.getInputStream(entry).use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }

                // 按确定性顺序添加或替换新文件，固定时间戳以支持可重现构建
                filesToAdd.entries.sortedBy { it.key }.forEach { (entryName, file) ->
                    if (entryName in originalStoredEntries) {
                        val crcValue = computeCrc32(file)
                        val fileSize = file.length()
                        zos.putNextEntry(ZipEntry(entryName).apply {
                            method = ZipEntry.STORED
                            size = fileSize
                            compressedSize = fileSize
                            crc = crcValue
                            time = 0L
                        })
                    } else {
                        zos.putNextEntry(ZipEntry(entryName).apply {
                            method = ZipEntry.DEFLATED
                            time = 0L
                        })
                    }
                    FileInputStream(file).use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    zipFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    zipFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun stripExtendedTimestampExtra(raw: ByteArray): ByteArray {
        val out = ArrayList<Byte>(raw.size)
        var offset = 0
        while (offset + 4 <= raw.size) {
            val headerId = (raw[offset].toInt() and 0xFF) or ((raw[offset + 1].toInt() and 0xFF) shl 8)
            val dataLen = (raw[offset + 2].toInt() and 0xFF) or ((raw[offset + 3].toInt() and 0xFF) shl 8)
            val next = offset + 4 + dataLen
            if (next > raw.size) {
                // Non-standard trailing alignment bytes (e.g. raw zero padding): preserve verbatim
                for (i in offset until raw.size) out.add(raw[i])
                return out.toByteArray()
            }
            if (headerId != 0x5455 && headerId != 0x5855) {
                for (i in offset until next) out.add(raw[i])
            }
            offset = next
        }
        for (i in offset until raw.size) out.add(raw[i])
        return out.toByteArray()
    }

    private fun computeCrc32(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(8192)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
