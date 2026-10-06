package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class DeveloperCredit(
    val name: String,
    val account: String,
    val profileUrl: String,
)

internal val developerCredits = listOf(
    DeveloperCredit("ShuyunR", "HGODLK", "https://github.com/HGODLK"),
    DeveloperCredit("Killy", "Killy806", "https://github.com/Killy806"),
)

@Composable
internal fun DeveloperCredits() {
    val uriHandler = LocalUriHandler.current
    developerCredits.forEach { developer ->
        ListItem(
            headlineContent = { Text(developer.name) },
            supportingContent = { Text("github.com/${developer.account}") },
            leadingContent = {
                RemoteArtwork(
                    imageUrl = "${developer.profileUrl}.png?size=128",
                    start = developer.name.hashCode().toLong(),
                    end = developer.profileUrl.hashCode().toLong(),
                    mark = developer.name.take(1),
                    modifier = Modifier.size(44.dp),
                    markSize = 18.sp,
                    shape = CircleShape,
                    markAlignment = androidx.compose.ui.Alignment.Center,
                )
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            },
            modifier = Modifier.clickable(
                onClickLabel = "打开 ${developer.name} 的 GitHub 主页",
                role = Role.Button,
            ) {
                uriHandler.openUri(developer.profileUrl)
            },
        )
    }
}
