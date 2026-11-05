package com.musicone

internal enum class MainSourceFavoriteAccess { UNSUPPORTED, LOGIN_REQUIRED, AVAILABLE }

/** 收藏目录按实际声明开放；可登录音源仍保留账号收藏边界。 */
internal fun mainSourceFavoriteAccess(
    signedIn: Boolean,
    requiresAccount: Boolean,
    supportsAuthentication: Boolean,
    supportsFavorites: Boolean,
): MainSourceFavoriteAccess = when {
    !supportsFavorites -> MainSourceFavoriteAccess.UNSUPPORTED
    signedIn || (!requiresAccount && !supportsAuthentication) -> MainSourceFavoriteAccess.AVAILABLE
    else -> MainSourceFavoriteAccess.LOGIN_REQUIRED
}

/** 我喜欢不可用时只显示综合歌单，“隐藏综合歌单”对该音源不生效，避免顶部卡片位空着。 */
internal fun mainSourceLibraryCards(
    favorite: MusicPlaylist,
    unified: MusicPlaylist,
    access: MainSourceFavoriteAccess,
    hideUnified: Boolean,
): Pair<MusicPlaylist, MusicPlaylist?> =
    if (access == MainSourceFavoriteAccess.UNSUPPORTED) unified to null
    else favorite to unified.takeUnless { hideUnified }
