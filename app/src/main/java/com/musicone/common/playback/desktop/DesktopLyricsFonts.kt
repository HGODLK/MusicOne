package com.musicone

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DESKTOP_FONT_WEIGHT = 600

/** 按文字所属语言取字体，规则与应用歌词一致；没有 SemiBold 的语言由系统合成加粗。 */
@RequiresApi(31)
internal class DesktopLyricTypefaces private constructor(private val faces: Map<MusicOneScript, Typeface>) {
    val default: Typeface get() = faces[MusicOneScript.SC] ?: systemFace()

    fun styled(text: String): CharSequence {
        if (faces.isEmpty() || text.isEmpty()) return text
        val script = musicOneScriptOf(text)
        val result = SpannableString(text)
        var start = 0
        var current = scriptFor(text[0], script)
        fun flush(end: Int) {
            faces[current]?.let { result.setSpan(TypefaceSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        for (index in 1 until text.length) {
            val next = scriptFor(text[index], script)
            if (next == current) continue
            flush(index)
            start = index
            current = next
        }
        flush(text.length)
        return result
    }

    companion object {
        fun systemFace(): Typeface = Typeface.create(Typeface.DEFAULT, DESKTOP_FONT_WEIGHT, false)

        val system by lazy { DesktopLyricTypefaces(emptyMap()) }

        /** 字体文件较大，读取放在后台，读完再整体换上。 */
        suspend fun load(context: Context, mode: AppFontMode): DesktopLyricTypefaces = when (mode) {
            AppFontMode.SYSTEM -> system
            AppFontMode.SANS_SERIF -> load(context, mapOf(
                MusicOneScript.SC to R.font.sarasa_ui_sc_semibold, MusicOneScript.LATIN to R.font.sarasa_ui_sc_semibold,
                MusicOneScript.TC to R.font.sarasa_ui_tc_regular, MusicOneScript.JA to R.font.sarasa_ui_j_regular,
                MusicOneScript.KO to R.font.sarasa_ui_k_regular))
            AppFontMode.DEFAULT -> load(context, mapOf(
                MusicOneScript.SC to R.font.source_han_serif_sc_semibold, MusicOneScript.LATIN to R.font.source_serif_4_semibold,
                MusicOneScript.TC to R.font.source_han_serif_tc_regular, MusicOneScript.JA to R.font.source_han_serif_j_regular,
                MusicOneScript.KO to R.font.source_han_serif_k_regular))
        }

        private suspend fun load(context: Context, ids: Map<MusicOneScript, Int>): DesktopLyricTypefaces = withContext(Dispatchers.IO) {
            try {
                DesktopLyricTypefaces(ids.mapValues { (script, id) ->
                    val face = context.resources.getFont(id)
                    // 简体和西文文件本身就是 SemiBold，不再合成。
                    if (script == MusicOneScript.SC || script == MusicOneScript.LATIN) face
                    else Typeface.create(face, DESKTOP_FONT_WEIGHT, false)
                })
            } catch (_: android.content.res.Resources.NotFoundException) { system }
        }
    }
}
