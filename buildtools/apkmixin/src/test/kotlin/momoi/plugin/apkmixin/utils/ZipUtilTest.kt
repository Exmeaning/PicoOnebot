package momoi.plugin.apkmixin.utils

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZipUtilTest {

    private fun crc32Of(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun createFixtureZip(target: File) {
        val arscBytes = "ANDROID-RESOURCES-STORED-PAYLOAD".toByteArray()
        val soBytes = ByteArray(1024) { (it * 31 and 0xFF).toByte() }
        val dexBytes = "original-classes-dex-content".repeat(20).toByteArray()

        ZipOutputStream(FileOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED
                size = arscBytes.size.toLong()
                compressedSize = arscBytes.size.toLong()
                crc = crc32Of(arscBytes)
                time = 1_700_000_000_000L
                comment = "stored-comment"
            })
            zos.write(arscBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("lib/armeabi-v7a/libdemo.so").apply {
                method = ZipEntry.STORED
                size = soBytes.size.toLong()
                compressedSize = soBytes.size.toLong()
                crc = crc32Of(soBytes)
                time = 1_700_000_000_000L
            })
            zos.write(soBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("classes.dex").apply {
                method = ZipEntry.DEFLATED
                time = 1_700_000_000_000L
            })
            zos.write(dexBytes)
            zos.closeEntry()
        }
    }

    @Test
    fun preservesStoredMethodSizeCrcAndSetsDeterministicTimestamp() {
        val dir = Files.createTempDirectory("ziputil-stored-test").toFile()
        try {
            val zipA = File(dir, "appA.apk")
            val zipB = File(dir, "appB.apk")
            createFixtureZip(zipA)
            createFixtureZip(zipB)

            val replacedArsc = File(dir, "new-resources.arsc").apply {
                writeBytes("UPDATED-STORED-RESOURCES-BINARY".toByteArray())
            }
            val replacedDex = File(dir, "classes.dex").apply {
                writeBytes("new-classes-dex".repeat(30).toByteArray())
            }
            val addedWebui = File(dir, "webui.html").apply {
                writeBytes("<!doctype html><title>Pico</title>".toByteArray())
            }

            // Pass keys in different insertion orders to verify deterministic output
            ZipUtil.addOrReplaceFilesInZip(
                zipA,
                linkedMapOf(
                    "assets/pico/webui.html" to addedWebui,
                    "resources.arsc" to replacedArsc,
                    "classes.dex" to replacedDex
                )
            )
            ZipUtil.addOrReplaceFilesInZip(
                zipB,
                linkedMapOf(
                    "classes.dex" to replacedDex,
                    "resources.arsc" to replacedArsc,
                    "assets/pico/webui.html" to addedWebui
                )
            )

            // Byte-for-byte deterministic output regardless of map insertion order
            assertContentEquals(zipA.readBytes(), zipB.readBytes())

            // No leftover tempZip files after atomic replace
            val leftovers = dir.listFiles { _, name -> name.startsWith("tempZip") }.orEmpty()
            assertTrue(leftovers.isEmpty(), "Expected no tempZip leftovers, found ${leftovers.map { it.name }}")

            ZipFile(zipA).use { zip ->
                // Untouched STORED entry keeps method/size/crc and zeroed time
                val soEntry = assertNotNull(zip.getEntry("lib/armeabi-v7a/libdemo.so"))
                assertEquals(ZipEntry.STORED, soEntry.method)
                val soData = zip.getInputStream(soEntry).use { it.readBytes() }
                assertEquals(soData.size.toLong(), soEntry.size)
                assertEquals(soData.size.toLong(), soEntry.compressedSize)
                assertEquals(crc32Of(soData), soEntry.crc)
                assertEquals(0L, soEntry.time)

                // Replaced STORED entry stays STORED with recomputed size/crc and zeroed time
                val arscEntry = assertNotNull(zip.getEntry("resources.arsc"))
                assertEquals(ZipEntry.STORED, arscEntry.method)
                val arscData = zip.getInputStream(arscEntry).use { it.readBytes() }
                assertContentEquals(replacedArsc.readBytes(), arscData)
                assertEquals(arscData.size.toLong(), arscEntry.size)
                assertEquals(arscData.size.toLong(), arscEntry.compressedSize)
                assertEquals(crc32Of(arscData), arscEntry.crc)
                assertEquals(0L, arscEntry.time)

                // Replaced DEFLATED and newly added entries are DEFLATED with zeroed time
                val dexEntry = assertNotNull(zip.getEntry("classes.dex"))
                assertEquals(ZipEntry.DEFLATED, dexEntry.method)
                assertContentEquals(replacedDex.readBytes(), zip.getInputStream(dexEntry).use { it.readBytes() })
                assertEquals(0L, dexEntry.time)

                val webuiEntry = assertNotNull(zip.getEntry("assets/pico/webui.html"))
                assertEquals(ZipEntry.DEFLATED, webuiEntry.method)
                assertContentEquals(addedWebui.readBytes(), zip.getInputStream(webuiEntry).use { it.readBytes() })
                assertEquals(0L, webuiEntry.time)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedReplaceLeavesOriginalZipIntactAndCleansTempFile() {
        val dir = Files.createTempDirectory("ziputil-atomic-test").toFile()
        try {
            val targetZip = File(dir, "target.apk")
            createFixtureZip(targetZip)
            val originalBytes = targetZip.readBytes()

            val missingFile = File(dir, "does-not-exist.dex")
            assertFailsWith<FileNotFoundException> {
                ZipUtil.addOrReplaceFilesInZip(targetZip, mapOf("classes.dex" to missingFile))
            }

            // Target archive must remain byte-for-byte identical and readable
            assertContentEquals(originalBytes, targetZip.readBytes())
            ZipFile(targetZip).use { zip ->
                assertNotNull(zip.getEntry("resources.arsc"))
                assertNotNull(zip.getEntry("classes.dex"))
            }

            // Temporary file must have been cleaned up on failure
            val leftovers = dir.listFiles { _, name -> name.startsWith("tempZip") }.orEmpty()
            assertTrue(leftovers.isEmpty(), "Expected tempZip cleanup on failure, found ${leftovers.map { it.name }}")
        } finally {
            dir.deleteRecursively()
        }
    }
}
