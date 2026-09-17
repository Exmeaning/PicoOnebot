package pico.onebot.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.tencent.qqnt.kernel.nativeinterface.FileElement
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.kernel.nativeinterface.PttElement
import com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo
import com.tencent.qqnt.kernel.nativeinterface.VideoElement
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.File
import java.io.FileOutputStream

/**
 * 本地文件 → 可发送的 `MsgElement`。
 *
 * **不需要预先上传。** 内核的发送流程是:调用方把文件拷进
 * `getRichMediaFilePathForMobileQQSend` 指定的缓存路径,填好 md5/尺寸等元信息,
 * `sendMsg` 时内核自己完成上传。这套参数不是猜的 —— 是从宿主自己的发图/发视频代码
 * (`com.tencent.watch.aio_impl.ext.MsgUtil`、`AIOVideoSendUtility`)里反出来的:
 *
 *   `RichMediaFilePathInfo(elementType, subType, md5, fileName, downloadType, thumbSize, ctx, uuid, needCreate)`
 *   其中 **downloadType 1 = 原件,2 = 缩略图**(图片缩略图 thumbSize 取 720)。
 *
 * 这里一律用无参构造 + 逐字段赋值,不走那个 9 参构造器 —— 字段名是稳定的,
 * 参数顺序不是,换个 QQ 版本就可能静默错位。
 */
object MediaBuilder {

    const val E_PIC = 2
    const val E_FILE = 3
    const val E_PTT = 4
    const val E_VIDEO = 5

    private const val DL_ORIGIN = 1
    private const val DL_THUMB = 2
    private const val PIC_THUMB_SIZE = 720

    // PttElement.formatType,取自内核枚举 PttFormatType
    private const val FMT_AMR = 0
    private const val FMT_SILK = 1
    private const val FMT_MP3 = 2
    private const val FMT_AAC = 3

    class BuildError(msg: String) : RuntimeException(msg)

    // ---------------- 图片 ----------------

    fun image(src: File, subType: Int = 0, summary: String = ""): MsgElement {
        val md5 = MediaFiles.md5File(src)
        val name = kernelName(src, md5)
        val path = kernelPath(E_PIC, subType, md5, name, DL_ORIGIN, 0)
            ?: throw BuildError("内核没有返回图片缓存路径")
        if (!MediaFiles.copyTo(src, path)) throw BuildError("拷贝到内核缓存失败: " + path)
        // 宿主自己也会把原图同时写进缩略图槽位,照做 —— 少了它某些场景不出预览图
        kernelPath(E_PIC, subType, md5, name, DL_THUMB, PIC_THUMB_SIZE)?.let {
            MediaFiles.copyTo(src, it)
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)

        val pic = PicElement()
        pic.sourcePath = path
        pic.picSubType = subType
        pic.fileName = name
        pic.fileSize = src.length()
        pic.picWidth = if (opts.outWidth > 0) opts.outWidth else 1
        pic.picHeight = if (opts.outHeight > 0) opts.outHeight else 1
        pic.original = true
        pic.md5HexStr = md5
        pic.picType = picType(name)
        if (summary.isNotEmpty()) pic.summary = summary

        val e = MsgElement()
        e.elementType = E_PIC
        e.picElement = pic
        PicoLog.i("build image " + name + " " + pic.picWidth + "x" + pic.picHeight + " " + pic.fileSize + "B")
        return e
    }

    /** 取自宿主的 picTypeMap;认不出来按 jpg 走(内核会自己再嗅一次)。 */
    private fun picType(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> 1001
        "webp" -> 1002
        "bmp" -> 1005
        "gif" -> 2000
        "apng" -> 2001
        else -> 1000
    }

    // ---------------- 语音 ----------------

    fun record(src: File, declaredDuration: Int = 0): MsgElement {
        val ext = MediaFiles.sniffExt(MediaFiles.head(src))
            .ifEmpty { "." + src.name.substringAfterLast('.', "") }
        val format = when (ext) {
            ".silk" -> FMT_SILK
            ".amr" -> FMT_AMR
            ".mp3" -> FMT_MP3
            ".m4a", ".aac" -> FMT_AAC
            else -> throw BuildError(
                "语音格式不支持: " + ext + "(内核只认 silk/amr/mp3/aac,wav/ogg 需要先转码)"
            )
        }
        val md5 = MediaFiles.md5File(src)
        val name = kernelName(src, md5)
        val path = kernelPath(E_PTT, 0, md5, name, DL_ORIGIN, 0)
            ?: throw BuildError("内核没有返回语音缓存路径")
        if (!MediaFiles.copyTo(src, path)) throw BuildError("拷贝到内核缓存失败: " + path)

        val duration = when {
            declaredDuration > 0 -> declaredDuration
            format == FMT_SILK -> silkDuration(src)
            else -> mediaDurationSec(src)
        }.coerceAtLeast(1)

        val ptt = PttElement()
        ptt.filePath = path
        ptt.fileName = name
        ptt.md5HexStr = md5
        ptt.fileSize = src.length()
        ptt.duration = duration
        ptt.formatType = format
        ptt.voiceType = 1
        ptt.voiceChangeType = 0
        ptt.canConvert2Text = true
        ptt.autoConvertText = 0
        // 波形图:给一串占位值,空 list 在部分客户端上会画不出条
        ptt.waveAmplitudes = ArrayList<Byte>().apply { repeat(20) { add(10) } }

        val e = MsgElement()
        e.elementType = E_PTT
        e.pttElement = ptt
        PicoLog.i("build record " + name + " fmt=" + format + " dur=" + duration + "s")
        return e
    }

