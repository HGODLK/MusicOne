package com.musicone

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CustomFontSettingsTest {

    @Test
    fun unmodifiedCustomModeMatchesDefaultForTypographyLyricsAndClock() {
        assertEquals(AppFontMode.CUSTOM, AppFontMode.fromStored("custom"))
        val empty = CustomFontConfig()
        assertFalse(empty.hasActiveOverrides())
        assertEquals(CustomFontFallback.DEFAULT, empty.fallback)
        assertEquals(CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX, empty.ghProxyPrefix)

        assertSame(MusicOneTypography, musicOneTypography(AppFontMode.CUSTOM, empty, 0L))
        assertEquals(
            musicOneUiAnnotatedString("简体中文 Music 123", mode = AppFontMode.DEFAULT),
            musicOneUiAnnotatedString("简体中文 Music 123", mode = AppFontMode.CUSTOM),
        )
        assertEquals(
            musicOneDisplayAnnotatedString("星光 Starlight سلام", mode = AppFontMode.DEFAULT),
            musicOneDisplayAnnotatedString("星光 Starlight سلام", mode = AppFontMode.CUSTOM),
        )

        val defaultClock = FontFamily.Serif
        assertSame(
            defaultClock,
            resolvePhoneLandscapeClockFontFamily(AppFontMode.CUSTOM, empty, defaultClock) { null },
        )
    }

    @Test
    fun landscapeClockOnlyAppliesCustomBindingInCustomFontMode() {
        val defaultClock = FontFamily.Serif
        val importedClock = FontFamily.Monospace
        val rec = CustomFontRecord("f1", "JetBrainsMono", "f1.ttf", 1024L, 1L)
        val config = CustomFontConfig(
            records = listOf(rec),
            uiFonts = mapOf(MusicOneScript.LATIN to "f1"),
            clockFont = CustomClockFontBinding.Font("f1"),
        )

        assertSame(defaultClock, resolvePhoneLandscapeClockFontFamily(AppFontMode.DEFAULT, config, defaultClock) { importedClock })
        assertSame(defaultClock, resolvePhoneLandscapeClockFontFamily(AppFontMode.SANS_SERIF, config, defaultClock) { importedClock })
        assertSame(defaultClock, resolvePhoneLandscapeClockFontFamily(AppFontMode.SYSTEM, config, defaultClock) { importedClock })
        assertSame(importedClock, resolvePhoneLandscapeClockFontFamily(AppFontMode.CUSTOM, config, defaultClock) {
            if (it == "f1") importedClock else null
        })

        val followUi = config.copy(clockFont = CustomClockFontBinding.FollowUiLatin)
        assertSame(importedClock, resolvePhoneLandscapeClockFontFamily(AppFontMode.CUSTOM, followUi, defaultClock) {
            if (it == "f1") importedClock else null
        })

        val systemClock = config.copy(clockFont = CustomClockFontBinding.System)
        assertSame(FontFamily.Default, resolvePhoneLandscapeClockFontFamily(AppFontMode.CUSTOM, systemClock, defaultClock) { null })
    }

    @Test
    fun configRoundTripAndRecordDeletionCleanAllBindings() {
        val recA = CustomFontRecord("a1", "LXGW", "font_a.ttf", 2048L, 100L)
        val recB = CustomFontRecord("b2", "Vazirmatn", "font_b.otf", 4096L, 200L)
        val original = CustomFontConfig(
            fallback = CustomFontFallback.SANS_SERIF,
            uiFonts = mapOf(MusicOneScript.SC to "a1", MusicOneScript.ARABIC to "b2"),
            lyricFonts = mapOf(
                MusicOneScript.SC to CustomLyricSlot.FollowUi,
                MusicOneScript.ARABIC to CustomLyricSlot.Font("b2"),
            ),
            clockFont = CustomClockFontBinding.Font("a1"),
            downloadChannel = CustomFontDownloadChannel.DIRECT,
            ghProxyPrefix = "https://mirror.example.com/",
            records = listOf(recA, recB),
        )

        val decoded = decodeCustomFontConfig(encodeCustomFontConfig(original))
        assertEquals(original, decoded)
        assertEquals("a1", decoded.resolveLyricFontId(MusicOneScript.SC))
        assertEquals("b2", decoded.resolveLyricFontId(MusicOneScript.ARABIC))

        val afterDeleteA = decoded.removingRecord("a1")
        assertNull(afterDeleteA.resolveUiFontId(MusicOneScript.SC))
        assertNull(afterDeleteA.resolveLyricFontId(MusicOneScript.SC))
        assertEquals(CustomClockFontBinding.Default, afterDeleteA.clockFont)
        assertEquals("b2", afterDeleteA.resolveLyricFontId(MusicOneScript.ARABIC))

        val recC = CustomFontRecord("c3", "Outfit", "font_c.ttf", 1024L, 300L)
        val batchConfig = original.copy(
            records = listOf(recA, recB, recC),
            uiFonts = original.uiFonts + (MusicOneScript.LATIN to "c3"),
        )
        val afterBatch = batchConfig.removingRecords(setOf("a1", "b2"))
        assertEquals(listOf(recC), afterBatch.records)
        assertNull(afterBatch.resolveUiFontId(MusicOneScript.SC))
        assertNull(afterBatch.resolveUiFontId(MusicOneScript.ARABIC))
        assertNull(afterBatch.resolveLyricFontId(MusicOneScript.ARABIC))
        assertEquals(CustomClockFontBinding.Default, afterBatch.clockFont)
        assertEquals("c3", afterBatch.resolveUiFontId(MusicOneScript.LATIN))

        assertTrue(CUSTOM_FONT_MAX_FILE_BYTES >= 256L * 1024L * 1024L)
        assertTrue(CUSTOM_FONT_MAX_ARCHIVE_BYTES >= 1024L * 1024L * 1024L)
    }

    @Test
    fun fontHeaderValidationAndVariableFontDetectionWork() {
        assertTrue(isSupportedFontHeader(byteArrayOf(0x00, 0x01, 0x00, 0x00)))
        assertTrue(isSupportedFontHeader("OTTO".toByteArray()))
        assertTrue(isSupportedFontHeader("true".toByteArray()))
        assertTrue(isSupportedFontHeader("ttcf".toByteArray()))
        assertFalse(isSupportedFontHeader("PK\u0003\u0004".toByteArray()))
        assertFalse(isSupportedFontHeader(byteArrayOf(1, 2)))

        val vf = ByteArray(12 + 16)
        vf[1] = 1
        vf[5] = 1
        "fvar".toByteArray().copyInto(vf, 12)
        assertTrue(isVariableFontBytes(vf))

        val staticFont = ByteArray(12 + 16)
        staticFont[1] = 1
        staticFont[5] = 1
        "glyf".toByteArray().copyInto(staticFont, 12)
        assertFalse(isVariableFontBytes(staticFont))
    }

    @Test
    fun githubLinkParsingAndGhProxyPrefixValidationWork() {
        val blob = parseCustomFontLink("https://github.com/rastikerdar/vazirmatn/blob/master/fonts/ttf/Vazirmatn-Regular.ttf")
        assertTrue(blob is CustomFontLinkTarget.DirectFile)
        blob as CustomFontLinkTarget.DirectFile
        assertEquals(
            "https://raw.githubusercontent.com/rastikerdar/vazirmatn/master/fonts/ttf/Vazirmatn-Regular.ttf",
            blob.originalUrl,
        )
        assertEquals("Vazirmatn-Regular.ttf", blob.suggestedName)

        assertEquals(
            CustomFontLinkTarget.GitHubRelease("lxgw", "LxgwWenKai", null),
            parseCustomFontLink("https://github.com/lxgw/LxgwWenKai"),
        )
        assertEquals(
            CustomFontLinkTarget.GitHubRelease("lxgw", "LxgwWenKai", "v1.500"),
            parseCustomFontLink("https://github.com/lxgw/LxgwWenKai/releases/tag/v1.500"),
        )

        assertNull(validateGhProxyPrefix(""))
        assertNull(validateGhProxyPrefix("https://gh-proxy.com/"))
        assertNull(validateGhProxyPrefix("https://alt.ghproxy.net"))
        assertNotNull(validateGhProxyPrefix("ftp://bad.host"))
        assertNotNull(validateGhProxyPrefix("not-a-url"))

        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/a/b/main/c.ttf",
            resolveCustomFontDownloadUrl(
                "https://raw.githubusercontent.com/a/b/main/c.ttf",
                CustomFontDownloadChannel.GHPROXY,
                CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX,
            ),
        )
        assertEquals(
            "https://my.proxy.test/https://github.com/a/b/releases/download/v1/f.zip",
            resolveCustomFontDownloadUrl(
                "https://github.com/a/b/releases/download/v1/f.zip",
                CustomFontDownloadChannel.GHPROXY,
                "https://my.proxy.test",
            ),
        )
        assertEquals(
            "https://github.com/a/b/releases/download/v1/f.zip",
            resolveCustomFontDownloadUrl(
                "https://github.com/a/b/releases/download/v1/f.zip",
                CustomFontDownloadChannel.DIRECT,
                CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX,
            ),
        )
        assertEquals(
            "https://cdn.example.com/font.otf",
            resolveCustomFontDownloadUrl(
                "https://cdn.example.com/font.otf",
                CustomFontDownloadChannel.GHPROXY,
                CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX,
            ),
        )
    }

    @Test
    fun releaseAssetsJsonAndZipInspectionFilterUnsafeEntries() {
        val json = """
            {
              "draft": false,
              "assets": [
                {"name": "README.txt", "browser_download_url": "https://github.com/a/b/releases/download/v1/README.txt", "size": 100},
                {"name": "MyFont-v1.zip", "browser_download_url": "https://github.com/a/b/releases/download/v1/MyFont-v1.zip", "size": 5242880},
                {"name": "MyFont-Regular.ttf", "browser_download_url": "https://github.com/a/b/releases/download/v1/MyFont-Regular.ttf", "size": 1048576}
              ]
            }
        """.trimIndent()
        val assets = parseGitHubReleaseAssets(json)
        assertEquals(2, assets.size)
        assertEquals("MyFont-v1.zip", assets[0].name)
        assertEquals("MyFont-Regular.ttf", assets[1].name)

        val tempZip = File.createTempFile("custom_font_test_", ".zip")
        try {
            ZipOutputStream(FileOutputStream(tempZip)).use { zip ->
                fun put(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
                put("__MACOSX/._Bad.ttf", byteArrayOf(0, 1, 0, 0))
                put("../evil.ttf", byteArrayOf(0, 1, 0, 0))
                put("fonts/Vazirmatn-SemiBold.ttf", byteArrayOf(0, 1, 0, 0))
                put("fonts/notes.txt", "ignore".toByteArray())
            }
            val entries = inspectFontZip(tempZip)
            assertEquals(1, entries.size)
            assertEquals("fonts/Vazirmatn-SemiBold.ttf", entries[0].entryPath)
            assertEquals("Vazirmatn-SemiBold", entries[0].displayName)
            assertEquals("半粗 · 阿拉伯/波斯", entries[0].hint)
        } finally {
            tempZip.delete()
        }
    }

    @Test
    fun compositeUiFallbackOrderFollowsLocaleAndDeduplicates() {
        // 回退顺序：LATIN、ARABIC、KO 优先级固定，其后按语言偏好排列 CJK
        assertEquals(
            listOf(
                MusicOneScript.LATIN,
                MusicOneScript.ARABIC,
                MusicOneScript.KO,
                MusicOneScript.SC,
                MusicOneScript.TC,
                MusicOneScript.JA,
            ),
            customUiFallbackScriptOrder(Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            listOf(
                MusicOneScript.LATIN,
                MusicOneScript.ARABIC,
                MusicOneScript.KO,
                MusicOneScript.TC,
                MusicOneScript.SC,
                MusicOneScript.JA,
            ),
            customUiFallbackScriptOrder(Locale.TRADITIONAL_CHINESE),
        )
        assertEquals(
            listOf(
                MusicOneScript.LATIN,
                MusicOneScript.ARABIC,
                MusicOneScript.KO,
                MusicOneScript.JA,
                MusicOneScript.SC,
                MusicOneScript.TC,
            ),
            customUiFallbackScriptOrder(Locale.JAPANESE),
        )
        assertEquals(
            listOf(
                MusicOneScript.LATIN,
                MusicOneScript.ARABIC,
                MusicOneScript.KO,
                MusicOneScript.SC,
                MusicOneScript.TC,
                MusicOneScript.JA,
            ),
            customUiFallbackScriptOrder(Locale.US),
        )

        val recLatin = CustomFontRecord("f_latin", "Outfit", "f_latin.ttf", 1024L, 1L)
        val recAr = CustomFontRecord("f_ar", "Vazirmatn", "f_ar.ttf", 1024L, 2L)
        val recCjk = CustomFontRecord("f_cjk", "LXGW", "f_cjk.ttf", 1024L, 3L)
        val recJa = CustomFontRecord("f_ja", "NotoSansJP", "f_ja.ttf", 1024L, 4L)

        // 仅绑定 LATIN/ARABIC 时只返回这两个字体
        val latinArabic = CustomFontConfig(
            uiFonts = mapOf(MusicOneScript.LATIN to "f_latin", MusicOneScript.ARABIC to "f_ar"),
            records = listOf(recLatin, recAr),
        )
        assertEquals(
            listOf("f_latin", "f_ar"),
            resolveCompositeUiFontIds(latinArabic, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals("f_latin", resolvePrimaryUiFontId(latinArabic, Locale.SIMPLIFIED_CHINESE))

        // SC/TC 绑定同一字体时应去重且保留首次出现顺序
        val cjk = CustomFontConfig(
            uiFonts = mapOf(
                MusicOneScript.SC to "f_cjk",
                MusicOneScript.TC to "f_cjk",
                MusicOneScript.JA to "f_ja",
                MusicOneScript.LATIN to "f_latin",
            ),
            records = listOf(recLatin, recCjk, recJa),
        )
        assertEquals(
            listOf("f_latin", "f_cjk", "f_ja"),
            resolveCompositeUiFontIds(cjk, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals("f_cjk", resolvePrimaryUiFontId(cjk, Locale.SIMPLIFIED_CHINESE))
        assertEquals("f_ja", resolvePrimaryUiFontId(cjk, Locale.JAPANESE))
    }

    @Test
    fun musicOneTypographyAppliesCustomFamilyOverrideAndFallback() {
        val empty = CustomFontConfig()
        val typo = musicOneTypography(AppFontMode.CUSTOM, empty, 1L, customFamilyOverride = FontFamily.Monospace)
        assertEquals(FontFamily.Monospace, typo.bodyLarge.fontFamily)
        assertEquals(FontFamily.Monospace, typo.titleLarge.fontFamily)

        // 相同 revision 下不同 family 应命中不同缓存
        val serifTypo = musicOneTypography(AppFontMode.CUSTOM, empty, 1L, customFamilyOverride = FontFamily.Serif)
        assertEquals(FontFamily.Serif, serifTypo.bodyLarge.fontFamily)

        // 无 override 且回退系统字体时使用默认字体
        val systemTypo = musicOneTypography(
            AppFontMode.CUSTOM,
            CustomFontConfig(fallback = CustomFontFallback.SYSTEM),
            2L,
        )
        assertEquals(FontFamily.Default, systemTypo.bodyLarge.fontFamily)
    }

    @Test
    fun blurSettingsHiddenBeforeAndroid12() {
        assertFalse(supportsBlurSettings(23))
        assertFalse(supportsBlurSettings(30))
        assertTrue(supportsBlurSettings(31))
        assertTrue(supportsBlurSettings(35))
    }
}
