package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    @Test fun versionSegmentsCompareAsNumbersFromLeftToRight() {
        fun version(value: String) = requireNotNull(MusicOneVersion.parse(value))
        assertTrue(version("0.1.10") > version("0.1.9"))
        assertTrue(version("0.2.0") > version("0.1.99"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertEquals(version("0.1.0"), version("v0.1.0"))
        assertEquals(version("0.1.0"), MusicOneVersion.installed("0.1.0-debugrelease"))
        listOf("", "0.1", "0.1.0.1", "0.-1.0", "0.1.0-", "999999999999999999999.1.0").forEach {
            assertNull(MusicOneVersion.parse(it))
        }
    }

    @Test fun nicknameVersionsWithVaryingChineseLengthsAreParsedAndComparedByNumbers() {
        fun version(value: String) = requireNotNull(MusicOneVersion.parse(value))
        val v020 = version("0.2.0-麦乐鸡块")
        assertEquals(0L, v020.major)
        assertEquals(2L, v020.minor)
        assertEquals(0L, v020.patch)
        assertEquals("麦乐鸡块", v020.nickname)
        assertEquals("0.2.0", v020.coreLabel)
        assertEquals("0.2.0-麦乐鸡块", v020.label)

        // 不同字数代号支持
        assertEquals("红", version("0.2.1-红").nickname)
        assertEquals("冰美式", version("0.2.1-冰美式").nickname)
        assertEquals("奥尔良烤翅", version("0.2.1-奥尔良烤翅").nickname)
        assertEquals("超长不定字数版本代号测试", version("0.2.1-超长不定字数版本代号测试").nickname)
        assertEquals("beta", version("0.1.0-beta").nickname)

        // 安装版本去除 debugrelease 后缀
        assertEquals(v020, MusicOneVersion.installed("0.2.0-麦乐鸡块-debugrelease"))
        assertEquals(v020, MusicOneVersion.installed("0.2.0-麦乐鸡块"))

        // 版本比较仅基于主版本数字，代号不参与大小排序；同版本不同代号视为相同版本
        assertTrue(version("0.2.1-冰美式") > version("0.2.0-麦乐鸡块"))
        assertTrue(version("0.2.0-麦乐鸡块") > version("0.1.9-红"))
        assertEquals(0, version("0.2.0-麦乐鸡块").compareTo(version("0.2.0-冰美式")))
    }

    @Test fun reminderRequiresAnOpeningWindowAndExactlySeventyTwoHours() {
        val lastShown = 1_000L
        val gate = UpdatePromptGate()
        gate.open(0)
        gate.setForeground(true)
        assertFalse(gate.allows(10, lastShown + UPDATE_REMINDER_INTERVAL_MS - 1, lastShown))
        assertTrue(gate.allows(10, lastShown + UPDATE_REMINDER_INTERVAL_MS, lastShown))
        assertFalse(gate.allows(10, lastShown - 1, lastShown))
        gate.close()
        assertFalse(gate.allows(20, lastShown + UPDATE_REMINDER_INTERVAL_MS * 2, lastShown))
        gate.open(30)
        gate.setForeground(true)
        assertTrue(gate.allows(40, lastShown + UPDATE_REMINDER_INTERVAL_MS * 2, lastShown))
    }

    @Test fun lateResultsAndResumingAfterAPermissionDialogCannotOpenAPrompt() {
        val gate = UpdatePromptGate()
        gate.open(100)
        assertFalse(gate.allows(101, 0, null))
        gate.setForeground(true)
        assertTrue(gate.allows(101, 0, null))
        assertFalse(gate.allows(100 + UPDATE_OPENING_WINDOW_MS, 0, null))
        gate.setForeground(false)
        gate.setForeground(true)
        assertFalse(gate.allows(102, 0, null))
        gate.open(200)
        gate.setForeground(true)
        gate.close()
        assertFalse(gate.allows(201, 0, null))
    }

    @Test fun persistedReminderTimeStillSuppressesANewerReleaseAfterRestart() {
        val latest = MusicOneRelease(requireNotNull(MusicOneVersion.parse("0.2.0")), "unused")
        assertNotNull(AppUpdateUiState("0.1.0", latest).available)
        assertNull(AppUpdateUiState("0.2.0", latest).available)
        assertNull(AppUpdateUiState("0.3.0", latest).available)

        // 验证当前带代号版本 "0.2.0-麦乐鸡块" 能正确计算 available
        val newerRelease = MusicOneRelease(requireNotNull(MusicOneVersion.parse("0.2.1-冰美式")), "unused")
        val sameRelease = MusicOneRelease(requireNotNull(MusicOneVersion.parse("0.2.0-冰美式")), "unused")
        assertNotNull(AppUpdateUiState("0.2.0-麦乐鸡块", newerRelease).available)
        assertNull(AppUpdateUiState("0.2.0-麦乐鸡块", sameRelease).available)
        assertNull(AppUpdateUiState("0.2.0-麦乐鸡块", latest).available)

        val restartedGate = UpdatePromptGate()
        restartedGate.open(0)
        restartedGate.setForeground(true)
        assertFalse(restartedGate.allows(1, UPDATE_REMINDER_INTERVAL_MS - 1, 0))
        assertTrue(restartedGate.allows(1, UPDATE_REMINDER_INTERVAL_MS, 0))
    }
}
