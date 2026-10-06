package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.config.AppConfig
import com.gl1ch5.stabcam.config.ConfigRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigLoadTest {
    private fun asset(p: String) = File("src/main/assets/config/$p").readText()

    @Test
    fun defaultAndPresetsLoadIntoAppConfig() {
        val base = JSONObject(asset("default.json"))
        for (f in File("src/main/assets/config/presets").listFiles()!!.filter { it.name.endsWith(".json") }) {
            val merged = JSONObject(base.toString())
            val preset = JSONObject(f.readText())
            ConfigRepository.deepMerge(merged, preset.getJSONObject("config"))
            val c = AppConfig(merged)
            assertTrue(c.stabCrop > 1f)
            assertEquals(3, c.gyroAxes.size)
            c.stabDenoise; c.stabTimeOffsetMs; c.hdr; c.updateRepo; c.stabPreviewRot
            com.gl1ch5.stabcam.config.Quality.entries.forEach { assertTrue(c.bitrateFor(it) > 0) }
        }
    }
}
