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
