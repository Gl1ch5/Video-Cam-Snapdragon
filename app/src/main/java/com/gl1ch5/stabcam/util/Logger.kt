package com.gl1ch5.stabcam.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory ring buffer + rotating file log, mirrored to logcat. Thread-safe. */
object Logger {
    private const val MAX_LINES = 3000
    private const val MAX_FILE = 512 * 1024L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    @Volatile var listener: ((String) -> Unit)? = null

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "stabcam.log")
        if (f.exists() && f.length() > MAX_FILE) f.renameTo(File(ctx.filesDir, "stabcam.old.log"))
        file = f
        i("Log", "=== запуск ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = log('D', tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = log('I', tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = log('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log('E', tag, msg, t)

    private fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        val text = if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg
        when (level) {
            'E' -> Log.e(tag, text, t)
            'W' -> Log.w(tag, text, t)
            'D' -> Log.d(tag, text)
            else -> Log.i(tag, text)
        }
        val line = "${fmt.format(Date())} $level/$tag: $text"
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            runCatching { file?.appendText(line + "\n") }
        }
        listener?.invoke(line)
    }

    @Synchronized fun snapshot(): String = lines.joinToString("\n")

    @Synchronized fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
    }
}
