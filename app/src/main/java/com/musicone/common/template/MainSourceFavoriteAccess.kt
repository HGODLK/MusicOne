package com.musicone

internal enum class MainSourceFavoriteAccess { UNSUPPORTED, LOGIN_REQUIRED, AVAILABLE }

/** 按音源声明判断“我喜欢”是否可用；需要登录的音源在未登录前不开放。 */
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
