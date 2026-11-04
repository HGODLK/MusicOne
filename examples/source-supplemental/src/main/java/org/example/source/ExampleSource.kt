package org.example.source

import android.content.Context
import androidx.compose.foundation.Image
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
        id = "org.example.supplemental", name = "外部音源示例 supplemental", kind = SourceKind.SUPPLEMENTAL,
        versionCode = BuildConfig.VERSION_CODE.toLong(), versionName = BuildConfig.VERSION_NAME,
        capabilities = setOf(SourceCapability.HOME), useCustomPages = true,
        presentation = SourcePresentation(pageColorArgb = 4281559415L)
    )
    override val implementedCapabilities = setOf(SourceCapability.HOME)
    private lateinit var environment: SourceEnvironment
    private val route = mutableStateOf("home")
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
    override fun close() { route.value = "home" }
    override fun onBack(): Boolean {
        if (route.value == "home") return false
        route.value = "home"
        return true
    }
    @Composable override fun Content(route: SourceRoute) {
        // 专属页面故障开关用于验证宿主错误边界，不代表当前宿主已完成异常隔离。
        check(!BuildConfig.FAIL_CONTENT) { "验收故障：外部 Compose 专属页面失败" }
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(4281559415L))) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.source_title))
                Image(painterResource(R.drawable.source_badge), "补充音源蓝色三角形", Modifier.size(64.dp))
                Text("SUPPLEMENTAL · " + this@ExampleSource.route.value + " · v" + descriptor.versionCode)
                Text("实际初始化次数：" + initializationCount.intValue + "，用户标记：" + userMarker.intValue)
                Button(onClick = {
                    val next = userMarker.intValue + 1
                    val preferences = environment.context.getSharedPreferences("example", Context.MODE_PRIVATE)
                    check(preferences.edit().putInt("userMarker", next).commit())
                    userMarker.intValue = next
                }) { Text("保存用户标记 +1") }
                Button(onClick = { this@ExampleSource.route.value = "detail" }) { Text("打开内部页面") }
                Button(onClick = { environment.host.exitSource() }) { Text("返回宿主") }
            }
        }
    }
}
