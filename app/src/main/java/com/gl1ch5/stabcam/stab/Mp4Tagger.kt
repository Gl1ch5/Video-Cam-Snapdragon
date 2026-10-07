package com.gl1ch5.stabcam.stab

import android.system.Os
import android.system.OsConstants
import com.gl1ch5.stabcam.util.Logger
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Appends an iTunes-style `udta/meta/ilst` box (make, model, software, comment, description) to the end of
 * `moov` of a finished MP4. Works only when `moov` is the last top-level box (what MediaMuxer produces), so no
 * chunk offsets move. On anything unexpected it leaves the file untouched.
 */
object Mp4Tagger {
    fun tag(fd: FileDescriptor, items: List<Pair<String, String>>): Boolean = try {
        val size = Os.fstat(fd).st_size
        var pos = 0L
        var moovPos = -1L
        var moovSize = 0L
        var freeSize = 0L // a "free" box right after moov: short clips get moov at the front with reserved space
        val hdr = ByteArray(16)
        while (pos + 8 <= size) {
            Os.pread(fd, hdr, 0, 16.coerceAtMost((size - pos).toInt()), pos)
            var boxSize = ByteBuffer.wrap(hdr, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (boxSize == 1L) boxSize = ByteBuffer.wrap(hdr, 8, 8).long
            if (boxSize == 0L) boxSize = size - pos
            if (boxSize < 8) break
            if (type == "moov") { moovPos = pos; moovSize = boxSize }
            if (type == "free" && moovPos >= 0 && pos == moovPos + moovSize && freeSize == 0L) freeSize = boxSize
            pos += boxSize
        }
        val udtaLen = udta(items).size.toLong()
        if (moovPos >= 0 && moovPos + moovSize != size && moovSize < 0xFFFFFFFFL - 65536 &&
            (freeSize == udtaLen || freeSize >= udtaLen + 8)) {
            // moov at the front: grow it into the reserved free space; nothing after it moves, so no offsets change
            val udta = udta(items)
            val at = moovPos + moovSize
            val rest = freeSize - udtaLen
            if (rest >= 8) {
                val fh = ByteBuffer.allocate(8).putInt(rest.toInt()).put("free".toByteArray(Charsets.ISO_8859_1)).array()
                Os.pwrite(fd, fh, 0, 8, at + udtaLen)
            }
            Os.pwrite(fd, udta, 0, udta.size, at)
            Os.pwrite(fd, ByteBuffer.allocate(4).putInt((moovSize + udtaLen).toInt()).array(), 0, 4, moovPos)
            Logger.i("Tag", "Метаданные записаны в резерв moov (${items.size} полей)")
            true
        } else if (moovPos < 0 || moovPos + moovSize != size || moovSize >= 0xFFFFFFFFL - 65536) {
            Logger.w("Tag", "moov не в конце файла, метаданные пропущены")
            false
        } else {
            val udta = udta(items)
            Os.pwrite(fd, udta, 0, udta.size, size) // append first: a failure here leaves a valid file
            val newSize = ByteBuffer.allocate(4).putInt((moovSize + udta.size).toInt()).array()
            Os.pwrite(fd, newSize, 0, 4, moovPos)
            Logger.i("Tag", "Метаданные записаны (${items.size} полей)")
            true
        }
    } catch (e: Exception) {
        Logger.w("Tag", "Метаданные не записаны", e)
        false
    }

    private fun box(type: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(8 + payload.size).array())
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray) = box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun item(key: String, value: String): ByteArray {
        val v = value.toByteArray(Charsets.UTF_8)
        val data = box("data", ByteBuffer.allocate(8 + v.size).putInt(1).putInt(0).put(v).array())
        val k = if (key.length == 3) byteArrayOf(0xA9.toByte()) + key.toByteArray(Charsets.ISO_8859_1) else key.toByteArray(Charsets.ISO_8859_1)
        return box(k, data)
    }

    private fun udta(items: List<Pair<String, String>>): ByteArray {
        val ilst = box("ilst", items.fold(ByteArray(0)) { a, (k, v) -> a + item(k, v) })
        val hdlr = box("hdlr", ByteArray(4) + "mdir".toByteArray() + "appl".toByteArray() + ByteArray(9))
        val meta = box("meta", ByteArray(4) + hdlr + ilst) // full box: version/flags
        return box("udta", meta)
    }

    @Suppress("unused") private val keep = OsConstants.O_RDWR
}
