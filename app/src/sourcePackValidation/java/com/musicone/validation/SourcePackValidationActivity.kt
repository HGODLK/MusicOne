package com.musicone.validation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.runtime.SourceRepository
import com.musicone.source.sdk.SourceHost
import com.musicone.source.sdk.SourceTrack
import java.io.File

/** 仅实验变体提供，用于在迁移业务前验证外部加载边界。 */
class SourcePackValidationActivity : ComponentActivity(), SourceHost {
    private lateinit var repository: SourceRepository
    private var revision by mutableStateOf(0)
    private var selected by mutableStateOf<SourceInstance?>(null)
    private var message by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SourceRepository(this, this)
        runCatching {
            repository.startAfterProcessRestart()
            intent.getStringExtra("import_path")?.let { path ->
                val file = File(path).canonicalFile
                val allowed = requireNotNull(getExternalFilesDir(null)).canonicalFile
                require(file.toPath().startsWith(allowed.toPath())) { "验证文件必须位于实验应用外部文件目录" }
                message = "导入结果：${repository.importPackage(file, intent.getBooleanExtra("allow_downgrade", false))}；重启进程后生效"
            }
            intent.getStringExtra("remove_id")?.let { repository.remove(it); message = "已移除 $it" }
            intent.getStringExtra("rollback_id")?.let { repository.requestRollback(it); message = "回退待重启生效" }
            selected = intent.getStringExtra("open_id")?.let(repository::running)
        }.onFailure { message = "检查失败：${it.message ?: it.javaClass.simpleName}" }
        setContent {
            MaterialTheme {
                BackHandler {
                    val current = selected
                    if (current == null) finish()
                    else if (!current.onBack()) selected = null
                }
                val current = selected
                if (current != null) current.Content()
                else Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("音源外部加载验证", style = MaterialTheme.typography.headlineSmall)
                    Text(message)
                    // 状态变化后重新读取持久化注册表，不将实例状态放进应用主页面。
                    revision
                    repository.installed().forEach { source ->
                        Text("${source.id}\n生效 ${source.activeVersion} · 待生效 ${source.pendingVersion} · 回退 ${source.rollbackVersion} · 已删除 ${source.removed}")
                        source.error?.let { Text("加载错误：$it") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repository.running(source.id)?.let { instance ->
                                Button(onClick = { selected = instance }) { Text("打开外部页面") }
                            }
                            if (!source.removed) Button(onClick = {
                                repository.remove(source.id)
                                message = "已立即停用 ${source.id}，保留数据和签名身份"
                                revision++
                            }) { Text("移除") }
                            if (source.rollbackVersion != null && !source.removed) Button(onClick = {
                                repository.requestRollback(source.id)
                                message = "回退待重启生效"
                                revision++
                            }) { Text("回退") }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.close()
        super.onDestroy()
    }

    override fun play(tracks: List<SourceTrack>, startIndex: Int) {
        message = "收到公开 SDK 播放请求：${tracks.size} 项，起点 $startIndex"
    }
    override fun importTracks(tracks: List<SourceTrack>, destinationId: String) {
        message = "收到公开 SDK 导入请求：${tracks.size} 项"
    }
    override fun exitSource() { selected = null }
    override fun reportError(message: String) { this.message = message }
}
