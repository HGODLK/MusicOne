package com.musicone.source.webdav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.SourceAuthenticationPage
import com.musicone.source.sdk.SourceComponentScope
import com.musicone.source.webdav.internal.WebDavServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** WebDAV 服务器管理页：点按切换、单独删除；添加表单在已有服务器时默认收起。 */
internal class WebDavLoginPage(
    private val auth: WebDavAuthentication,
    private val components: () -> SourceComponentScope?,
) : SourceAuthenticationPage {

    @Composable
    override fun Content() {
        val ui = remember { components() }
        if (ui == null) {
            Text("当前版本不支持此页面，请更新 MusicOne。", Modifier.padding(24.dp))
            return
        }
        val reduceMotion = auth.reduceMotion
        CompositionLocalProvider(LocalWebDavMotion provides remember(reduceMotion) { WebDavMotion(reduceMotion) }) {
            ServerManager(ui)
        }
    }

    @Composable
    private fun ServerManager(ui: SourceComponentScope) {
        val scope = rememberCoroutineScope()
        val motion = LocalWebDavMotion.current
        val revision = auth.observedRevision
        val servers = remember(revision) { auth.servers() }
        val current = remember(revision) { auth.currentServer() }
        val form = remember { WebDavServerForm() }
        var formOpen by remember { mutableStateOf(servers.isEmpty()) }
        var working by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var deleting by remember { mutableStateOf<WebDavServer?>(null) }
        LaunchedEffect(servers.isEmpty()) { if (servers.isEmpty()) formOpen = true }
        // 从“登录已失效”进来时直接带出原服务器，只需补密码。
        LaunchedEffect(Unit) {
            if (current?.expired == true) {
                form.fill(current)
                formOpen = true
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AnimatedVisibility(
                        servers.isNotEmpty(),
                        enter = fadeIn(motion.spec(260)) + expandVertically(motion.spec(320)),
                        exit = fadeOut(motion.spec(180)) + shrinkVertically(motion.spec(260)),
                    ) {
                        ui.SettingsGroup("服务器") {
                            Column(Modifier.animateContentSize(motion.spec(260))) {
                                servers.forEach { server ->
                                    key(server.key) {
                                        ServerRow(server, current = server.key == current?.key, enabled = !working,
                                            onSelect = {
                                                message = null
                                                if (server.expired) {
                                                    form.fill(server)
                                                    formOpen = true
                                                } else if (server.key != current?.key) {
                                                    try {
                                                        auth.switchTo(server.key)
                                                        auth.loginCompleted()
                                                    } catch (error: Exception) {
                                                        message = error.message ?: "切换失败，请重试"
                                                    }
                                                }
                                            },
                                            onDelete = { deleting = server })
                                    }
                                }
                                FlowRow(
                                    Modifier.padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ui.ActionButton(if (formOpen) "收起" else "添加服务器", enabled = !working, primary = !formOpen) {
                                        if (!formOpen) form.clear()
                                        formOpen = !formOpen
                                        message = null
                                    }
                                    if (current != null) ui.ActionButton("断开连接", enabled = !working, primary = false) {
                                        working = true
                                        message = null
                                        scope.launch { try { auth.logout() } finally { working = false } }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        formOpen,
                        enter = fadeIn(motion.spec(260)) + expandVertically(motion.spec(320)),
                        exit = fadeOut(motion.spec(180)) + shrinkVertically(motion.spec(260)),
                    ) {
                        ui.SettingsGroup(if (form.reconnecting) "重新登录" else "连接服务器") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val edited = { message = null }
                                ui.FormField("服务器地址，如 http://192.168.1.10:5244/dav", form.address, !working) { form.address = it; edited() }
                                ui.FormField("用户名（匿名访问可留空）", form.username, !working) { form.username = it; edited() }
                                PasswordField(form.password, !working) { form.password = it; edited() }
                                ui.FormField("起始路径（可选，如 /音乐）", form.rootPath, !working) { form.rootPath = it; edited() }
                                ui.FormField("备注名称（可选）", form.displayName, !working) { form.displayName = it; edited() }
                                Spacer(Modifier.height(4.dp))
                                ui.ActionButton(
                                    if (working) "正在连接…" else "连接",
                                    enabled = !working && form.address.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    working = true
                                    message = null
                                    scope.launch {
                                        try {
                                            auth.connect(form.address, form.rootPath, form.username, form.password, form.displayName)
                                            form.clear()
                                            auth.loginCompleted()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            message = error.message ?: "连接失败，请检查地址和账号"
                                        } finally {
                                            working = false
                                        }
                                    }
                                }
                                Text("账号只保存在本机。用 OpenList／AList 挂载网盘时，建议开启 WebDAV 代理模式。",
                                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    var lastMessage by remember { mutableStateOf("") }
                    message?.let { SideEffect { lastMessage = it } }
                    AnimatedVisibility(
                        message != null,
                        enter = fadeIn(motion.spec(220)) + expandVertically(motion.spec(260)),
                        exit = fadeOut(motion.spec(160)) + shrinkVertically(motion.spec(220)),
                    ) { ui.InlineMessage(message ?: lastMessage) }
                }
            }
        }

        deleting?.let { server ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("删除「${server.displayName}」？") },
                text = { Text("将删除保存的地址和账号。已加入本地歌单的歌曲会保留，但需要重新连接才能播放。") },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        try {
                            auth.removeServer(server.key)
                        } catch (error: Exception) {
                            message = error.message ?: "删除失败，请重试"
                        }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            )
        }
    }
}

@Stable
private class WebDavServerForm {
    var address by mutableStateOf("")
    var rootPath by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var displayName by mutableStateOf("")
    var reconnecting by mutableStateOf(false)
        private set

    /** 密码不回填，避免在界面上露出已保存的凭据。 */
    fun fill(server: WebDavServer) {
        address = server.baseUrl
        rootPath = server.rootPath
        username = server.username
        password = ""
        displayName = server.displayName
        reconnecting = true
    }

    fun clear() {
        address = ""
        rootPath = ""
        username = ""
        password = ""
        displayName = ""
        reconnecting = false
    }
}

@Composable
private fun ServerRow(server: WebDavServer, current: Boolean, enabled: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
            .selectable(current, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(horizontal = 8.dp).size(24.dp), contentAlignment = Alignment.Center) {
            WebDavSwapIcon(if (current) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, if (current) "正在使用" else "未使用")
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(server.displayName, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (server.expired) "登录已失效，点按重新登录" else server.subtitleUrl,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (server.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Rounded.DeleteOutline, "删除${server.displayName}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 公共 FormField 组件暂不支持密码输入框，这里按同样的样式自己封装一个。 */
@Composable
private fun PasswordField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("密码（匿名访问可留空）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .4f)), RoundedCornerShape(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value, onChange,
                Modifier.weight(1f).padding(14.dp).semantics { contentDescription = "密码" },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            )
            IconButton(onClick = { visible = !visible }) {
                WebDavSwapIcon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "隐藏密码" else "显示密码")
            }
        }
    }
}
