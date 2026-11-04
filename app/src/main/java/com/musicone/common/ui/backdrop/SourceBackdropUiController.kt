package com.musicone

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

internal class SourceBackdropUiController(
    val visual: SourceBackdropVisual?,
    val hasCustomGlobal: Boolean,
    val playlistRevision: Int,
    val chooseGlobal: () -> Unit,
    val choosePlaylist: (String) -> Unit,
    val restoreGlobal: () -> Unit,
    val restorePlaylist: (String) -> Unit,
    val dialogContent: @Composable () -> Unit,
)

/** 由可选公开视觉服务管理背景选择和裁剪，根节点只持有通用控制器。 */
@Composable
internal fun rememberSourceBackdropUiController(source: MusicSource): SourceBackdropUiController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instance = com.musicone.sourceintegration.SourcePackageController.get(context).running(source.id)
    val visuals = instance?.entry?.visuals
    val route = com.musicone.source.sdk.SourceRoute()
    val visualFlow = remember(visuals) { visuals?.observe(route) }
    val published = visualFlow?.collectAsState()?.value

    var globalRevision by remember(source.id) { mutableIntStateOf(0) }
    var playlistRevision by remember(source.id) { mutableIntStateOf(0) }
    var pendingTarget by remember(source.id) { mutableStateOf<SourceBackdropTarget?>(null) }
    var cropRequest by remember(source.id) { mutableStateOf<SourceBackdropCropRequest?>(null) }
    val customGlobal = published?.backgroundUri
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        cropRequest = uri?.let { selected ->
            pendingTarget?.let { target -> SourceBackdropCropRequest(selected, target) }
        }
        pendingTarget = null
    }
    val visual = if (visuals == null) null else rememberSourceBackdropVisual(
        customBackgroundUri = customGlobal,
        customBackgroundRevision = (published?.revision ?: globalRevision.toLong()).toInt(),
        foregroundArgb = published?.foregroundArgb,
    )
    val choose: (SourceBackdropTarget) -> Unit = { target ->
        if (visuals?.supportsCustomBackground == true) {
        pendingTarget = target
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    fun targetRoute(target: SourceBackdropTarget) = when (target) {
        SourceBackdropTarget.Global -> com.musicone.source.sdk.SourceRoute()
        is SourceBackdropTarget.Playlist -> com.musicone.source.sdk.SourceRoute("playlist", mapOf("id" to target.playlistId))
    }
    val restore: (SourceBackdropTarget) -> Unit = { target ->
        scope.launch {
            try {
                checkNotNull(visuals).setCustomBackground(targetRoute(target), null)
                if (target is SourceBackdropTarget.Playlist) playlistRevision++ else globalRevision++
                Toast.makeText(context, if (target is SourceBackdropTarget.Playlist) "已恢复全局背景" else "已恢复默认背景", Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(context, error.message ?: "背景恢复失败", Toast.LENGTH_SHORT).show() }
        }
    }

    return SourceBackdropUiController(
        visual = visual,
        hasCustomGlobal = published?.customBackground == true,
        playlistRevision = playlistRevision + (published?.revision ?: 0).toInt(),
        chooseGlobal = { choose(SourceBackdropTarget.Global) },
        choosePlaylist = { choose(SourceBackdropTarget.Playlist(it)) },
        restoreGlobal = { restore(SourceBackdropTarget.Global) },
        restorePlaylist = { restore(SourceBackdropTarget.Playlist(it)) },
        dialogContent = {
            cropRequest?.let { request ->
                SourceBackdropCropDialog(
                    request = request,
                    onDismiss = { cropRequest = null },
                    onConfirm = { bitmap, selection ->
                        cropRequest = null
                        scope.launch {
                            val saved = SourceBackdropStore.saveCrop(
                                context.applicationContext,
                                request.target,
                                bitmap,
                                selection,
                                source.id,
                            )
                            if (saved) {
                                try {
                                    val uri = when (val target = request.target) {
                                        SourceBackdropTarget.Global -> SourceBackdropStore.currentUri(context, source.id)
                                        is SourceBackdropTarget.Playlist -> SourceBackdropStore.playlistUri(context, target.playlistId, source.id)
                                    }
                                    checkNotNull(visuals).setCustomBackground(targetRoute(request.target), checkNotNull(uri))
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: Exception) { Toast.makeText(context, error.message ?: "背景更新失败", Toast.LENGTH_SHORT).show(); return@launch }

                                when (request.target) {
                                    SourceBackdropTarget.Global -> globalRevision++
                                    is SourceBackdropTarget.Playlist -> playlistRevision++
                                }
                                Toast.makeText(context, "背景已更新", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "这张图片无法读取", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        },
    )
}
