package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicone.OfflinePlaybackAvailability
import com.musicone.source.sdk.SourceEntry
import com.musicone.source.sdk.currentMembership
import com.musicone.source.sdk.detectMembership

/** 页面只在主动提供检测器时核验；未声明与未知保留不同值。 */
@Composable
internal fun rememberSourceMembership(entry: SourceEntry?): Boolean? {
    val context = LocalContext.current
    val offline by remember(context) { OfflinePlaybackAvailability.get(context) }.state.collectAsStateWithLifecycle()
    val revision = entry?.authentication?.currentAccount()?.revision
    var membership by remember(entry, revision) { mutableStateOf(if (entry == null) false else entry.currentMembership()) }
    LaunchedEffect(entry, revision, offline.online) {
        membership = if (entry == null) false else entry.currentMembership()
        if (entry != null && offline.online) membership = entry.detectMembership()
    }
    return membership
}
