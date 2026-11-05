package com.musicone

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** 只显示服务实际返回的思考，沿用执行步骤的折叠入口与动效。 */
@Composable
internal fun AiReasoningSection(reasoning: String, thinking: Boolean, messageId: String) {
    if (reasoning.isBlank() && !thinking) return
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * .5f
    Column(Modifier.fillMaxWidth()) {
        AiDisclosureRow(if (thinking) "正在思考" else "思考过程", expanded,
            busy = thinking, onClick = { expanded = !expanded })
        AnimatedVisibility(expanded,
            enter = expandVertically(musicMotion(280)) + fadeIn(musicMotion(220)),
            exit = shrinkVertically(musicMotion(240)) + fadeOut(musicMotion(180))) {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
                .verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
                AiMarkdown(reasoning, small = true, streaming = thinking)
            }
        }
    }
}
