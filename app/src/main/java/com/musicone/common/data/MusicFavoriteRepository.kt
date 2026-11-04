package com.musicone

import android.content.Context
import com.musicone.sourceintegration.*

/** 云端收藏只由包执行，宿主仍按旧账号命名空间保存离线收藏索引。 */
internal class MusicFavoriteRepository(context: Context) {
    private val application = context.applicationContext
    private val controller = SourcePackageController.get(context)
    private val knownIds = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    fun favoriteSources(): List<MusicSource> = (controller.available(com.musicone.source.sdk.SourceKind.MAIN) +
        controller.available(com.musicone.source.sdk.SourceKind.SUPPLEMENTAL)).filter { it.entry.favorites != null }
        .map { MusicSource.fromId(it.entry.descriptor.id) }
    fun knownFavoriteIds(source: MusicSource): Set<String> = knownIds[source.id].orEmpty()
    private fun entry(source: MusicSource) = controller.running(source.id)?.entry ?: error("${source.label}音源未启用")
    suspend fun likedTrackIds(source: MusicSource): Set<String> {
        val service = entry(source)
        val state = service.authentication?.currentAccount()
        val owner = state?.account?.id ?: return emptySet<String>().also { knownIds[source.id] = it }
        val rawIds = requireNotNull(requireNotNull(service.favorites) { "此音源不支持收藏" }.favoriteTrackIds()) { "此音源不支持读取收藏身份" }
        val ids = rawIds.mapTo(hashSetOf()) { sourceStorageId(source.id, owner, it) }
        check(service.authentication?.currentAccount()?.account?.id == owner) { "账号已变化，请刷新收藏" }
        MusicDiskCache.get(application).setFavorites("${source.name}:$owner", ids)
        knownIds[source.id] = ids
        return ids
    }
    suspend fun setLiked(track: MusicTrack, liked: Boolean) = setLiked(listOf(track), liked)
    suspend fun setLiked(tracks: List<MusicTrack>, liked: Boolean) {
        if (tracks.isEmpty()) return
        val source = tracks.first().source
        require(tracks.all { it.source == source }) { "不能批量修改不同音源的收藏" }
        val service = entry(source)
        val owner = service.authentication?.currentAccount()?.account?.id ?: error("请先登录${source.label}")
        requireNotNull(service.favorites) { "此音源不支持收藏" }.setTracksFavorite(tracks.distinctBy(MusicTrack::id).map { it.toSourceTrack(owner) }, liked)
        check(service.authentication?.currentAccount()?.account?.id == owner) { "账号已变化，旧收藏结果已取消" }
        MusicDiskCache.get(application).changeFavorites("${source.name}:$owner", tracks.map { it.id }, liked)
    }
}
