package com.musicone

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

internal const val CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX = "https://gh-proxy.com/"
internal const val CUSTOM_FONT_MAX_FILE_BYTES = 256L * 1024L * 1024L
internal const val CUSTOM_FONT_MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L

/** 自定义字体未绑定槽位时的回退风格；初始为“默认”，未修改时表现与默认模式一致。 */
internal enum class CustomFontFallback(val storedValue: String, val label: String) {
    DEFAULT("default", "默认"),
    SANS_SERIF("sans_serif", "黑体"),
    SYSTEM("system", "跟随系统");

    fun asBaseFontMode(): AppFontMode = when (this) {
        DEFAULT -> AppFontMode.DEFAULT
        SANS_SERIF -> AppFontMode.SANS_SERIF
        SYSTEM -> AppFontMode.SYSTEM
    }

    companion object {
        fun fromStored(value: String?): CustomFontFallback =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

/** GitHub 字体下载通道。 */
internal enum class CustomFontDownloadChannel(val storedValue: String, val label: String) {
    GHPROXY("ghproxy", "ghproxy 加速"),
    DIRECT("direct", "直连 GitHub");

    companion object {
        fun fromStored(value: String?): CustomFontDownloadChannel =
            entries.firstOrNull { it.storedValue == value } ?: GHPROXY
    }
}

/** 歌词语系槽位绑定；未出现在映射表时表示跟随回退基底。 */
internal sealed interface CustomLyricSlot {
    data object FollowUi : CustomLyricSlot
    data class Font(val fontId: String) : CustomLyricSlot
}

/** 横屏时钟字体绑定（仅在全局字体模式为“自定义”时生效）。 */
internal sealed interface CustomClockFontBinding {
    data object Default : CustomClockFontBinding
    data object FollowUiLatin : CustomClockFontBinding
    data object FollowLyricLatin : CustomClockFontBinding
    data object System : CustomClockFontBinding
    data class Font(val fontId: String) : CustomClockFontBinding
}

internal data class CustomFontRecord(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
)

internal data class CustomFontConfig(
    val fallback: CustomFontFallback = CustomFontFallback.DEFAULT,
    val uiFonts: Map<MusicOneScript, String> = emptyMap(),
    val lyricFonts: Map<MusicOneScript, CustomLyricSlot> = emptyMap(),
    val clockFont: CustomClockFontBinding = CustomClockFontBinding.Default,
    val downloadChannel: CustomFontDownloadChannel = CustomFontDownloadChannel.GHPROXY,
    val ghProxyPrefix: String = CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX,
    val records: List<CustomFontRecord> = emptyList(),
) {
    fun recordById(id: String?): CustomFontRecord? = if (id == null) null else records.firstOrNull { it.id == id }

    fun resolveUiFontId(script: MusicOneScript): String? =
        uiFonts[script]?.takeIf { recordById(it) != null }

    fun resolveLyricFontId(script: MusicOneScript): String? = when (val slot = lyricFonts[script]) {
        null -> null
        CustomLyricSlot.FollowUi -> resolveUiFontId(script)
        is CustomLyricSlot.Font -> slot.fontId.takeIf { recordById(it) != null }
    }

    fun hasActiveOverrides(): Boolean =
        fallback != CustomFontFallback.DEFAULT ||
            uiFonts.values.any { recordById(it) != null } ||
            lyricFonts.values.any { slot ->
                when (slot) {
                    CustomLyricSlot.FollowUi -> true
                    is CustomLyricSlot.Font -> recordById(slot.fontId) != null
                }
            } ||
            clockFont != CustomClockFontBinding.Default

    fun removingRecord(fontId: String): CustomFontConfig = removingRecords(setOf(fontId))

    fun removingRecords(fontIds: Set<String>): CustomFontConfig {
        if (fontIds.isEmpty()) return this
        return copy(
            records = records.filterNot { it.id in fontIds },
            uiFonts = uiFonts.filterValues { it !in fontIds },
            lyricFonts = lyricFonts.filterValues { it !is CustomLyricSlot.Font || it.fontId !in fontIds },
            clockFont = if (clockFont is CustomClockFontBinding.Font && clockFont.fontId in fontIds) {
                CustomClockFontBinding.Default
            } else clockFont,
        )
    }

    fun boundLabels(fontId: String): List<String> {
        val labels = ArrayList<String>()
        for (script in MusicOneScript.entries) {
            if (uiFonts[script] == fontId) labels += "界面·${script.displayLabel}"
        }
        for (script in MusicOneScript.entries) {
            val slot = lyricFonts[script]
            if (slot is CustomLyricSlot.Font && slot.fontId == fontId) labels += "歌词·${script.displayLabel}"
        }
        if (clockFont is CustomClockFontBinding.Font && clockFont.fontId == fontId) labels += "横屏时钟"
        return labels
    }
}

internal val MusicOneScript.displayLabel: String
    get() = when (this) {
        MusicOneScript.SC -> "简体中文"
        MusicOneScript.TC -> "繁体中文"
        MusicOneScript.JA -> "日文"
        MusicOneScript.KO -> "韩文"
        MusicOneScript.LATIN -> "拉丁 / 西文"
        MusicOneScript.ARABIC -> "阿拉伯 / 波斯"
    }

/** 按语系决定 CJK 槽位的优先级顺序：日文区优先日文，港澳台或繁中优先繁体，其余优先简体。 */
private fun cjkScriptOrder(locale: Locale): List<MusicOneScript> {
    val lang = locale.language.lowercase(Locale.ROOT)
    return when {
        lang == "ja" -> listOf(MusicOneScript.JA, MusicOneScript.SC, MusicOneScript.TC)
        lang == "zh" && (
            locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
            ) -> listOf(MusicOneScript.TC, MusicOneScript.SC, MusicOneScript.JA)
        else -> listOf(MusicOneScript.SC, MusicOneScript.TC, MusicOneScript.JA)
    }
}

/** 界面复合字体所需语系槽位顺序：拉丁、阿拉伯、韩文在前，CJK 依区域排后。 */
internal fun customUiFallbackScriptOrder(locale: Locale = Locale.getDefault()): List<MusicOneScript> =
    listOf(MusicOneScript.LATIN, MusicOneScript.ARABIC, MusicOneScript.KO) + cjkScriptOrder(locale)

/** 按语系顺序收集所有已配置的界面字体 ID（去重）。 */
internal fun resolveCompositeUiFontIds(
    config: CustomFontConfig,
    locale: Locale = Locale.getDefault(),
): List<String> = customUiFallbackScriptOrder(locale)
    .mapNotNull { config.resolveUiFontId(it) }
    .distinct()

/** 取当前语系下最优先的界面字体 ID；CJK 槽位全空时退回复合顺序的首个 ID。 */
internal fun resolvePrimaryUiFontId(
    config: CustomFontConfig,
    locale: Locale = Locale.getDefault(),
): String? = cjkScriptOrder(locale)
    .firstNotNullOfOrNull { config.resolveUiFontId(it) }
    ?: resolveCompositeUiFontIds(config, locale).firstOrNull()

internal fun isSupportedFontFileName(name: String): Boolean {
    val lower = name.trim().lowercase(Locale.ROOT)
    return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
}

internal fun fontExtensionOf(name: String): String = when {
    name.trim().lowercase(Locale.ROOT).endsWith(".otf") -> "otf"
    name.trim().lowercase(Locale.ROOT).endsWith(".ttc") -> "ttc"
    else -> "ttf"
}

/** 校验 TrueType / OpenType / TTC 文件头魔数。 */
internal fun isSupportedFontHeader(header: ByteArray, length: Int = header.size): Boolean {
    if (length < 4) return false
    val b0 = header[0].toInt() and 0xFF
    val b1 = header[1].toInt() and 0xFF
    val b2 = header[2].toInt() and 0xFF
    val b3 = header[3].toInt() and 0xFF
    return (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) ||
        (b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code) ||
        (b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code) ||
        (b0 == 't'.code && b1 == 'y'.code && b2 == 'p'.code && b3 == '1'.code) ||
        (b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code)
}

/** 校验用户输入的 ghproxy 前缀；合法返回 null，非法返回中文错误提示。 */
internal fun validateGhProxyPrefix(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase(Locale.ROOT)
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
        return "ghproxy 前缀必须以 http:// 或 https:// 开头"
    }
    val host = runCatching { java.net.URI.create(trimmed).host }.getOrNull()
    if (host.isNullOrBlank()) return "ghproxy 前缀格式无效"
    return null
}

internal fun normalizeGhProxyPrefix(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || validateGhProxyPrefix(trimmed) != null) return CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}

