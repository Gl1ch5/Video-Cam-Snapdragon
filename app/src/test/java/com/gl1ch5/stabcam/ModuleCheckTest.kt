package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.module.ModuleManager
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleCheckTest {
    private fun cube(n: Int) = buildString {
        append("LUT_3D_SIZE $n\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) append("${r / (n - 1f)} ${g / (n - 1f)} ${b / (n - 1f)}\n")
    }

    @Test
    fun acceptsConfigLutAndPreset() {
        val json = org.json.JSONObject()
            .put("module", 1).put("id", "user.test-mod").put("name", "T")
            .put("config", org.json.JSONObject().put("stab", org.json.JSONObject().put("denoise", 0.6)))
            .put("luts", org.json.JSONArray().put(org.json.JSONObject().put("name", "Id").put("cube", cube(3))))
            .put("presets", org.json.JSONArray().put(org.json.JSONObject().put("name", "P").put("config", org.json.JSONObject().put("video", org.json.JSONObject().put("quality", "4K30")))))
        val r = ModuleManager.check(json.toString())
        assertTrue(r.toString(), r is ModuleManager.Result.Ok)
        assertTrue((r as ModuleManager.Result.Ok).summary.size == 3)
    }

    @Test
    fun rejectsForbiddenKeysBadIdAndGarbage() {
        fun mod(cfg: String) = """{"module":1,"id":"user.x1","config":$cfg}"""
        assertTrue(ModuleManager.check(mod("""{"update":{"repo":"evil/repo"}}""")) is ModuleManager.Result.Error)
        assertTrue(ModuleManager.check(mod("""{"camera":{"vendorTags":[]}}""")) is ModuleManager.Result.Error)
        assertTrue(ModuleManager.check("""{"module":1,"id":"A B","config":{"stab":{"denoise":1}}}""") is ModuleManager.Result.Error)
        assertTrue(ModuleManager.check("not json") is ModuleManager.Result.Error)
        assertTrue(ModuleManager.check("""{"module":1,"id":"user.empty"}""") is ModuleManager.Result.Error)
    }
}
