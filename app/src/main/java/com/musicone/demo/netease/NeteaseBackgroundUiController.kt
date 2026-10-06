package com.musicone.demo

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

internal class NeteaseBackgroundUiController(
    val visual: NeteaseProfileVisual,
    val hasCustomGlobal: Boolean,
    val playlistRevision: Int,
    val chooseGlobal: () -> Unit,
    val choosePlaylist: (String) -> Unit,
    val restoreGlobal: () -> Unit,
    val restorePlaylist: (String) -> Unit,
    val dialogContent: @Composable () -> Unit,
)

/** 集中管理网易云背景选择、裁剪与保存，避免平台专属状态进入应用根节点。 */
@Composable
internal fun rememberNeteaseBackgroundUiController(): NeteaseBackgroundUiController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var globalRevision by remember { mutableIntStateOf(0) }
    var playlistRevision by remember { mutableIntStateOf(0) }
    var pendingTarget by remember { mutableStateOf<NeteaseBackgroundTarget?>(null) }
    var cropRequest by remember { mutableStateOf<NeteaseBackgroundCropRequest?>(null) }
    val customGlobal = remember(globalRevision) {
        NeteaseProfileBackgroundStore.currentUri(context)
    }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        cropRequest = uri?.let { selected ->
            pendingTarget?.let { target -> NeteaseBackgroundCropRequest(selected, target) }
        }
        pendingTarget = null
    }
    val visual = rememberNeteaseProfileVisual(
        customBackgroundUri = customGlobal,
        customBackgroundRevision = globalRevision,
    )
    val choose: (NeteaseBackgroundTarget) -> Unit = { target ->
        pendingTarget = target
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val restore: (NeteaseBackgroundTarget) -> Unit = { target ->
        if (NeteaseProfileBackgroundStore.clear(context.applicationContext, target)) {
            when (target) {
                NeteaseBackgroundTarget.Global -> globalRevision++
                is NeteaseBackgroundTarget.Playlist -> playlistRevision++
            }
            val message = if (target == NeteaseBackgroundTarget.Global) "已恢复默认背景" else "已恢复全局背景"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    return NeteaseBackgroundUiController(
        visual = visual,
        hasCustomGlobal = customGlobal != null,
        playlistRevision = playlistRevision,
        chooseGlobal = { choose(NeteaseBackgroundTarget.Global) },
        choosePlaylist = { choose(NeteaseBackgroundTarget.Playlist(it)) },
        restoreGlobal = { restore(NeteaseBackgroundTarget.Global) },
        restorePlaylist = { restore(NeteaseBackgroundTarget.Playlist(it)) },
        dialogContent = {
            cropRequest?.let { request ->
                NeteaseBackgroundCropDialog(
                    request = request,
                    onDismiss = { cropRequest = null },
                    onConfirm = { bitmap, selection ->
                        cropRequest = null
                        scope.launch {
                            val saved = NeteaseProfileBackgroundStore.saveCrop(
                                context.applicationContext,
                                request.target,
                                bitmap,
                                selection,
                            )
                            if (saved) {
                                when (request.target) {
                                    NeteaseBackgroundTarget.Global -> globalRevision++
                                    is NeteaseBackgroundTarget.Playlist -> playlistRevision++
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
