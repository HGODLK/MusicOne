package com.musicone.source.kugou

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.musicone.source.sdk.SourceAccountState
import com.musicone.source.sdk.SourceAuthentication
import com.musicone.source.sdk.SourceAuthenticationPage
import com.musicone.source.sdk.SourceSessionStatus

/** 自定义根页也走已有认证页；不可见的相邻页不创建第二份扫码任务。 */
@Composable internal fun KugouRequiredLogin(preferences: SharedPreferences, authentication: SourceAuthentication,
    page: SourceAuthenticationPage, active: Boolean, content: @Composable () -> Unit) {
    var account by remember(authentication) { mutableStateOf(authentication.currentAccount()) }
    DisposableEffect(preferences, authentication) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("session_kugou_") == true) account = authentication.currentAccount()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        account = authentication.currentAccount()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (kugouRootNeedsLogin(account)) {
        if (active) page.Content() else Box(Modifier.fillMaxSize())
    } else content()
}

internal fun kugouRootNeedsLogin(account: SourceAccountState): Boolean =
    account.status != SourceSessionStatus.AUTHENTICATED || account.account == null
