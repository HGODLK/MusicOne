package com.musicone.source.subsonic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.SubsonicServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 服务器管理页：已保存的服务器点按即切换，“管理”模式下点按删除；下方添加新服务器。
 * 列表行、按钮、输入框复用宿主公共组件，宿主未提供时退回同结构的 Material 控件。
 */
internal class SubsonicLoginPage(
    private val auth: SubsonicAuthentication,
    private val components: () -> SourceComponentScope?,
) : SourceAuthenticationPage {
    @Composable override fun Content() {
        val scope = rememberCoroutineScope()
        val ui = remember { components() }
        var servers by remember { mutableStateOf(auth.servers()) }
        var currentKey by remember { mutableStateOf(auth.current()?.key) }
        var managing by remember { mutableStateOf(false) }
        var address by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        fun reload() { servers = auth.servers(); currentKey = auth.current()?.key; if (servers.isEmpty()) managing = false }

        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
            .navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 返回由宿主页头统一处理；卡片宽度与其他音源登录页一致，手机和平板共用。
            Spacer(Modifier.height(40.dp))
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Subsonic 服务器", style = MaterialTheme.typography.titleLarge)
                    Text("支持 Navidrome、Gonic、Airsonic、Ampache 等兼容 Subsonic 协议的服务器",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    AnimatedVisibility(servers.isNotEmpty(), enter = fadeIn(motion()) + expandVertically(motion()),
                        exit = fadeOut(motion()) + shrinkVertically(motion())) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PageGroup(ui, if (managing) "点按要删除的服务器" else "已保存的服务器") {
                                servers.forEach { server ->
                                    val current = server.key == currentKey
                                    ServerRow(ui, server.displayName, serverSubtitle(server, current, managing), enabled = !working) {
                                        message = null
                                        try {
                                            if (managing) auth.remove(server.key)
                                            else if (!current) { auth.switchTo(server.key); reload(); auth.loginCompleted() }
                                        } catch (error: Exception) { message = error.message ?: "操作失败，请重试" }
                                        reload()
                                    }
                                }
                            }
                            PageButton(ui, if (managing) "完成" else "管理服务器", primary = false, enabled = !working) { managing = !managing }
                        }
                    }

                    PageGroup(ui, if (servers.isEmpty()) "连接服务器" else "添加服务器") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PageField(ui, "服务器地址，例如 http://192.168.1.10:4533", address, !working) { address = it; message = null }
                            PageField(ui, "用户名", username, !working) { username = it; message = null }
                            OutlinedTextField(password, { password = it; message = null }, Modifier.fillMaxWidth(),
                                enabled = !working, label = { Text("密码") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    PageButton(ui, "连接", primary = true, enabled = !working && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
                        working = true; message = null
                        scope.launch {
                            try {
                                auth.connect(address, username, password)
                                password = ""; address = ""; username = ""
                                reload()
                                auth.loginCompleted()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { message = error.message ?: "连接失败，请检查地址和账号" }
                            finally { working = false }
                        }
                    }
                    Text("密码只用于生成登录令牌，仅当服务器不支持令牌登录时才会保存。局域网 http 地址可直接使用；暂不支持自签名 https 证书。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(110.dp))
        }
    }

    private fun serverSubtitle(server: SubsonicServer, current: Boolean, managing: Boolean): String = when {
        managing -> if (current) "当前使用 · 删除后需要重新登录" else "点按删除"
        server.expired -> "账号或密码已变更，请重新添加"
        current -> listOf("正在使用", serverLabel(server)).filter(String::isNotBlank).joinToString(" · ")
        else -> listOf("点按切换", serverLabel(server)).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun serverLabel(server: SubsonicServer): String =
        listOf(server.serverType.replaceFirstChar { it.uppercase() }, server.serverVersion).filter(String::isNotBlank).joinToString(" ")

    private fun <T> motion(): FiniteAnimationSpec<T> =
        if (auth.reduceMotion) snap() else tween(300, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))
}

@Composable
private fun PageGroup(ui: SourceComponentScope?, title: String, content: @Composable () -> Unit) {
    if (ui != null) ui.SettingsGroup(title) { content() }
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun ServerRow(ui: SourceComponentScope?, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    if (ui != null) ui.ActionRow(title, subtitle, enabled, onClick)
    else ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick))
}

@Composable
private fun PageButton(ui: SourceComponentScope?, title: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (ui != null) ui.ActionButton(title, enabled, primary, Modifier.fillMaxWidth(), onClick)
    else if (primary) Button(onClick, Modifier.fillMaxWidth(), enabled) { Text(title) }
    else OutlinedButton(onClick, Modifier.fillMaxWidth(), enabled) { Text(title) }
}

@Composable
private fun PageField(ui: SourceComponentScope?, label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    if (ui != null) ui.FormField(label, value, enabled, onChange)
    else OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, singleLine = true)
}
