package com.musicone.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** 只在更新弹窗挂载期间订阅当前音源，复用平台主题与玻璃表面。 */
@Composable
internal fun AppUpdatePrompt(
    release: MusicOneRelease,
    installedVersion: String,
    backdropLayer: GraphicsLayer,
    bounds: Rect,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onGithub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val platformModel: PlatformSettingsViewModel = viewModel()
    val platform by platformModel.state.collectAsStateWithLifecycle()
    PlatformMusicTheme(platform.selectedSource, neutral = !platform.sourceSelected) {
        MusicOneBackdropGlass(
            backdropLayer = backdropLayer,
            backdropBounds = bounds,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .then(modifier)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(28.dp),
            blurRadius = 24.dp,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .78f),
            fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .40f)),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 22.dp, vertical = 20.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = "发现新版本 ${release.version.label}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "当前版本 ${installedVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                // 更新说明正文区域
                if (release.body.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = release.body.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp,
                        )
                    }
                } else {
                    Text(
                        text = "新版本已就绪，点击下方按钮即可获取最新安装包。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(18.dp))

                // 操作按钮
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("稍后")
                    }
                    TextButton(onClick = onGithub) {
                        Text("GitHub")
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onDownload()
                        },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("获取下载")
                    }
                }
            }
        }
    }
}
