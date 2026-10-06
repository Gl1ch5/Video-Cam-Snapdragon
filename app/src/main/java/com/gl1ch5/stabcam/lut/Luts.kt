package com.gl1ch5.stabcam.lut

import android.content.Context
import com.gl1ch5.stabcam.util.Logger
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 3D colour LUT, RGB8, red fastest (same order as .cube and as GL texture upload). */
class Lut(val id: String, val name: String, val size: Int, val rgb: ByteArray) {
    /** Nearest-neighbour lookup, used for the swatches in the picker. */
    fun apply(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun ix(v: Float) = (v.coerceIn(0f, 1f) * (size - 1) + 0.5f).toInt()
        val i = ((ix(b) * size + ix(g)) * size + ix(r)) * 3
        return Triple((rgb[i].toInt() and 255) / 255f, (rgb[i + 1].toInt() and 255) / 255f, (rgb[i + 2].toInt() and 255) / 255f)
    }
}

object Luts {
    class Entry(val id: String, val name: String, val subtitle: String)

    /** Looks generated in code (no assets to ship): "Apple/Samsung-style" grades and classics. */
    val builtin = listOf(
        Entry("rich", "Rich Contrast", "в стиле iPhone"),
        Entry("vivid", "Vivid", "в стиле Samsung"),
        Entry("warm", "Тёплый", "золотой час"),
        Entry("cool", "Холодный", "голубые тени"),
        Entry("cinema", "Кино", "teal & orange"),
        Entry("film", "Плёнка", "мягкие чёрные"),
        Entry("soft", "Мягкий", "пастель"),
        Entry("bw", "Ч/Б", "контрастный"),
    )

    private const val N = 33

    fun userDir(ctx: Context) = File(ctx.filesDir, "luts").apply { mkdirs() }

    fun userEntries(ctx: Context): List<Entry> =
        (userDir(ctx).listFiles { f -> f.name.endsWith(".cube", true) } ?: emptyArray()).sortedBy { it.name }
            .map { Entry("file:" + it.name, it.nameWithoutExtension, "мой .cube") }

    /** Resolves "rich", "file:name.cube" or "" (none). */
    fun resolve(ctx: Context, id: String): Lut? = when {
        id.isEmpty() || id == "off" -> null
        id.startsWith("mod:") -> runCatching { com.gl1ch5.stabcam.module.ModuleManager(ctx).lut(id) }.getOrNull()
        id.startsWith("file:") -> runCatching {
            parseCube(id, File(userDir(ctx), id.removePrefix("file:")).let { it.nameWithoutExtension }, File(userDir(ctx), id.removePrefix("file:")).readText())
        }.onFailure { Logger.e("LUT", "Не удалось прочитать $id", it) }.getOrNull()
        else -> generate(id)
    }

    /** Copies a picked .cube into the app and returns its id ("file:name.cube"), or null if it is not a usable 3D LUT. Blocking. */
    fun importCube(ctx: Context, uri: android.net.Uri): String? {
        val name = (ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "lut.cube").let { if (it.endsWith(".cube", true)) it else "$it.cube" }
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        val lut = parseCube("file:$name", name.removeSuffix(".cube"), text) ?: return null
        File(userDir(ctx), name).writeText(text)
        Logger.i("LUT", "Импортирован $name (${lut.size}³)")
        return "file:$name"
    }

    fun generate(id: String): Lut? {
        val f = look(id) ?: return null
        val data = ByteArray(N * N * N * 3)
        var i = 0
        for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
            val o = f(r / (N - 1f), g / (N - 1f), b / (N - 1f))
            data[i++] = to8(o[0]); data[i++] = to8(o[1]); data[i++] = to8(o[2])
        }
        return Lut(id, builtin.firstOrNull { it.id == id }?.name ?: id, N, data)
    }

    fun parseCube(id: String, name: String, text: String): Lut? {
        var size = 0
        val vals = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE") || line.startsWith("DOMAIN")) continue
            if (line.startsWith("LUT_3D_SIZE")) { size = line.split(Regex("\\s+"))[1].toInt(); continue }
            if (line.startsWith("LUT_1D_SIZE")) return null // 1D LUTs are not supported
            val p = line.split(Regex("\\s+"))
            if (p.size >= 3) p.take(3).mapNotNullTo(vals) { it.toFloatOrNull() }
        }
        if (size < 2 || vals.size < size * size * size * 3) return null
        val data = ByteArray(size * size * size * 3) { to8(vals[it]) }
        return Lut(id, name, size, data)
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    // --- colour maths -------------------------------------------------------------------------------------------

    private fun lum(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun curve(x: Float, k: Float) = x + k * (smooth(x) - x)
    private fun sat(c: FloatArray, s: Float): FloatArray {
        val l = lum(c[0], c[1], c[2])
        return floatArrayOf(l + (c[0] - l) * s, l + (c[1] - l) * s, l + (c[2] - l) * s)
    }
    private fun cv(c: FloatArray, k: Float) = floatArrayOf(curve(c[0], k), curve(c[1], k), curve(c[2], k))

    private fun look(id: String): ((Float, Float, Float) -> FloatArray)? = when (id) {
        "rich" -> { r, g, b -> sat(cv(floatArrayOf(r, g, b), 0.55f), 1.12f) }
        "vivid" -> { r, g, b -> sat(cv(floatArrayOf(r, g, b), 0.3f), 1.38f) }
        "warm" -> { r, g, b -> cv(floatArrayOf(r * 1.07f, g * 1.01f, b * 0.9f), 0.2f) }
        "cool" -> { r, g, b -> cv(floatArrayOf(r * 0.93f, g * 0.99f, b * 1.08f), 0.2f) }
        "cinema" -> { r, g, b ->
            val l = lum(r, g, b)
            val sh = (1 - l) * (1 - l)
            val hi = l * l
            val c = floatArrayOf(r - 0.03f * sh + 0.05f * hi, g + 0.012f * sh + 0.015f * hi, b + 0.045f * sh - 0.04f * hi)
            sat(cv(c, 0.4f), 0.9f)
        }
        "film" -> { r, g, b ->
            val c = floatArrayOf(r * 1.03f, g, b * 0.95f)
            sat(cv(floatArrayOf(0.04f + 0.92f * c[0], 0.04f + 0.92f * c[1], 0.04f + 0.92f * c[2]), 0.25f), 0.92f)
        }
        "soft" -> { r, g, b ->
            val c = floatArrayOf(0.5f + (r - 0.5f) * 0.88f + 0.03f, 0.5f + (g - 0.5f) * 0.88f + 0.03f, 0.5f + (b - 0.5f) * 0.88f + 0.03f)
            sat(c, 0.85f)
        }
        "bw" -> { r, g, b -> val l = curve(lum(r, g, b), 0.4f); floatArrayOf(l, l, l) }
        else -> null
    }

    @Suppress("unused") private fun clamp01(v: Float) = max(0f, min(1f, v))
}