internal fun cleanFontDisplayName(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
    val stripped = base.replace(Regex("\\.(ttf|otf|ttc)$", RegexOption.IGNORE_CASE), "")
    return stripped.ifBlank { base.ifBlank { "自定义字体" } }
}

/** 检测 SFNT 表目录是否包含 `fvar` 表，区分可变字体与静态字体。 */
internal fun isVariableFontBytes(bytes: ByteArray, length: Int = bytes.size): Boolean {
    if (length < 12) return false
    val numTables = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
    if (numTables <= 0 || numTables > 256) return false
    val limit = minOf(length, 12 + numTables * 16)
    var offset = 12
    while (offset + 4 <= limit) {
        if (bytes[offset].toInt() == 'f'.code && bytes[offset + 1].toInt() == 'v'.code &&
            bytes[offset + 2].toInt() == 'a'.code && bytes[offset + 3].toInt() == 'r'.code
        ) return true
        offset += 16
    }
    return false
}

private fun isVariableFontFile(file: File): Boolean = runCatching {
    FileInputStream(file).use { input ->
        val header = ByteArray(12 + 128 * 16)
        val read = input.read(header)
        read >= 12 && isVariableFontBytes(header, read)
    }
}.getOrDefault(false)

internal fun encodeCustomFontConfig(config: CustomFontConfig): String = JSONObject().apply {
    put("fallback", config.fallback.storedValue)
    put("downloadChannel", config.downloadChannel.storedValue)
    put("ghProxyPrefix", normalizeGhProxyPrefix(config.ghProxyPrefix))
    put("uiFonts", JSONObject().apply { config.uiFonts.forEach { (k, v) -> put(k.name, v) } })
    put("lyricFonts", JSONObject().apply {
        config.lyricFonts.forEach { (k, v) ->
            put(k.name, when (v) {
                CustomLyricSlot.FollowUi -> "follow_ui"
                is CustomLyricSlot.Font -> "font:${v.fontId}"
            })
        }
    })
    put("clockFont", when (val c = config.clockFont) {
        CustomClockFontBinding.Default -> "default"
        CustomClockFontBinding.FollowUiLatin -> "follow_ui_latin"
        CustomClockFontBinding.FollowLyricLatin -> "follow_lyric_latin"
        CustomClockFontBinding.System -> "system"
        is CustomClockFontBinding.Font -> "font:${c.fontId}"
    })
    put("records", JSONArray().apply {
        config.records.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id)
                put("displayName", r.displayName)
                put("fileName", r.fileName)
                put("sizeBytes", r.sizeBytes)
                put("addedAtMs", r.addedAtMs)
            })
        }
    })
}.toString()

