package com.musicone

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

@Composable
internal fun ArtistIntroduction(profile: MainSourceArtistProfile, modifier: Modifier, playable: Boolean, onPlay: () -> Unit,
    pager: PagerState, appearance: State<ArtistAppearance>, usePhotoTheme: Boolean = true,
    following: @Composable () -> Unit = {}) {
    var reading by remember { mutableStateOf(false) }
    val photos = profile.backgrounds
    val scope = rememberCoroutineScope()
    val motion = LocalPlaylistMotion.current
    val avatar = LocalEntityFrame.current?.artistAvatar ?: rememberArtistAvatar(profile.artwork)
    Box(modifier.drawBehind { drawRect(appearance.value.background) }) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
            userScrollEnabled = photos.size > 1 && motion?.moving != true) { index ->
            photos.getOrNull(index)?.let { url -> key(url) {
                ArtistBackgroundArtwork(url, Modifier.fillMaxSize())
            } }
        }
        Box(Modifier.fillMaxSize().drawBehind {
            val shade = appearance.value.background
            drawRect(Brush.verticalGradient(
                0f to Color.Transparent, .25f to shade.copy(alpha = 0f), .55f to shade.copy(alpha = .6f), .85f to shade, 1f to shade))
        })
            // 固定信息槽位，简介和轮播异步出现不能改变共享头像的落点。
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(304.dp)
                .padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtistAvatar(avatar, profile.name.take(1),
                    Modifier.size(64.dp).motionAnchor(motion, motion?.coverKey.orEmpty(), true,
                        corner = 32f, markSize = 24f, markX = 0f, markY = 0f), 24.sp)
                Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                ArtistAccentText(profile.name, appearance, Modifier.playlistDetailReveal(),
                    LocalTextStyle.current.copy(fontSize = 30.sp, lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
                }
                Box(Modifier.fillMaxWidth().height(74.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(profile.introduction.isNotBlank(),
                    enter = fadeIn(musicMotion(280)) + slideInVertically(musicMotion(320)) { it / 4 },
                    exit = fadeOut(musicMotion(180))) {
                Column(Modifier.widthIn(max = 440.dp).playlistDetailReveal()
                    .clickable(onClickLabel = "查看歌手简介") { reading = true }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(profile.introduction, color = Color.White.copy(alpha = .86f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    ArtistAccentText("简介 ›", appearance, Modifier.padding(top = 4.dp), MaterialTheme.typography.labelMedium)
                }
                }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtistLiveTheme(appearance, enabled = usePhotoTheme) { following() }
                ArtistPhotoPlayButton(appearance, playable, onPlay)
                }
                Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
                if (photos.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + photos.size) % photos.size,
                        animationSpec = musicMotion(320)) } }) { Icon(Icons.Default.ChevronLeft, "上一张背景", tint = Color.White) }
                    Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % photos.size,
                        animationSpec = musicMotion(320)) } }) { Icon(Icons.Default.ChevronRight, "下一张背景", tint = Color.White) }
                }
                }
            }
    }
    ArtistReadingWindow(reading, appearance, usePhotoTheme, profile.name, profile.introduction) { reading = false }
}
