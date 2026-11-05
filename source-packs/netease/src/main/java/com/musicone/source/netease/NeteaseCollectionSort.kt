package com.musicone.source.netease

import android.content.Context
import com.musicone.source.sdk.SourceCollection
import com.musicone.source.sdk.SourceCollectionSortOption
import java.text.Collator
import java.util.Locale

private const val DEFAULT_SORT = "ADDED_DESC"
private val SORT_OPTIONS = listOf(SourceCollectionSortOption(DEFAULT_SORT, "加入时间：新到旧"),
    SourceCollectionSortOption("ADDED_ASC", "加入时间：旧到新"), SourceCollectionSortOption("NAME", "歌曲名称：升序"))

/** 只保存账号下的真实歌单（数字编号）；每日推荐、已播歌曲等临时歌单仍由宿主保存，否则键带日期会每天重置。 */
internal fun neteaseCollectionSortKey(remoteId: String, collectionAccount: String?, account: String?): String? {
    if (account.isNullOrBlank() || collectionAccount?.let { it != account } == true) return null
    if (remoteId.toLongOrNull()?.let { it > 0 } != true) return null
    return "$account:$remoteId"
}

/** 首页卡片由包自己播放，读不到宿主保存的排序，所以网易云歌单的排序存在包内。 */
internal class NeteaseCollectionSort(context: Context) {
    private val preferences = context.getSharedPreferences("netease_collection_sort", Context.MODE_PRIVATE)
    private fun key(collection: SourceCollection, account: String?) =
        neteaseCollectionSortKey(collection.identity.remoteId, collection.identity.accountId, account)

    fun options(collection: SourceCollection, account: String?): List<SourceCollectionSortOption> =
        if (key(collection, account) == null) emptyList() else SORT_OPTIONS

    fun selected(collection: SourceCollection, account: String?): String? = key(collection, account)?.let { key ->
        preferences.getString(key, null)?.takeIf { value -> SORT_OPTIONS.any { it.id == value } } ?: DEFAULT_SORT
    }

    fun select(collection: SourceCollection, account: String?, optionId: String) {
        require(SORT_OPTIONS.any { it.id == optionId }) { "不支持此歌单排序" }
        val key = requireNotNull(key(collection, account)) { "此歌单不支持账号排序" }
        check(preferences.edit().putString(key, optionId).commit())
    }
}

/** 和公共详情页同一套规则，默认保持接口顺序。 */
internal fun <T> sortedNeteaseCollection(items: List<T>, sortId: String?, title: (T) -> String): List<T> = when (sortId) {
    "ADDED_ASC" -> items.reversed()
    "NAME" -> {
        val collator = Collator.getInstance(Locale.CHINA)
        items.sortedWith { a, b -> collator.compare(title(a), title(b)) }
    }
    else -> items
}
