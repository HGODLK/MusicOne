package com.musicone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.musicone.source.sdk.SourceCollectionEditField

/** 表单复用原编辑窗口；仅在音源声明支持时展示简介和图片选择。 */
@Composable
internal fun MainSourceCollectionForm(editor: MainSourcePlaylistEditorState) {
    OutlinedTextField(editor.name, { editor.name = it }, enabled = !editor.busy,
        singleLine = true, label = { Text("歌单名称") }, modifier = Modifier.fillMaxWidth())
    val fields = editor.editableFields
    if (SourceCollectionEditField.DESCRIPTION in fields) {
        OutlinedTextField(editor.description, { editor.description = it }, enabled = !editor.busy,
            label = { Text("简介") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
    }
    if (SourceCollectionEditField.ARTWORK in fields) {
        var pickerPresentation by remember { mutableLongStateOf(-1L) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (editor.open && !editor.busy && pickerPresentation == editor.presentationId && uri != null) editor.artworkUri = uri.toString()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickerPresentation = editor.presentationId; picker.launch("image/*") }, enabled = !editor.busy) {
                Text(if (editor.artworkUri == null) "选择封面" else "重新选择封面")
            }
            if (editor.artworkUri != null) TextButton(onClick = { editor.artworkUri = null }, enabled = !editor.busy) { Text("取消选图") }
        }
        editor.artworkUri?.let { RemoteArtwork(it, 0xFFE6B8C8, 0xFF98677D, "封", Modifier.size(96.dp), 28.sp, RoundedCornerShape(16.dp)) }
    }
}
