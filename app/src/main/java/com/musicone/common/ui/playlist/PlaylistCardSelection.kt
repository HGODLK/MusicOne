package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 卡片在网格里的行列，用来按离起点的距离错开勾选圈的出现时间。 */
internal data class PlaylistGridSlot(val row: Int, val column: Int)

/** 把各分区的卡片按列数排成行列；分区标题各占一行，行号跨分区连续。 */
internal fun playlistGridSlots(sections: List<List<String>>, columns: Int): Map<String, PlaylistGridSlot> {
    val width = columns.coerceAtLeast(1)
    val slots = HashMap<String, PlaylistGridSlot>()
    var row = 0
    sections.filter { it.isNotEmpty() }.forEach { ids ->
        ids.forEachIndexed { index, id -> slots[id] = PlaylistGridSlot(row + index / width, index % width) }
        row += (ids.size + width - 1) / width + 1
    }
    return slots
}

/** 每远一圈晚 25ms 出现，最远一张也在 250ms 内出完。 */
internal fun playlistSelectionRevealDelay(slot: PlaylistGridSlot?, origin: PlaylistGridSlot?): Int {
    if (slot == null || origin == null) return 0
    val ring = maxOf(abs(slot.row - origin.row), abs(slot.column - origin.column))
    return (ring * 25).coerceAtMost(250)
}

/** 歌单卡片多选：从卡片编辑面板或标题旁的入口进入，点卡片切换勾选；批量操作执行期间锁定。 */
@Stable
internal class PlaylistCardSelection {
    var active by mutableStateOf(false)
        private set
    var ids by mutableStateOf(emptySet<String>())
        private set

    /** 批量操作进行中的进度文字；非空时不能改选择，也不能退出。 */
    var busyText by mutableStateOf<String?>(null)
    val busy get() = busyText != null

    /** 已经删掉、正等列表刷新的卡片：先淡出缩小，列表更新后其余卡片再补位。 */
    var leaving by mutableStateOf(emptySet<String>())
        private set

    /** 页面每次组合写入的卡片行列，进入多选时据此确定勾选圈从哪里开始出现。 */
    var slots: Map<String, PlaylistGridSlot> = emptyMap()
    var origin by mutableStateOf<PlaylistGridSlot?>(null)
        private set

    /**
     * id 为进入时直接勾上的卡片；originId 为勾选圈开始出现的卡片，默认同 id。
     * 从标题旁进入时两者都为空，从第一张卡片开始按阅读顺序出现。
     */
    fun begin(id: String?, originId: String? = id) {
        if (busy) return
        origin = originId?.let(slots::get) ?: slots.values.minWithOrNull(compareBy({ it.row }, { it.column }))
        active = true
        ids = setOfNotNull(id)
    }

    fun revealDelay(id: String): Int = playlistSelectionRevealDelay(slots[id], origin)

    fun toggle(id: String) { if (!busy) ids = if (id in ids) ids - id else ids + id }

    /** 分区或整页全选；这些已经全部选中时改为取消。 */
    fun toggleAll(all: Collection<String>) {
        if (busy || all.isEmpty()) return
        ids = if (ids.containsAll(all)) ids - all.toSet() else ids + all
    }

    /** 列表变化后丢掉已经不存在的勾选；已离开列表的卡片也不再记着。 */
    fun retain(valid: Collection<String>) {
        val present = valid.toSet()
        if (leaving.any { it !in present }) leaving = leaving.intersect(present)
        if (busy) return
        if (ids.any { it !in present }) ids = ids.intersect(present)
    }

    /** 批量操作部分失败时只保留失败项，方便直接重试。 */
    fun keepOnly(remaining: Collection<String>) { ids = remaining.toSet() }

    fun markLeaving(removed: Collection<String>) { if (removed.isNotEmpty()) leaving = leaving + removed }

    /** 列表迟迟没有刷新时恢复显示，避免卡片一直隐身。 */
    fun clearLeaving() { leaving = emptySet() }

    fun close() {
        if (busy) return
        active = false
        ids = emptySet()
    }
}

private val SelectableCardShape = RoundedCornerShape(20.dp)

/** 批量删除后卡片淡出缩小的时长；调用方等它播完再更新列表，其余卡片随后补位。 */
internal const val PlaylistCardLeaveMillis = 220L

/**
 * 多选时盖在卡片上的一层：整页卡片一起轻微缩小，选中的卡片盖一层主题色并描边；不可选的卡片变淡且不响应。
 * 勾选圈按离起点的距离依次出现，退出时一起淡出。content 拿到卡片上播放按钮等提示的可见度，随多选淡出。
 * 列表项的位置动画修饰符要放在这一层上，卡片本身只保留尺寸。
 */
