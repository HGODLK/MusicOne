package com.musicone.validation

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicone.MainActivity
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourcePackageController
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 只启动正常入口并读取真实 SDK；不登录、不改会话、不导入或写资料库，不执行音频播放。 */
@RunWith(AndroidJUnit4::class)
class SourceIntegrationSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ids = listOf("qq", "kugou", "netease", "bilibili").map { "com.musicone.source.$it" }
    private val failures = mutableListOf<String>()

    @Test fun initializedPackagesAndAuthenticatedLibrary() {
        val arguments = InstrumentationRegistry.getArguments()
        val report = JSONObject().put("schema", 1).put("timestamp", System.currentTimeMillis())
            .put("packageName", context.packageName).put("playbackExecuted", false)
        val providers = JSONArray()
        report.put("providers", providers)
        try {
            val provider = arguments.getString("providerId")
            require(provider == null || provider in ids) { "providerId必须是四包稳定ID之一" }
            val searchRequested = provider != null || arguments.containsKey("query")
            val query = arguments.getString("query") ?: "RADWIMPS"
            require(!searchRequested || query.isNotBlank()) { "搜索参数不能为空" }
            // instrumentation会重启目标进程，因此只通过一次正常Activity启动等待真实初始化。
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    val optimizedEntries = OptimizedSmokeEntryAccess.readWhenConfigured(context, arguments, ids)
                    val controller = if (optimizedEntries != null) null else withTimeout(60_000L) {
                        while (true) {
                            val current = SourcePackageController.current()
                            if (current != null && ids.all { id -> current.installed.value.any { it.id == id } }) {
                                return@withTimeout current
                            }
                            delay(200L)
                        }
                        @Suppress("UNREACHABLE_CODE") error("未完成初始化")
                    }
                    for (id in ids) {
                        val row = JSONObject().put("sourceId", id)
                        providers.put(row)
                        val entry = phase(row, "loaded") {
                            requireNotNull(optimizedEntries?.get(id) ?: controller?.running(id)?.entry) { "包没有运行实例" }.also {
                                check(it.descriptor.id == id && it.mediaPlayback != null)
                                row.put("versionCode", it.descriptor.versionCode)
                            }
                        } ?: continue
                        phase(row, "account") {
                            val state = requireNotNull(entry.authentication).currentAccount()
                            // 仅保留状态及字段存在性；不导出会话、ID、昵称、头像或会员原文。
                            row.put("accountState", JSONObject().put("status", state.status.name)
                                .put("revision", state.revision).put("accountPresent", state.account != null)
                                .put("accountIdPresent", !state.account?.id.isNullOrBlank()))
                            check(state.status == SourceSessionStatus.AUTHENTICATED && !state.account?.id.isNullOrBlank())
                        }
                        var collection: SourceCollection? = null
                        var remote: SourceRemoteCollection? = null
                        phase(row, "library") {
                            val request = SourceCursorRequest(limit = 5, policy = SourceFetchPolicy.REFRESH)
                            if (entry.descriptor.kind == SourceKind.MAIN) {
                                val service = requireNotNull(entry.collections)
                                check(SourceLibrarySection.CREATED_COLLECTIONS in service.librarySections)
                                val page = service.library(SourceLibrarySection.CREATED_COLLECTIONS, request)
                                collection = page.items.firstOrNull()
                                row.put("libraryCount", page.items.size).put("libraryHasNext", page.nextCursor != null)
                            } else {
                                val page = requireNotNull(entry.remoteCollections).collections(request)
                                remote = page.items.firstOrNull()
                                row.put("libraryCount", page.items.size).put("libraryHasNext", page.nextCursor != null)
                            }
                        }
                        if (!searchRequested || (provider != null && provider != id)) continue
                        var track: SourceTrackDetails? = null
                        val search = entry.search
                        if (search != null && SourceSearchKind.TRACK in search.kinds) {
                            phase(row, "search") {
                                val result = search.search(query, SourceSearchKind.TRACK,
                                    SourceCursorRequest(limit = 5, policy = SourceFetchPolicy.REFRESH))
                                row.put("searchCount", result.tracks.size).put("searchHasNext", result.nextCursor != null)
                                row.put("playableCount", result.tracks.count { it.access.playable })
                                    .put("purchaseCount", result.tracks.count { it.access.badge == TrackAccessBadge.PURCHASE })
                                    .put("membershipCount", result.tracks.count { it.access.badge == TrackAccessBadge.MEMBERSHIP })
                                // 首条结果可能为另行购买的专辑，优先选择不带权限角标的可播曲目。
                                track = result.tracks.firstOrNull { it.access.playable && it.access.badge == null }
                                    ?: result.tracks.firstOrNull { it.access.playable }
                            }
                        } else {
                            row.put("search", JSONObject().put("status", "NOT_DECLARED"))
                            phase(row, "libraryPlaybackCandidate") {
                                track = if (remote != null) {
                                    requireNotNull(entry.remoteCollections).contents(requireNotNull(remote), null,
                                        SourceCursorRequest(limit = 5, policy = SourceFetchPolicy.REFRESH))
                                        .items.firstNotNullOfOrNull { it.playableTrack?.takeIf { song -> song.access.playable } }
                                } else collection?.let { value -> requireNotNull(entry.collections)
                                    .tracks(value, SourceCursorRequest(limit = 5)).items.firstOrNull { it.access.playable } }
                                check(track != null)
                            }
                        }
                        if (track == null) {
                            row.put("playbackResolve", JSONObject().put("status", "NOT_VERIFIED_NO_PLAYABLE_CANDIDATE"))
                            continue
                        }
                        phase(row, "playbackResolve") {
                            val playback = requireNotNull(entry.mediaPlayback)
                            val song = playback.enrich(requireNotNull(track))
                            val resource = playback.resolve(SourcePlaybackRequest(song, playback.defaultQualityId,
                                automatic = true, purpose = SourcePlaybackPurpose.PREFETCH))
                            check(resource.address.cacheIdentity.isNotBlank())
                            check(resource.address.url.startsWith("https://") || resource.address.url.startsWith("http://") ||
                                resource.address.url.startsWith("data:"))
                            // 只报告结构与结果，不写媒体URL、请求头、缓存键或不透明payload。
                            row.put("resource", JSONObject().put("quality", resource.qualityId.takeIf { value ->
                                value in setOf("STANDARD", "HIGHER", "EXHIGH", "LOSSLESS", "HI_RES", "DOLBY") } ?: "OTHER")
                                .put("trial", resource.trial).put("verificationPending", resource.verificationPending)
                                .put("inlineManifest", resource.inlineManifest != null).put("segments", resource.segments.size)
                                .put("headers", resource.address.headers.size).put("alternatives", resource.address.alternatives.size))
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            failures += "startup"
            report.put("startup", failureSummary(failure))
        } finally {
            report.put("passed", failures.isEmpty()).put("failedStages", JSONArray(failures))
            val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
            val root = File(external, "source-integration-smoke").canonicalFile
            check(root.parentFile == external)
            root.mkdirs()
            val output = File(root, "latest.json").canonicalFile
            check(output.parentFile == root)
            output.writeText(report.toString(2), Charsets.UTF_8)
            instrumentation.sendStatus(0, Bundle().apply { putString("sourceIntegrationSmokeReport", output.absolutePath) })
        }
        assertTrue("音源集成烟测失败；请读取externalFilesDir/source-integration-smoke/latest.json脱敏报告", failures.isEmpty())
    }

    private suspend fun <T> phase(row: JSONObject, name: String, action: suspend () -> T): T? {
        repeat(2) { attempt ->
            try {
                return withTimeout(60_000L) { action() }.also {
                    row.put(name, JSONObject().put("status", "SUCCEEDED").put("accountRefreshRetry", attempt))
                }
            } catch (failure: Throwable) {
                // 正常启动会刷新账号；仅重做一次被版本隔离取消的只读请求，不吞父任务取消。
                if (failure is CancellationException && failure !is TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (attempt == 0 && failure.message == "音源账号已变化") { delay(350L); return@repeat }
                }
                failures += "${row.optString("sourceId")}:$name"
                row.put(name, failureSummary(failure))
                return null
            }
        }
        return null
    }

    private fun failureSummary(failure: Throwable): JSONObject {
        val safeCodes = setOf("AUTH_REQUIRED", "AUTH_EXPIRED", "SECURITY_VERIFICATION_REQUIRED", "REQUEST_REJECTED",
            "RATE_LIMITED", "TRACK_UNAVAILABLE", "PLAYBACK_RETRY_REQUIRED", "SOURCE_REQUEST_FAILED")
        return JSONObject().put("status", "FAILED").put("exceptionType", failure.javaClass.simpleName)
            .put("code", (failure as? SourceOperationException)?.code?.takeIf { it in safeCodes } ?: "UNSPECIFIED")
            // 仅记录代码位置，不输出可能包含账号、请求参数或媒体地址的异常消息。
            .put("frames", JSONArray(failure.stackTrace.filter { it.className.startsWith("com.musicone.") }
                .take(8).map { "${it.className}.${it.methodName}:${it.lineNumber}" }))
    }
}
