package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.config.ConfigRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMergeTest {

    @Test
    fun deepMergeOverridesLeavesAndKeepsSiblings() {
        val base = JSONObject("""{"video":{"codec":"hevc","bitrateMbps":{"4K60":120,"4K30":80}},"camera":{"ois":true}}""")
        val over = JSONObject("""{"video":{"bitrateMbps":{"4K60":150}},"camera":{"ois":false}}""")
        ConfigRepository.deepMerge(base, over)
        assertEquals("hevc", base.getJSONObject("video").getString("codec"))
        assertEquals(150, base.getJSONObject("video").getJSONObject("bitrateMbps").getInt("4K60"))
        assertEquals(80, base.getJSONObject("video").getJSONObject("bitrateMbps").getInt("4K30"))
        assertFalse(base.getJSONObject("camera").getBoolean("ois"))
    }

    @Test
    fun presetMatching() {
        val props = mapOf("manufacturer" to "OnePlus", "socManufacturer" to "QTI", "model" to "CPH0000")
        assertTrue(ConfigRepository.matches(JSONObject("""{"manufacturer":["OnePlus"]}"""), props))
        assertTrue(ConfigRepository.matches(JSONObject("""{"manufacturer":"oneplus","socManufacturer":["QTI","Qualcomm"]}"""), props))
        assertFalse(ConfigRepository.matches(JSONObject("""{"manufacturer":["samsung"]}"""), props))
        assertFalse(ConfigRepository.matches(JSONObject("{}"), props))
    }
}
