package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import org.json.JSONObject
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*

internal enum class PlaylistEditMode { Actions, Create, Rename, Delete, RefreshSubscription, StopSubscription }

/** 编辑窗口独立持有状态，写入前重新核对当前账号的目录归属。 */
internal class MainSourcePlaylistEditorState(private val preferences: PlatformPreferences, private val scope: CoroutineScope,
    private val library: MusicLibraryStore? = null, private val libraryContext: android.content.Context? = null,
    private val requestedSource: MusicSource? = null) {
    var open by mutableStateOf(false)
    var playlist by mutableStateOf<MusicPlaylist?>(null)
    var anchor by mutableStateOf(Rect.Zero)
        private set
    private var currentBounds: (() -> Rect)? = null
    // 原卡片即使透明仍持续测量，布局改变后浮层返回实际位置。
    val currentAnchor get() = currentBounds?.invoke() ?: anchor
    var artworkVersion: Long? = null
    var artworkLayers: List<ArtworkBlendSnapshot>? = null
        private set
    var completedAction by mutableStateOf<PlaylistEditMode?>(null)
        private set
    var mode by mutableStateOf(PlaylistEditMode.Actions)
    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var artworkUri by mutableStateOf<String?>(null)
    var remoteOrderOpen by mutableStateOf(false)
    var presentationId by mutableLongStateOf(0L)
        private set
    var createUnconfirmed by mutableStateOf(false)
        private set
    var presentationAccountId: String? = null
        private set
    private fun editing() = SourcePackageController.get(preferences.applicationContext).running(source.id)?.entry?.collectionEditing
    val editableFields get() = if (local) emptySet() else (editing() as? SourceCollectionDetailsEditing)?.editableFields.orEmpty()
    val supportsRemoteOrder get() = !local && playlist?.let {
        (editing() as? SourceCollectionRemoteOrder)?.supportsRemoteOrder(it.toSourceCollection(preferences.readSession(source).account?.userId))
    } == true
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var local by mutableStateOf(false)
        private set
    val subscribed get() = library?.state?.value?.subscriptions?.any { it.playlistId == playlist?.id } == true
    val source get() = playlist?.source ?: requestedSource ?: preferences.selectedSource()
    fun supports(action: PlaylistEditMode): Boolean {
        if (local) return true
        val operation = when (action) {
            PlaylistEditMode.Create -> SourceCollectionOperation.CREATE
            PlaylistEditMode.Rename -> SourceCollectionOperation.EDIT
            PlaylistEditMode.Delete -> SourceCollectionOperation.DELETE
            else -> return false
        }
        return operation in SourcePackageController.get(preferences.applicationContext).running(source.id)?.entry?.collectionEditing?.supportedOperations.orEmpty()
    }
    fun show(target: MusicPlaylist?, bounds: Rect, version: Long? = null,
        artwork: List<ArtworkBlendSnapshot>? = null, local: Boolean = target?.isManagedLibrary == true,
        currentBounds: (() -> Rect)? = null) {
        if (busy) return
        presentationId++
        createUnconfirmed = false
        completedAction = null
        playlist = target; anchor = bounds; artworkVersion = version; name = target?.title.orEmpty(); error = null
        description = target?.description.orEmpty(); artworkUri = null; remoteOrderOpen = false
        this.currentBounds = currentBounds
        artworkLayers = artwork
        this.local = local
        presentationAccountId = preferences.readSession(source).account?.userId
        mode = if (target == null) PlaylistEditMode.Create else PlaylistEditMode.Actions
        open = true
    }
    fun close() { if (!busy) open = false }
    fun releaseArtwork() { artworkLayers = null; currentBounds = null }
    fun back() {
        if (busy) return
        if (playlist != null && mode != PlaylistEditMode.Actions) mode = PlaylistEditMode.Actions else close()
    }
    fun submit(onChanged: (PlaylistEditMode, MusicPlaylist?) -> Unit, action: PlaylistEditMode = mode) {
        if (busy) return
        val target = playlist
        if (action == PlaylistEditMode.Create && createUnconfirmed) return
        if (!supports(action)) { error = "此音源不支持该歌单操作"; return }
        val title = name.trim()
        val details = SourceCollectionDetailsEdit(title,
            description.takeIf { SourceCollectionEditField.DESCRIPTION in editableFields &&
                (if (target == null) it.isNotEmpty() else it != target.description) },
            artworkUri.takeIf { SourceCollectionEditField.ARTWORK in editableFields })
        if ((action == PlaylistEditMode.Create || action == PlaylistEditMode.Rename) && title.isBlank()) { error = "请输入歌单名称"; return }
        val source = target?.source ?: requestedSource ?: preferences.selectedSource()
        val session = preferences.readSession(source)
        busy = true; error = null
        scope.launch {
            try {
                if (local) {
                    val store = requireNotNull(library)
                    when (action) {
                        PlaylistEditMode.Create -> store.createPlaylist(title)
                        PlaylistEditMode.Rename -> store.renamePlaylist(requireNotNull(target).id, title)
                        PlaylistEditMode.Delete -> store.deletePlaylist(requireNotNull(target).id)
                        PlaylistEditMode.RefreshSubscription -> refreshLibrarySubscription(requireNotNull(libraryContext), store, requireNotNull(target).id)
                        PlaylistEditMode.StopSubscription -> store.stopSubscription(requireNotNull(target).id)
                        PlaylistEditMode.Actions -> error("请选择操作")
                    }
                    completedAction = action
                    open = false
                    onChanged(action, target)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val entry = requireNotNull(SourcePackageController.get(preferences.applicationContext).running(source.id)?.entry) { "音源未启用" }
                    val account = requireNotNull(entry.authentication?.currentAccount()?.account) { "请先登录${source.label}" }
                    check(account.id == presentationAccountId) { "账号已变化，请重新打开歌单编辑" }
                    val editing = requireNotNull(entry.collectionEditing) { "此音源不支持编辑歌单" }
                    val owned = target?.let { selected ->
                        editing.writableCollections().firstOrNull { it.toMusicPlaylist().id == selected.id && !it.accountFavorites }
                            ?: error("只能修改当前账号自建的歌单")
                    }
                    when (action) {
                        PlaylistEditMode.Create -> if (editing is SourceCollectionDetailsEditing && editing.editableFields.isNotEmpty()) editing.createDetails(details) else editing.create(SourceCollectionEdit(title))
                        PlaylistEditMode.Rename -> if (editing is SourceCollectionDetailsEditing && editing.editableFields.isNotEmpty()) editing.editDetails(requireNotNull(owned), details) else editing.edit(requireNotNull(owned), SourceCollectionEdit(title))
                        PlaylistEditMode.Delete -> editing.delete(requireNotNull(owned))
                        else -> error("请选择操作")
                    }
                    check(entry.authentication?.currentAccount()?.account?.id == account.id) { "账号已变化，旧结果已取消" }
                }
                completedAction = action
                open = false
                if (preferences.readSession(source).account?.userId == session.account?.userId) {
                    onChanged(action, target)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.asUserMessage()
                if (action == PlaylistEditMode.Create && failure is SourceOperationException && failure.code == "COLLECTION_CREATE_UNCONFIRMED") {
                    createUnconfirmed = true
                    if (preferences.readSession(source).account?.userId == session.account?.userId) onChanged(action, target)
                }
            }
            finally { busy = false }
        }
    }
}