internal fun decodeCustomFontConfig(raw: String?): CustomFontConfig {
    if (raw.isNullOrBlank()) return CustomFontConfig()
    return runCatching {
        val root = JSONObject(raw)
        val records = ArrayList<CustomFontRecord>()
        root.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val fileName = obj.optString("fileName").trim()
                if (id.isEmpty() || fileName.isEmpty()) continue
                records += CustomFontRecord(
                    id = id,
                    displayName = obj.optString("displayName").ifBlank { cleanFontDisplayName(fileName) },
                    fileName = fileName,
                    sizeBytes = obj.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                    addedAtMs = obj.optLong("addedAtMs", 0L),
                )
            }
        }
        val validIds = records.mapTo(HashSet()) { it.id }
        val uiFonts = LinkedHashMap<MusicOneScript, String>()
        root.optJSONObject("uiFonts")?.let { obj ->
            MusicOneScript.entries.forEach { s ->
                obj.optString(s.name).takeIf { it.isNotBlank() && it in validIds }?.let { uiFonts[s] = it }
            }
        }
        val lyricFonts = LinkedHashMap<MusicOneScript, CustomLyricSlot>()
        root.optJSONObject("lyricFonts")?.let { obj ->
            MusicOneScript.entries.forEach { s ->
                val v = obj.optString(s.name)
                when {
                    v == "follow_ui" -> lyricFonts[s] = CustomLyricSlot.FollowUi
                    v.startsWith("font:") -> v.removePrefix("font:").takeIf { it in validIds }
                        ?.let { lyricFonts[s] = CustomLyricSlot.Font(it) }
                }
            }
        }
        val clockRaw = root.optString("clockFont", "default")
        val clockFont = when {
            clockRaw == "follow_ui_latin" -> CustomClockFontBinding.FollowUiLatin
            clockRaw == "follow_lyric_latin" -> CustomClockFontBinding.FollowLyricLatin
            clockRaw == "system" -> CustomClockFontBinding.System
            clockRaw.startsWith("font:") -> clockRaw.removePrefix("font:").takeIf { it in validIds }
                ?.let(CustomClockFontBinding::Font) ?: CustomClockFontBinding.Default
            else -> CustomClockFontBinding.Default
        }
        CustomFontConfig(
            fallback = CustomFontFallback.fromStored(root.optString("fallback")),
            uiFonts = uiFonts,
            lyricFonts = lyricFonts,
            clockFont = clockFont,
            downloadChannel = CustomFontDownloadChannel.fromStored(root.optString("downloadChannel")),
            ghProxyPrefix = normalizeGhProxyPrefix(root.optString("ghProxyPrefix", CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX)),
            records = records,
        )
    }.getOrDefault(CustomFontConfig())
}

