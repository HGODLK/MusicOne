package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
internal fun LocalLibraryToolbar(enabled: Boolean, onImport: () -> Unit, onManage: (Rect) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onManage(bounds) }, modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Icon(Icons.Default.Settings, "本地音乐库设置")
        }
        LibraryActionButton("导入音乐", onImport, icon = Icons.Default.Add, enabled = enabled)
    }
}