    /**
     * SILK 没有头部时长字段,只能按帧算:QQ 用 24kHz、20ms 一帧。
     * 算不准也没关系 —— 它只影响气泡上显示的秒数,不影响能不能播。
     */
    private fun silkDuration(f: File): Int {
        val bytes = f.length()
        if (bytes <= 0) return 1
        return ((bytes * 8) / 25000).toInt().coerceAtLeast(1)
    }

    private fun mediaDurationSec(f: File): Int = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ((ms + 999) / 1000).toInt()
        } finally {
            mmr.release()
        }
    } catch (t: Throwable) {
        0
    }

    // ---------------- 视频 ----------------

    fun video(src: File, thumbSrc: File? = null): MsgElement {
        val md5 = MediaFiles.md5File(src)
        val name = kernelName(src, md5)
        val path = kernelPath(E_VIDEO, 0, md5, name, DL_ORIGIN, 0)
            ?: throw BuildError("内核没有返回视频缓存路径")
        if (!MediaFiles.copyTo(src, path)) throw BuildError("拷贝到内核缓存失败: " + path)

        val video = VideoElement()
        video.filePath = path
        video.fileName = name
        video.videoMd5 = md5
        video.fileSize = src.length()
        video.fileTime = mediaDurationSec(src)
        video.fileFormat = 2
        video.busiType = 0
        video.subBusiType = 0

        // 没有封面的视频在 QQ 里只是一块黑底,所以自己抽一帧
        val thumb = thumbSrc ?: extractThumb(src)
        if (thumb != null && thumb.isFile) {
            val tMd5 = MediaFiles.md5File(thumb)
            val tName = kernelName(thumb, tMd5)
            val tPath = kernelPath(E_VIDEO, 0, tMd5, tName, DL_THUMB, 0)
            if (tPath != null && MediaFiles.copyTo(thumb, tPath)) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tPath, opts)
                video.thumbPath = HashMap<Int, String>().apply { put(0, tPath) }
                video.thumbMd5 = tMd5
                video.thumbSize = thumb.length().toInt()
                video.thumbWidth = if (opts.outWidth > 0) opts.outWidth else 1
                video.thumbHeight = if (opts.outHeight > 0) opts.outHeight else 1
            }
        }

        val e = MsgElement()
        e.elementType = E_VIDEO
        e.videoElement = video
        PicoLog.i("build video " + name + " " + video.fileTime + "s " + video.fileSize + "B")
        return e
    }

    private fun extractThumb(src: File): File? = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(src.absolutePath)
            val bmp: Bitmap? = mmr.getFrameAtTime(0)
            if (bmp == null) null else {
                val out = File(MediaFiles.outboxDir, "thumb-" + src.name + ".jpg")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                bmp.recycle()
                out
            }
        } finally {
            mmr.release()
        }
    } catch (t: Throwable) {
        PicoLog.w("extract video thumb failed", t)
        null
    }

    // ---------------- 文件 ----------------

    fun file(src: File, displayName: String = ""): MsgElement {
        val md5 = MediaFiles.md5File(src)
        val name = displayName.ifEmpty { src.name }
        val path = kernelPath(E_FILE, 0, md5, name, DL_ORIGIN, 0)
        if (path != null) MediaFiles.copyTo(src, path)

        val fe = FileElement()
        fe.fileName = name
        fe.filePath = path ?: src.absolutePath
        fe.fileMd5 = md5
        fe.fileSize = src.length()

        val e = MsgElement()
        e.elementType = E_FILE
        e.fileElement = fe
        PicoLog.i("build file " + name + " " + fe.fileSize + "B")
        return e
    }

    // ---------------- 公共 ----------------

    private fun kernelName(src: File, md5: String): String {
        val ext = src.name.substringAfterLast('.', "")
            .ifEmpty { MediaFiles.sniffExt(MediaFiles.head(src)).removePrefix(".") }
        return if (ext.isEmpty()) md5 else md5 + "." + ext
    }

    private fun kernelPath(
        elementType: Int,
        subType: Int,
        md5: String,
        fileName: String,
        downloadType: Int,
        thumbSize: Int
    ): String? {
        val svc = KernelGate.msgService() ?: return null
        val info = RichMediaFilePathInfo()
        info.elementType = elementType
        info.elementSubType = subType
        info.md5HexStr = md5
        info.fileName = fileName
        info.downloadType = downloadType
        info.thumbSize = thumbSize
        info.importRichMediaContext = null
        info.fileUuid = ""
        info.needCreate = true
        return try {
            svc.getRichMediaFilePathForMobileQQSend(info)?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            PicoLog.w("getRichMediaFilePathForMobileQQSend failed type=" + elementType, t)
            null
        }
    }
}
