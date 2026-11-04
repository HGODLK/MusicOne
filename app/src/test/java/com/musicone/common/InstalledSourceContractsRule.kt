package com.musicone

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.runtime.SourceManifest
import com.musicone.source.runtime.VerifiedSourceArchive
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourcePackageController
import org.junit.rules.ExternalResource
import java.io.File

/** 使用真实包清单建立宿主策略所需的能力夹具；不启动包、不发网络请求，也不模拟安装成功。 */
class InstalledSourceContractsRule : ExternalResource() {
    private val instanceField = SourcePackageController::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null

    override fun before() {
        previous = instanceField.get(null)
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val constructor = SourcePackageController::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val controller = constructor.newInstance(context)
        val instances = listOf("qq", "kugou", "netease", "bilibili").associate { name ->
            val relative = "source-packs/$name/src/main/assets/musicone-source.json"
            val file = listOf(File(relative), File("../$relative")).first { it.isFile }
            val manifest = SourceManifest.parse(file.readText())
            manifest.descriptor.id to instance(context, manifest, contractEntry(manifest.descriptor, name))
        }.toMutableMap()
        val custom = SourceDescriptor("org.example.source", "测试歌词音源", SourceKind.MAIN, 1, "1", setOf(SourceCapability.LYRICS))
        instances[custom.id] = instance(context, SourceManifest(custom, "fixture.Entry", SourceSdk.API_VERSION, 23,
            SourceSdk.KOTLIN_VERSION, SourceSdk.COMPOSE_VERSION), contractEntry(custom, "lyrics"))
        SourcePackageController::class.java.getDeclaredField("activeInstances").apply { isAccessible = true }.set(controller, instances)
        instanceField.set(null, controller)
    }

    override fun after() { instanceField.set(null, previous) }

    private fun instance(context: Context, manifest: SourceManifest, entry: SourceEntry): SourceInstance {
        val host = object : SourceHost {
            override fun play(tracks: List<SourceTrack>, startIndex: Int) = error("策略测试不能播放")
            override fun importTracks(tracks: List<SourceTrack>, destinationId: String) = error("策略测试不能导入")
            override fun exitSource() = Unit
            override fun reportError(message: String) = Unit
        }
        val lease = Class.forName("com.musicone.source.runtime.SourceHostLease").declaredConstructors
            .single { it.parameterCount == 3 }.apply { isAccessible = true }.newInstance(host, false, manifest.descriptor)
        return SourceInstance::class.java.declaredConstructors.single { it.parameterCount == 4 }
            .apply { isAccessible = true }.newInstance(VerifiedSourceArchive(File("fixture.mscm"), manifest, "fixture"), entry, context, lease) as SourceInstance
    }

    private fun contractEntry(value: SourceDescriptor, name: String): SourceEntry = object : SourceEntry {
        override val descriptor = value
        override val implementedCapabilities = value.capabilities
        override val mediaPlayback: SourceMediaPlayback? = if (SourceCapability.PLAYBACK in value.capabilities) media(name) else null
        override val lyricProvider: SourceLyricProvider? = if (SourceCapability.LYRICS in value.capabilities) object : SourceLyricProvider {
            override val parserVersion = 2
            override val refreshPlainLyricsForVerbatim = true
            override suspend fun lyrics(track: SourceTrackDetails): SourceLyrics? = error("策略测试不读取网络歌词")
            override fun timing(track: SourceTrackDetails) = SourceLyricTiming()
        } else null
        override fun initialize(environment: SourceEnvironment) = Unit
        override fun close() = Unit
        @Composable override fun Content(route: SourceRoute) = Unit
    }

    /** 宿主仅验证对 SDK 声明的执行；各包的真实协议策略仍由 source-packs 自己的测试验证。 */
    private fun media(name: String): SourceMediaPlayback = object : SourceMediaPlayback {
        override fun fallbackQualityIds(preferredId: String): List<String> {
            if (name == "bilibili") return when (preferredId) {
                "DOLBY" -> listOf("DOLBY", "HI_RES", "STANDARD")
                "STANDARD", "EXHIGH", "HIGHER" -> listOf("STANDARD")
                else -> listOf("HI_RES", "STANDARD")
            }
            val preferred = when {
                preferredId == "HIGHER" -> "EXHIGH"
                preferredId == "DOLBY" && name == "kugou" -> "HI_RES"
                preferredId == "DOLBY" && name == "netease" -> "LOSSLESS"
                else -> preferredId
            }
            val order = listOf("STANDARD", "EXHIGH", "LOSSLESS", "HI_RES", "DOLBY")
            return order.take(order.indexOf(preferred).coerceAtLeast(0) + 1)
                .filterNot { name == "qq" && preferred == "DOLBY" && it == "HI_RES" }.asReversed()
        }
        override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> = error("策略测试不能探测网络音质")
        override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = error("策略测试不能解析播放地址")
        override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = error("策略测试不能恢复播放")
        override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> = error("策略测试不能探测权限")
    }
}