/** 自定义字体存储与加载缓存；通过 [revision] 驱动主题、歌词、横屏时钟和桌面歌词热刷新。 */
internal object CustomFontStore {
    var config by mutableStateOf(CustomFontConfig())
        private set
    var revision by mutableLongStateOf(0L)
        private set

    private var preferences: SharedPreferences? = null
    private var fontDir: File? = null
    private val familyCache = HashMap<String, FontFamily>()
    private val typefaceCache = HashMap<Pair<String, Int>, Typeface>()
    private val baseTypefaceCache = HashMap<String, Typeface>()
    private var compositeFamilyCacheKey: Triple<Long, String, Int>? = null
    private var compositeFamilyCacheValue: FontFamily? = null

    fun initialize(context: Context) {
        if (preferences != null) return
        val app = context.applicationContext
        val dir = File(app.filesDir, "custom_fonts").apply { mkdirs() }
        val store = app.getSharedPreferences("custom_fonts", Context.MODE_PRIVATE)
        fontDir = dir
        preferences = store
        val decoded = decodeCustomFontConfig(store.getString(KEY_CONFIG, null))
        val existing = decoded.records.filter { File(dir, it.fileName).isFile }
        val cleaned = if (existing.size == decoded.records.size) decoded else {
            var next = decoded
            decoded.records.filterNot { it in existing }.forEach { next = next.removingRecord(it.id) }
            next
        }
        config = cleaned
        if (cleaned != decoded) persist(cleaned)
    }

