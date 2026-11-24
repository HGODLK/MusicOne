package com.musicone.source.webdav

import com.musicone.source.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebDavSourceManifestTest {
    @Test
    fun manifestMatchesEntryDescriptorAndPolicies() {
        val stream = javaClass.classLoader!!.getResourceAsStream("musicone-source.json")
            ?: error("缺少测试资源 musicone-source.json")
        val json = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })

        val entry = WebDavSourceEntry()
        val d = entry.descriptor

        assertEquals(json.getString("id"), d.id)
        assertEquals(json.getString("name"), d.name)
        assertEquals(json.getString("kind"), d.kind.name)
        assertEquals(json.getLong("versionCode"), d.versionCode)
        assertEquals(json.getString("versionName"), d.versionName)
        assertEquals(WebDavSourceEntry::class.java.name, json.getString("entryClass"))
        assertEquals(SourceSdk.API_VERSION, json.getInt("apiVersion"))
        assertEquals(SourceSdk.KOTLIN_VERSION, json.getString("kotlinVersion"))
        assertEquals(SourceSdk.COMPOSE_VERSION, json.getString("composeVersion"))
        assertEquals(json.getBoolean("useCustomPages"), d.useCustomPages)
        assertEquals(json.getBoolean("requiresLogin"), d.requiresLogin)
        assertFalse("实现 SourceSupplementalPageProvider 时清单 useCustomPages 必须为 false", d.useCustomPages)

        val capsArr = json.getJSONArray("capabilities")
        val manifestCaps = (0 until capsArr.length()).map { SourceCapability.valueOf(capsArr.getString(it)) }.toSet()
        assertEquals(manifestCaps, d.capabilities)
        assertEquals(manifestCaps, entry.implementedCapabilities)

        val perms = json.getJSONObject("permissions")
        assertEquals(perms.getBoolean("editTrackMetadata"), d.permissions.editTrackMetadata)
        assertEquals(perms.getBoolean("replaceManualLyrics"), d.permissions.replaceManualLyrics)
        assertEquals(perms.getBoolean("adjustLineOffset"), d.permissions.adjustLineOffset)

        val pres = json.getJSONObject("presentation")
        assertEquals(pres.getLong("pageColorArgb"), d.presentation.pageColorArgb)
        assertEquals(pres.getLong("lineLeadMs"), d.presentation.lineLeadMs)
        assertEquals(pres.getLong("wordLeadMs"), d.presentation.wordLeadMs)

        // 校验逐页页面策略合法性
        assertEquals(SourceSupplementalPageMode.CUSTOM, entry.supplementalPagePolicy(SourceSupplementalPage.HOME).mode)
        assertEquals(SourceSupplementalPageMode.DEFAULT, entry.supplementalPagePolicy(SourceSupplementalPage.ACCOUNT).mode)
        assertEquals(SourceSupplementalPageMode.DEFAULT, entry.supplementalPagePolicy(SourceSupplementalPage.COLLECTIONS).mode)
        assertEquals(SourceSupplementalPageMode.DEFAULT, entry.supplementalPagePolicy(SourceSupplementalPage.COLLECTION).mode)
        assertEquals(SourceSupplementalPageMode.DISABLED, entry.supplementalPagePolicy(SourceSupplementalPage.LINK).mode)
        assertEquals(SourceSupplementalPageMode.DISABLED, entry.supplementalPagePolicy(SourceSupplementalPage.SETTINGS).mode)
        for (page in SourceSupplementalPage.entries) {
            val policy = entry.supplementalPagePolicy(page)
            if (policy.mode != SourceSupplementalPageMode.CUSTOM) {
                assertFalse("非 CUSTOM 页面不允许开启 allowPublicFallback", policy.allowPublicFallback)
            }
        }
    }
}
