package com.gl1ch5.stabcam.stab

import java.util.Locale

/** Gyro log formats. Pure Kotlin so it can be unit-tested. */
object GyroLog {
    /**
     * Gyroflow `.gcsv`: time in ms relative to the first video frame (so it lines up with the clip), rates in rad/s
     * in the Android device frame (x right, y up, z out of the screen). `orientation` maps them to the camera frame:
     * x = +x, y = -y, z = -z.
     */
    fun gcsv(baseTsNs: Long, t: LongArray, w: FloatArray, n: Int, fromNs: Long, toNs: Long, id: String = "stabcam", note: String = ""): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("GYROFLOW IMU LOG\n")
        sb.append("version,1.1\n")
        sb.append("id,").append(id).append('\n')
        sb.append("orientation,Xyz\n")
        sb.append("note,").append(note.replace(',', ';').replace('\n', ' ')).append('\n')
        sb.append("tscale,0.001\n")
        sb.append("gscale,1.0\n")
        sb.append("ascale,1.0\n")
        sb.append("t,gx,gy,gz\n")
        for (i in 0 until n) {
            if (t[i] < fromNs || t[i] > toNs) continue
            sb.append(String.format(Locale.US, "%.3f,%.6f,%.6f,%.6f\n", (t[i] - baseTsNs) / 1e6, w[i * 3], w[i * 3 + 1], w[i * 3 + 2]))
        }
        return sb.toString()
    }

    /** Parses what [gcsv] wrote: returns (times in ns relative to base, rates xyz) or null. */
    fun parseGcsv(text: String): Pair<LongArray, FloatArray>? {
        val ts = ArrayList<Long>(); val ws = ArrayList<Float>()
        var inData = false
        for (line in text.lineSequence()) {
            if (!inData) { if (line.startsWith("t,gx")) inData = true; continue }
            val p = line.split(',')
            if (p.size < 4) continue
            ts += (p[0].toDouble() * 1e6).toLong()
            ws += p[1].toFloat(); ws += p[2].toFloat(); ws += p[3].toFloat()
        }
        if (ts.isEmpty()) return null
        return LongArray(ts.size) { ts[it] } to FloatArray(ws.size) { ws[it] }
    }
}