    fun fontFile(fontId: String): File? {
        val dir = fontDir ?: return null
        val record = config.recordById(fontId) ?: return null
        return File(dir, record.fileName).takeIf { it.isFile }
    }

    @Synchronized
    fun fontFamily(fontId: String?): FontFamily? {
        if (fontId == null) return null
        familyCache[fontId]?.let { return it }
        val file = fontFile(fontId) ?: return null
        val family = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isVariableFontFile(file)) {
                FontFamily(
                    Font(file, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                    Font(file, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
                    Font(file, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
                    Font(file, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
                )
            } else {
                FontFamily(Font(file, FontWeight.Normal))
            }
        }.getOrNull() ?: return null
        familyCache[fontId] = family
        return family
    }

    @Synchronized
    fun typeface(fontId: String?, weight: Int = 400): Typeface? {
        if (fontId == null) return null
        val key = fontId to weight
        typefaceCache[key]?.let { return it }
        val file = fontFile(fontId) ?: return null
        val base = baseTypefaceCache[fontId] ?: runCatching { Typeface.createFromFile(file) }.getOrNull()
            ?.also { baseTypefaceCache[fontId] = it } ?: return null
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && weight != 400) {
            runCatching { Typeface.create(base, weight, false) }.getOrDefault(base)
        } else base
        typefaceCache[key] = resolved
        return resolved
    }

    /**
     * 为 MaterialTheme.typography 构建多语系复合 FontFamily：
     * Android 10+ 使用原生 CustomFallbackBuilder 按窄字符集优先串联所有已配界面字体并衔接回退基底；
     * Android 9 及以下优雅降级为单主字体。
     */
    @Synchronized
    fun compositeUiFontFamily(
        context: Context,
        customConfig: CustomFontConfig = config,
        customRevision: Long = revision,
        locale: Locale = Locale.getDefault(),
    ): FontFamily? {
        val orderedIds = resolveCompositeUiFontIds(customConfig, locale)
        if (orderedIds.isEmpty()) return null
        val cacheKey = Triple(customRevision, locale.toLanguageTag(), customConfig.fallback.ordinal)
        if (compositeFamilyCacheKey == cacheKey) return compositeFamilyCacheValue
        val resolved: FontFamily? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buildCompositeUiFontFamilyApi29(context.applicationContext, orderedIds, customConfig.fallback)
                ?: resolvePrimaryUiFontId(customConfig, locale)?.let(::fontFamily)
        } else {
            resolvePrimaryUiFontId(customConfig, locale)?.let(::fontFamily)
        }
        compositeFamilyCacheKey = cacheKey
        compositeFamilyCacheValue = resolved
        return resolved
    }

