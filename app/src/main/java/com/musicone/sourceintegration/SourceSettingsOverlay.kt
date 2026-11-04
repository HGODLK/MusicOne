package com.musicone.sourceintegration

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicone.SettingsHeader
import com.musicone.SettingsPlatformTheme
import com.musicone.MusicSource
import com.musicone.musicMotion
import com.musicone.settingsPageTransform
import com.musicone.source.sdk.SourceRoute

/** 外部登录和平台设置也沿用设置层级过渡，退出完成前保留原页面。 */
@Composable
internal fun SourceSettingsOverlay(host: SourceApplicationHost, destination: SourceDestination?) {
    val context = LocalContext.current
    val controller = remember(context) { SourcePackageController.get(context) }
    val installed by controller.installed.collectAsState()
    val route = destination?.takeIf { it.name in setOf("source-authentication", "source-settings") }
    var retained by remember { mutableStateOf<SourceDestination?>(null) }
    if (route != null) SideEffect { retained = route }
    AnimatedVisibility(route != null,
        enter = fadeIn(musicMotion(320)) + slideInHorizontally(musicMotion(360)) { it / 16 },
        exit = fadeOut(musicMotion(220)) + slideOutHorizontally(musicMotion(280)) { it / 16 },
    ) {
        val displayedRoute = route ?: retained ?: return@AnimatedVisibility
        AnimatedContent(displayedRoute, Modifier.fillMaxSize(), transitionSpec = {
            settingsPageTransform(true)
        }, label = "音源设置页面衔接") { displayed ->
            val id = displayed.arguments["sourceId"].orEmpty()
            val instance = remember(id, installed) { controller.running(id) }
            val interactive = route == displayed
            val source = instance?.entry?.descriptor?.id?.let(MusicSource::fromId) ?: MusicSource.LOCAL
            val neutral = remember(instance) { instance?.entry?.authentication?.currentAccount()?.account == null }
            val exit = { host.exitSource() }
            val back = {
                if (instance == null || !runCatching { instance.onBack() }.getOrDefault(false)) exit()
            }
            BackHandler(enabled = interactive, onBack = back)
            SettingsPlatformTheme(source, neutral) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SettingsHeader(instance?.entry?.descriptor?.name ?: "音源不可用", back)
                    }
                    if (instance == null) Text("音源不可用，请返回音源管理。", Modifier.padding(20.dp))
                    else SourceExternalSurface(instance, SourceRoute(), Modifier.weight(1f),
                        mode = if (displayed.name == "source-authentication") "authentication" else "settings",
                        onExit = exit, interactive = interactive)
                }
            }
            }
        }
    }
}
