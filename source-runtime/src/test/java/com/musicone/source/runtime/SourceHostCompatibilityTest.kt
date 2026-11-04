package com.musicone.source.runtime

import org.junit.Assert.*
import org.junit.Test

class SourceHostCompatibilityTest {
    private val json = """{"id":"org.example.source","name":"测试","kind":"SUPPLEMENTAL",
        "versionCode":1,"versionName":"1","capabilities":[],"entryClass":"org.example.Entry",
        "apiVersion":1,"minAndroid":23,"kotlinVersion":"2.3.21","composeVersion":"1.11.4"}"""
    private val host = SourceHostCompatibility(4, "0.3.0")

    @Test fun oldPackagesWithoutMinimumStillLoad() {
        SourceManifest.parse(json).checkCompatibility(37, host)
    }

    @Test fun numericHostCodeControlsCompatibilityAndErrorShowsRequiredVersion() {
        val manifest = SourceManifest.parse(json).copy(minHostVersionCode = 5, minHostVersionName = "0.3.1")
        val failure = assertThrows(IllegalArgumentException::class.java) { manifest.checkCompatibility(37, host) }
        assertTrue(failure.message!!.contains("0.3.1（code 5）"))
        manifest.checkCompatibility(37, host.copy(versionCode = 5, versionName = "任意展示名"))
        manifest.checkCompatibility(37, host.copy(versionCode = 10))
    }

    @Test fun explicitRuntimeCompatibilityAllowsOldCompilerAfterHostUpgrade() {
        // 此处只验证名单策略，合成版本组合不充当真实 ABI 验收证据。
        val upgraded = host.copy(kotlinVersions = setOf("2.3.21", "2.3.22"),
            composeVersions = setOf("1.11.4", "1.11.5"))
        val old = SourceManifest.parse(json)
        old.checkCompatibility(37, upgraded)
        old.copy(kotlinVersion = "2.3.22", composeVersion = "1.11.5").checkCompatibility(37, upgraded)
        assertThrows(IllegalArgumentException::class.java) {
            old.copy(kotlinVersion = "2.3.23").checkCompatibility(37, upgraded)
        }
        assertThrows(IllegalArgumentException::class.java) {
            old.copy(composeVersion = "1.11.6").checkCompatibility(37, upgraded)
        }
        assertThrows(IllegalArgumentException::class.java) {
            old.copy(kotlinVersion = "2.3.22").checkCompatibility(37, host)
        }
    }

    @Test fun minimumHostFieldsAreStrictAndNamesNeverReplaceCodes() {
        listOf("null", "0", "-1", "4.5", "\"4\"", "true").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SourceManifest.parse(json.dropLast(1) + ",\"minHostVersionCode\":$value}").checkCompatibility(37, host)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceManifest.parse(json.dropLast(1) + ",\"minHostVersionName\":\"0.3.1\"}")
        }
        val manifest = SourceManifest.parse(json.dropLast(1) +
            ",\"minHostVersionCode\":4,\"minHostVersionName\":\"0.3.0\"}")
        manifest.checkCompatibility(37, host)
        assertThrows(IllegalArgumentException::class.java) { manifest.checkCompatibility(37) }
    }
}
