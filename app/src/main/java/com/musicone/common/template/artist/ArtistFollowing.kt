package com.musicone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourcePackageController
import kotlinx.coroutines.*

@Composable
internal fun ArtistFollowButton(artist: MainSourceArtist) {
    val context = LocalContext.current
    val entry = SourcePackageController.get(context).running(artist.source.id)?.entry ?: return
    val service = (entry.entities as? SourceEntityFollowing)?.takeIf { it.supportsFollowing } ?: return
    val account = entry.authentication?.currentAccount()?.account?.id
    val reference = artist.reference ?: SourceEntityRef(artist.id, artist.name, "artist")
    val scope = rememberCoroutineScope()
    var followed by remember(service, account, reference.id) { mutableStateOf<Boolean?>(null) }
    var busy by remember(service, account, reference.id) { mutableStateOf(false) }
    var error by remember(service, account, reference.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(service, account, reference.id, retry) {
        if (account == null) return@LaunchedEffect
        busy = true
        try { followed = withContext(Dispatchers.IO) { service.isFollowing(reference) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.asUserMessage() }
        finally { busy = false }
    }
    OutlinedButton(enabled = !busy, onClick = {
        if (account == null) { error = "请先登录${artist.source.label}"; return@OutlinedButton }
        if (followed == null) { retry++; return@OutlinedButton }
        val desired = followed != true
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(entry.authentication?.currentAccount()?.account?.id == account) { "账号已变化" }
                    service.setFollowing(reference, desired)
                    check(entry.authentication?.currentAccount()?.account?.id == account) { "账号已变化" }
                }
                followed = desired
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.asUserMessage() }
            finally { busy = false }
        }
    }) { Text(if (busy) "处理中…" else if (followed == true) "已关注" else if (followed == null && account != null) "重试" else "关注") }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("歌手关注") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

internal fun supportsArtistFollowing(context: android.content.Context, source: MusicSource) =
    (SourcePackageController.get(context).running(source.id)?.entry?.entities as? SourceEntityFollowing)?.supportsFollowing == true

@Composable
internal fun ArtistFollowingDirectory(source: MusicSource, accountId: String?) {
    val context = LocalContext.current
    val service = SourcePackageController.get(context).running(source.id)?.entry?.entities as? SourceEntityFollowing ?: return
    val navigation = LocalEntityNavigation.current
    var open by remember(source.id, accountId) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var artists by remember(source.id, accountId) { mutableStateOf<List<SourceEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }, enabled = accountId != null, modifier = Modifier.fillMaxWidth()) { Text("我关注的歌手") }
    if (!open) return
    LaunchedEffect(service, accountId, revision) {
        loading = true; error = null
        try {
            artists = withContext(Dispatchers.IO) {
                val result = mutableListOf<SourceEntity>()
                val seen = mutableSetOf<String>()
                var cursor: String? = null
                do {
                    val page = service.followed(SourceCursorRequest(cursor, policy = SourceFetchPolicy.REFRESH))
                    result += page.items
                    cursor = page.nextCursor
                    check(cursor == null || seen.add(cursor)) { "关注列表分页未前进" }
                } while (cursor != null)
                result.distinctBy { it.reference.id }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.asUserMessage() }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("我关注的歌手") }, text = {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text("重试") } }
            if (!loading && error == null && artists.isEmpty()) Text("还没有关注歌手")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(artists, key = { it.reference.id }) { artist ->
                    Text(artist.reference.name, Modifier.fillMaxWidth().clickable {
                        open = false
                        navigation?.open(EntityTarget.Artist(MainSourceArtist(artist.reference.id, artist.reference.name,
                            artist.artwork.url, source, artist.reference)))
                    }.padding(vertical = 16.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
}
