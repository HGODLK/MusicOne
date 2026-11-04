package org.example.source

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*

class ExampleSource : SourceEntry {
    override val descriptor = SourceDescriptor(
        id = "org.example.main", name = "外部音源示例 main", kind = SourceKind.MAIN,
        versionCode = BuildConfig.VERSION_CODE.toLong(), versionName = BuildConfig.VERSION_NAME,
        capabilities = setOf(SourceCapability.HOME, SourceCapability.SEARCH, SourceCapability.PLAYLIST,
            SourceCapability.ARTIST, SourceCapability.ALBUM), useCustomPages = true,
        presentation = SourcePresentation(pageColorArgb = 4281559415L)
    )
    override val implementedCapabilities get() = descriptor.capabilities
    private val demo = ExampleCatalog(descriptor.id)
    private val navigation = ExampleRootNavigation(descriptor.id)
    override val pageState = navigation.pageState
    override val discovery: SourceDiscovery get() = demo
    override val search: SourceSearch get() = demo.search
    override val collections: SourceCollections get() = demo
    override val entities: SourceEntities get() = demo
    private lateinit var environment: SourceEnvironment
    private val initializationCount = mutableIntStateOf(0)
    private val userMarker = mutableIntStateOf(0)
    override fun initialize(environment: SourceEnvironment) {
        this.environment = environment
        // 故障开关仅用于验收：导入隔离验证通过，真实重启初始化时失败，触发宿主回退。
        check(environment.validationOnly || !BuildConfig.FAIL_INITIALIZATION) { "验收故障：真实音源初始化失败" }
        val preferences = environment.context.getSharedPreferences("example", Context.MODE_PRIVATE)
        initializationCount.intValue = preferences.getInt("initializationCount", 0)
        userMarker.intValue = preferences.getInt("userMarker", 0)
        if (!environment.validationOnly) {
            initializationCount.intValue++
            check(preferences.edit().putInt("initializationCount", initializationCount.intValue).commit())
        }
    }
    override fun close() { navigation.reset() }
    override fun onBack(): Boolean = navigation.onBack()
    @Composable override fun Content(route: SourceRoute) {
        // 专属页面故障开关用于验证宿主错误边界，不代表当前宿主已完成异常隔离。
        check(!BuildConfig.FAIL_CONTENT) { "验收故障：外部 Compose 专属页面失败" }
        val rootPage = navigation.rootPage(route)
        val root = navigation.root(rootPage)
        val active = route.arguments["active"] != "false"
        val bottomInset = route.arguments["bottomInsetDp"]?.toFloatOrNull()
            ?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        SideEffect { navigation.synchronize(route) }
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(4281559415L))) {
            val searchRequest = root.search.takeIf { active }
            if (searchRequest != null) {
                val pages = environment.host.contentUi?.pages
                if (pages != null) {
                    pages.Content(searchRequest, bottomInsetDp = bottomInset,
                        onClose = { navigation.closeSearch(rootPage, searchRequest) })
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = bottomInset.dp + 24.dp)) {
                        Text("当前宿主未提供公共搜索页面")
                        Button(onClick = { navigation.closeSearch(rootPage, searchRequest) }) { Text("返回示例页") }
                    }
                }
                return@MaterialTheme
            }
            if (root.screen == "public-ui") {
                ExamplePublicUiDemo(environment.host, demo, root.publicUiDemo, bottomInset,
                    onClose = { navigation.navigate(rootPage, "root") })
                return@MaterialTheme
            }
            // 根页让出宿主 68dp 页头；内部页由包提供返回，宿主页头已隐藏。
            val topInset = if (route.arguments["embedded"] == "true" && root.screen == "root") 68.dp else 0.dp
            Column(Modifier.verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = topInset + 24.dp, end = 24.dp, bottom = bottomInset.dp + 24.dp)) {
                Text(stringResource(R.string.source_title))
                Image(painterResource(R.drawable.source_badge), "主音源红色方形", Modifier.size(64.dp))
                Text("MAIN · " + (if (root.screen == "root") rootPage else root.screen) + " · v" + descriptor.versionCode)
                Text("实际初始化次数：" + initializationCount.intValue + "，用户标记：" + userMarker.intValue)
                Button(onClick = {
                    val next = userMarker.intValue + 1
                    val preferences = environment.context.getSharedPreferences("example", Context.MODE_PRIVATE)
                    check(preferences.edit().putInt("userMarker", next).commit())
                    userMarker.intValue = next
                }) { Text("保存用户标记 +1") }
                if (root.screen != "root") {
                    Button(onClick = { navigation.navigate(rootPage, "root") }) { Text("返回示例根页") }
                }
                Button(onClick = { navigation.navigate(rootPage, "detail") }) { Text("打开内部页面") }
                Button(onClick = { navigation.navigate(rootPage, "public-ui") }) { Text("公共页面与组件演示") }
                Button(onClick = { environment.host.exitSource() }) { Text("返回宿主") }
            }
        }
    }
}
