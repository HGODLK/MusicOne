package com.musicone.source.qq

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqSourceManifestTest {
    @Test fun realEntryMatchesTheDistributedManifest() {
        val json = javaClass.classLoader!!.getResourceAsStream("musicone-source.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val entry = QqSourceEntry()
        val actual = entry.descriptor
        assertEquals(json.getString("entryClass"), entry.javaClass.name)
        assertEquals(json.getString("id"), actual.id)
        assertEquals(json.getString("name"), actual.name)
        assertEquals(json.getString("kind"), actual.kind.name)
        assertEquals(json.getLong("versionCode"), actual.versionCode)
        assertEquals(json.getString("versionName"), actual.versionName)
        assertEquals(json.getBoolean("useCustomPages"), actual.useCustomPages)
        assertEquals(json.getBoolean("requiresLogin"), actual.requiresLogin)
        val capabilities = json.getJSONArray("capabilities")
        assertEquals((0 until capabilities.length()).map { capabilities.getString(it) }.toSet(),
            actual.capabilities.map { it.name }.toSet())
        val permissions = json.getJSONObject("permissions")
        assertEquals(permissions.getBoolean("editTrackMetadata"), actual.permissions.editTrackMetadata)
        assertEquals(permissions.getBoolean("replaceManualLyrics"), actual.permissions.replaceManualLyrics)
        assertEquals(permissions.getBoolean("adjustLineOffset"), actual.permissions.adjustLineOffset)
        val presentation = json.getJSONObject("presentation")
        assertEquals(presentation.getLong("pageColorArgb"), actual.presentation.pageColorArgb)
        assertEquals(presentation.getLong("radioColorArgb"), actual.presentation.radioColorArgb)
        assertEquals(presentation.getString("radioLabel"), actual.presentation.radioLabel)
        assertEquals(presentation.getLong("lineLeadMs"), actual.presentation.lineLeadMs)
        assertEquals(presentation.getLong("wordLeadMs"), actual.presentation.wordLeadMs)
        for ((key, colors) in listOf("radioPaletteLight" to actual.presentation.radioPaletteLight,
            "radioPaletteDark" to actual.presentation.radioPaletteDark)) {
            val expected = presentation.getJSONArray(key)
            assertEquals((0 until expected.length()).map { expected.getLong(it) }, colors)
        }
    }
}
