package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 子项（如分P）列表和默认勾选都由音源返回，宿主只管展示和透传，不解析具体平台编号。 */
@Composable
internal fun SupplementalImportSection(state: SupplementalState, model: SupplementalSourceModel) {
    val content = state.content ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SupplementalContentSummary(content)
        LibrarySettingsGroup {
            SupplementalChildSelection(state.children, state.selectedChildren, !state.busy,
                model::toggleChild, model::selectAllChildren, model::clearChildren)
            if (supplementalCanImport(model.entry, content, state.children.isNotEmpty())) SupplementalImportTarget(state, model)
            SupplementalImportActions(state.selectedChildren.size, state.children.isNotEmpty(), state.busy,
                canImport = supplementalCanImport(model.entry, content, state.children.isNotEmpty()),
                canPlay = model.canPlay && model.canConvert(content, state.children.isNotEmpty()),
                onImport = { model.importSelected(); Unit }, onPlay = { model.importSelected(play = true); Unit },
                showImport = supplementalCanImport(model.entry, content, state.children.isNotEmpty()),
                showPlay = model.canPlay && model.canConvert(content, state.children.isNotEmpty()))
        }
    }
}

@Composable
internal fun SupplementalImportTarget(state: SupplementalState, model: SupplementalSourceModel) {
    SupplementalImportDestination(state.destinations, state.targetId) { model.target(it.id, it.title) }
}