    /** 将单个字体文件转换为平台字体家族；可变字体按 400/500/600/700 四档注册。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildPlatformFontFamilyApi29(file: File): android.graphics.fonts.FontFamily? = runCatching {
        if (isVariableFontFile(file)) {
            var builder: android.graphics.fonts.FontFamily.Builder? = null
            for (weight in listOf(400, 500, 600, 700)) {
                val font = android.graphics.fonts.Font.Builder(file)
                    .setWeight(weight)
                    .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                    .setFontVariationSettings("'wght' $weight")
                    .build()
                builder = if (builder == null) {
                    android.graphics.fonts.FontFamily.Builder(font)
                } else {
                    builder.addFont(font)
                }
            }
            checkNotNull(builder) { "可变字体家族未构建" }.build()
        } else {
            android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(file).build()).build()
        }
    }.getOrNull()

    /** 构建内置兜底家族（更纱黑体 UI SC 与 Vazirmatn），仅保留成功项。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildBuiltInFallbackFamiliesApi29(context: Context): List<android.graphics.fonts.FontFamily> {
        val result = ArrayList<android.graphics.fonts.FontFamily>(2)
        runCatching {
            android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(context.resources, R.font.sarasa_ui_sc_regular)
                    .setWeight(400)
                    .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                    .build()
            )
                .addFont(
                    android.graphics.fonts.Font.Builder(context.resources, R.font.sarasa_ui_sc_semibold)
                        .setWeight(500)
                        .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                        .build()
                )
                .addFont(
                    android.graphics.fonts.Font.Builder(context.resources, R.font.sarasa_ui_sc_semibold)
                        .setWeight(600)
                        .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                        .build()
                )
                .addFont(
                    android.graphics.fonts.Font.Builder(context.resources, R.font.sarasa_ui_sc_bold)
                        .setWeight(700)
                        .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                        .build()
                )
                .build()
        }.getOrNull()?.let { result += it }
        runCatching {
            android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(context.resources, R.font.vazirmatn_regular)
                    .setWeight(400)
                    .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                    .build()
            )
                .addFont(
                    android.graphics.fonts.Font.Builder(context.resources, R.font.vazirmatn_semibold)
                        .setWeight(600)
                        .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                        .build()
                )
                .addFont(
                    android.graphics.fonts.Font.Builder(context.resources, R.font.vazirmatn_bold)
                        .setWeight(700)
                        .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                        .build()
                )
                .build()
        }.getOrNull()?.let { result += it }
        return result
    }

    /** 组合已配字体家族与内置兜底家族，最终衔接系统 sans-serif。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCompositeUiFontFamilyApi29(
        context: Context,
        orderedIds: List<String>,
        fallback: CustomFontFallback,
    ): FontFamily? {
        val families = orderedIds.mapNotNull { fontFile(it)?.let(::buildPlatformFontFamilyApi29) }
        if (families.isEmpty()) return null
        return runCatching {
            val builder = Typeface.CustomFallbackBuilder(families[0])
            families.drop(1).forEach { builder.addCustomFallback(it) }
            if (fallback != CustomFontFallback.SYSTEM) {
                buildBuiltInFallbackFamiliesApi29(context).forEach { builder.addCustomFallback(it) }
            }
            builder.setSystemFallback("sans-serif")
            FontFamily(builder.build())
        }.getOrNull()
    }

    fun updateFallback(fallback: CustomFontFallback) = update { it.copy(fallback = fallback) }
    fun setUiFont(script: MusicOneScript, fontId: String?) = update { c ->
        val next = c.uiFonts.toMutableMap().apply { if (fontId == null) remove(script) else put(script, fontId) }
        c.copy(uiFonts = next)
    }
    fun applyUiFontToAll(fontId: String?) = update { c ->
        c.copy(uiFonts = if (fontId == null) emptyMap() else MusicOneScript.entries.associateWith { fontId })
    }
    fun setLyricSlot(script: MusicOneScript, slot: CustomLyricSlot?) = update { c ->
        val next = c.lyricFonts.toMutableMap().apply { if (slot == null) remove(script) else put(script, slot) }
        c.copy(lyricFonts = next)
    }
    fun applyLyricSlotToAll(slot: CustomLyricSlot?) = update { c ->
        c.copy(lyricFonts = if (slot == null) emptyMap() else MusicOneScript.entries.associateWith { slot })
    }
    fun setClockFont(binding: CustomClockFontBinding) = update { it.copy(clockFont = binding) }
    fun updateDownloadOptions(channel: CustomFontDownloadChannel, ghProxyPrefix: String) = update {
        it.copy(downloadChannel = channel, ghProxyPrefix = normalizeGhProxyPrefix(ghProxyPrefix))
    }

    fun deleteFont(context: Context, fontId: String) {
        deleteFonts(context, listOf(fontId))
    }

    /** 批量移除多个字体，一次性清理缓存、槽位绑定并刷新版本，避免循环触发多次重绘。 */
    fun deleteFonts(context: Context, fontIds: Collection<String>): Int {
        initialize(context)
        val idSet = fontIds.toSet()
        if (idSet.isEmpty()) return 0
        val removed = config.records.filter { it.id in idSet }
        if (removed.isEmpty()) return 0
        synchronized(this) {
            idSet.forEach { id ->
                familyCache.remove(id)
                baseTypefaceCache.remove(id)
            }
            typefaceCache.keys.removeAll { it.first in idSet }
            compositeFamilyCacheKey = null
            compositeFamilyCacheValue = null
        }
        update { it.removingRecords(idSet) }
        fontDir?.let { dir -> removed.forEach { File(dir, it.fileName).delete() } }
        return removed.size
    }