@Composable
internal fun SelectablePlaylistCard(selection: PlaylistCardSelection, id: String, selectable: Boolean,
    modifier: Modifier = Modifier, content: @Composable (hintVisibility: () -> Float) -> Unit) {
    val active = selection.active
    val selected = active && id in selection.ids
    val mode by animateFloatAsState(if (active) 1f else 0f, musicMotion(280), label = "歌单卡片多选状态")
    val markTarget = if (active && selectable) 1f else 0f
    val mark by animateFloatAsState(markTarget,
        if (markTarget > 0f) musicMotion<Float>(220, selection.revealDelay(id)) else musicMotion<Float>(160),
        label = "歌单卡片勾选圈")
    val check by animateFloatAsState(if (selected) 1f else 0f, musicMotion(200), label = "歌单卡片选中")
    val exit by animateFloatAsState(if (id in selection.leaving) 1f else 0f, musicMotion(PlaylistCardLeaveMillis.toInt()),
        label = "歌单卡片删除")
    val colors = MaterialTheme.colorScheme
    Box(modifier.graphicsLayer {
        val scale = (1f - .03f * mode) * (1f - .1f * exit)
        scaleX = scale; scaleY = scale
        alpha = (if (selectable) 1f else 1f - .62f * mode) * (1f - exit)
    }) {
        content { 1f - mode }
        if (check > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = check }
            .background(colors.primary.copy(alpha = .26f), SelectableCardShape)
            .border(2.5.dp, colors.primary, SelectableCardShape))
        if (active) {
            Box(Modifier.matchParentSize().then(
                if (selectable) Modifier.toggleable(selected, enabled = !selection.busy, role = Role.Checkbox) { selection.toggle(id) }
                // 不可选的卡片也要吞掉点击，多选期间不能误开歌单。
                else Modifier.pointerInput(Unit) { detectTapGestures { } }))
        }
        if (mark > 0f) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp)
                .graphicsLayer { alpha = mark; scaleX = .7f + .3f * mark; scaleY = .7f + .3f * mark }
                .background(lerp(Color.Black.copy(alpha = .28f), colors.primary, check), CircleShape)
                .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                if (check > 0f) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp).graphicsLayer {
                    alpha = check; scaleX = .6f + .4f * check; scaleY = .6f + .4f * check
                }, tint = colors.onPrimary)
            }
        }
    }
}

/**
 * 分区标题旁的入口：平时是“多选”图标（onBegin 为空时不显示）加 trailing（如新建歌单）；
 * 多选时整组原位淡出，本分区的“全选”在同一槽位右对齐淡入。两者叠放、槽位不拆，标题行和文字都不发生位移。
 */
@Composable
internal fun PlaylistSectionSelectAll(selection: PlaylistCardSelection, ids: List<String>, onBegin: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null) {
    val selecting = selection.active && ids.isNotEmpty()
    val progress by animateFloatAsState(if (selection.active) 1f else 0f, musicMotion(220), label = "分区多选入口")
    Box(contentAlignment = Alignment.CenterEnd) {
        // 常态按钮多选期间保留占位，只淡出并截下触摸，避免槽位变窄带着“全选”挪动。
        Row(Modifier.graphicsLayer { alpha = 1f - progress }.blockSectionTouches(selection.active),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBegin != null || selection.active || progress > 0f) IconButton(onClick = { onBegin?.invoke() },
                enabled = onBegin != null) { Icon(Icons.Rounded.Checklist, "多选歌单") }
            trailing?.invoke()
        }
        // 退出多选时文字随同一进度淡出，不提前消失。
        if (selection.active || progress > 0f) Box(Modifier.graphicsLayer { alpha = if (ids.isEmpty()) 0f else progress }) {
            SelectionTextAction(if (selection.ids.containsAll(ids)) "取消全选" else "全选", selecting && !selection.busy) {
                selection.toggleAll(ids)
            }
        }
    }
}

private fun Modifier.blockSectionTouches(blocked: Boolean): Modifier = if (!blocked) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/**
 * 把多选操作条交给底栏显示：多选期间底栏的导航胶囊沉下去，玻璃操作条在同一位置升起来。
 * 返回键先退出多选。操作条本身由 BottomPlayerBar 绘制，和导航胶囊采样同一层背景。
 */
@Composable
internal fun PlaylistCardSelectionBar(selection: PlaylistCardSelection, allIds: List<String>, actionLabel: String,
    onAction: () -> Unit) {
    val host = LocalPlaylistSelectionBarHost.current
    val request = remember(selection) { PlaylistSelectionBarRequest(selection) }
    SideEffect {
        request.allIds = allIds
        request.actionLabel = actionLabel
        request.onAction = onAction
        if (selection.active) { if (host.request !== request) host.request = request }
        else if (host.request === request) host.request = null
    }
    DisposableEffect(host, request) { onDispose { if (host.request === request) host.request = null } }
    BackHandler(selection.active && !selection.busy) { selection.close() }
}

/** 批量操作都先经过确认框；云端删除不可恢复，说明里要写清楚影响范围。 */
internal class PlaylistBatchConfirm(val title: String, val detail: String, val confirmLabel: String, val run: () -> Unit)

/** 确认框里列出前三个歌单名，其余只给数量。 */
internal fun playlistBatchNames(playlists: List<MusicPlaylist>): String =
    playlists.take(3).joinToString("、") { "「${it.title}」" } + if (playlists.size > 3) " 等 ${playlists.size} 个" else ""
