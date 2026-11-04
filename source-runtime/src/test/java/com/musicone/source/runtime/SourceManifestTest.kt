package com.musicone.source.runtime

import com.musicone.source.sdk.SourceKind
import org.junit.Assert.*
import org.junit.Test

class SourceManifestTest {
    private val manifest = """{
        "id":"org.example.source","name":"测试音源","kind":"SUPPLEMENTAL",
        "versionCode":10,"versionName":"1.10.0","capabilities":[],
        "entryClass":"org.example.Source","apiVersion":1,"minAndroid":23,
        "kotlinVersion":"2.3.21","composeVersion":"1.11.4"
    }"""
    @Test fun omittedOptionalFieldsDoNotGrantPermissionsOrCapabilities() {
        val value = SourceManifest.parse(manifest)
        value.checkCompatibility(37)
        assertEquals(SourceKind.SUPPLEMENTAL, value.descriptor.kind)
        assertFalse(value.descriptor.useCustomPages)
        assertFalse(value.descriptor.requiresLogin)
        assertFalse(value.descriptor.permissions.replaceManualLyrics)
        assertFalse(value.descriptor.permissions.adjustLineOffset)
        assertEquals(0L, value.descriptor.presentation.lineLeadMs)
        assertEquals(0L, value.descriptor.presentation.wordLeadMs)
        assertEquals("猜你喜欢", value.descriptor.presentation.radioLabel)
    }
    @Test fun hostRejectsDifferentComposeRuntime() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceManifest.parse(manifest.replace("1.11.4", "1.9.0")).checkCompatibility(37)
        }
    }
    @Test fun hostRejectsUnsupportedAndroidAndSdkVersions() {
        assertThrows(IllegalArgumentException::class.java) { SourceManifest.parse(manifest).checkCompatibility(22) }
        assertThrows(IllegalArgumentException::class.java) {
            SourceManifest.parse(manifest.replace("\"apiVersion\":1", "\"apiVersion\":2")).checkCompatibility(37)
        }
    }
}