    fun resetAll(context: Context) {
        initialize(context)
        val files = config.records.map { it.fileName }
        synchronized(this) {
            familyCache.clear()
            baseTypefaceCache.clear()
            typefaceCache.clear()
            compositeFamilyCacheKey = null
            compositeFamilyCacheValue = null
        }
        val keepChannel = config.downloadChannel
        val keepPrefix = config.ghProxyPrefix
        update { CustomFontConfig(downloadChannel = keepChannel, ghProxyPrefix = keepPrefix) }
        fontDir?.let { dir -> files.forEach { File(dir, it).delete() } }
    }

    /** 校验并复制到私有目录（使用新文件名避免 mmap 覆写崩溃）。 */
    suspend fun importValidatedFile(context: Context, sourceFile: File, originalName: String): CustomFontRecord =
        withContext(Dispatchers.IO) {
            initialize(context)
            require(isSupportedFontFileName(originalName)) { "仅支持 .ttf、.otf 或 .ttc 字体文件" }
            val size = sourceFile.length()
            require(size > 16L) { "字体文件为空或已损坏" }
            require(size <= CUSTOM_FONT_MAX_FILE_BYTES) {
                "单个字体文件不能超过 ${CUSTOM_FONT_MAX_FILE_BYTES / (1024 * 1024)} MB"
            }
            val header = ByteArray(12)
            val read = FileInputStream(sourceFile).use { it.read(header) }
            require(isSupportedFontHeader(header, read)) { "文件不是有效的 TrueType / OpenType 字体" }
            val dir = checkNotNull(fontDir) { "字体目录未初始化" }
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            val targetName = "font_${System.currentTimeMillis()}_$id.${fontExtensionOf(originalName)}"
            val targetFile = File(dir, targetName)
            try {
                sourceFile.copyTo(targetFile, overwrite = false)
                check(Typeface.createFromFile(targetFile) != null) { "系统无法解析该字体文件" }
                val record = CustomFontRecord(id, cleanFontDisplayName(originalName), targetName, targetFile.length(), System.currentTimeMillis())
                withContext(Dispatchers.Main.immediate) {
                    update { c -> c.copy(records = listOf(record) + c.records) }
                }
                record
            } catch (error: Throwable) {
                targetFile.delete()
                throw error
            }
        }

    suspend fun copyUriToTemp(context: Context, uri: Uri): Pair<File, String> = withContext(Dispatchers.IO) {
        val displayName = queryUriDisplayName(context, uri)
        val temp = File(context.cacheDir, "font_import_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { copyBounded(it, temp, CUSTOM_FONT_MAX_ARCHIVE_BYTES) }
                ?: error("无法读取所选文件")
            temp to displayName
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun queryUriDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "font.ttf"
    }

    private fun update(transform: (CustomFontConfig) -> CustomFontConfig) {
        val next = transform(config)
        if (next == config) return
        config = next
        revision += 1L
        persist(next)
    }

    private fun persist(value: CustomFontConfig) {
        preferences?.edit()?.putString(KEY_CONFIG, encodeCustomFontConfig(value))?.apply()
    }

    private const val KEY_CONFIG = "config_v1"
}

internal fun copyBounded(input: InputStream, target: File, maxBytes: Long): Long {
    var total = 0L
    val buffer = ByteArray(16 * 1024)
    FileOutputStream(target).use { output ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) error("文件超过大小上限（${maxBytes / (1024 * 1024)} MB）")
            output.write(buffer, 0, read)
        }
    }
    return total
}
