package com.musicone.source.qq

import com.musicone.source.qq.internal.*

import androidx.compose.animation.*
import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** 三种登录方式共用现有卡片，手机和平板保持同一宽度约束。 */
@Composable
internal fun QqLoginScreen(state: QqLoginUiState, actions: QqLoginActions, reduceMotion: Boolean, expiryNote: String? = null) {
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
        .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 返回按钮由宿主页头统一提供，这里顶部留白对齐卡片位置
        Spacer(Modifier.height(40.dp))
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // 上次掉登录的原因，便于排查；重新登录成功后清除。
                expiryNote?.let {
                    Text("上次登录失效：$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
                QqLoginMethodSelector(state.loginMethod, actions.onSelectLoginMethod, enabled = !state.working)
                AnimatedContent(state, contentKey = { it.loginMethod }, transitionSpec = {
                    ((fadeIn(qqLoginMotion(reduceMotion, 320)) + slideInHorizontally(qqLoginMotion(reduceMotion, 360)) { it / 16 }) togetherWith
                        (fadeOut(qqLoginMotion(reduceMotion, 220)) + slideOutHorizontally(qqLoginMotion(reduceMotion, 280)) { -it / 16 })).using(SizeTransform(sizeAnimationSpec = { _, _ -> qqLoginMotion(reduceMotion, 300) }))
                }, label = "QQ登录方式衔接") { displayed ->
                    if (displayed.loginMethod == PlatformLoginMethod.SMS) {
                        QqSmsLoginContent(displayed, actions)
                    } else {
                        QqQrLoginContent(displayed, actions.onStartQrLogin, reduceMotion)
                    }
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}
