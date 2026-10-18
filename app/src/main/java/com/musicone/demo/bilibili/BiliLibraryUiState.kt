package com.musicone.demo

/**
 * B站音源与本地库交互的 UI 状态模型。
 */
internal enum class BiliLibraryPage { HOME, ACCOUNT, LINK, FOLDERS, FOLDER, VIDEO }

internal data class BiliLibraryUiState(
    val page: BiliLibraryPage = BiliLibraryPage.HOME,
    val videoReturnPage: BiliLibraryPage = BiliLibraryPage.LINK,
    val account: BiliAccountSession? = null,
    val generation: Long = 0L,
    val busy: Boolean = false,
    val sessionRevision: Long = 0L,

    // 登录流程
    val loginMethod: PlatformLoginMethod = PlatformLoginMethod.QR_CODE,
    val qrCode: BiliQrCode? = null,
    val qrStatus: PlatformQrLoginStatus = PlatformQrLoginStatus.IDLE,
    val qrMessage: String? = null,

    // 短信登录
    val phone: String = "",
    val countryCode: String = "86",
    val smsCode: String = "",
    val captchaKey: String = "",
    val smsSent: Boolean = false,
    val smsPhase: BiliSmsPhase = BiliSmsPhase.IDLE,
    val resendSeconds: Int = 0,
    val geetestChallenge: BiliGeetestChallenge? = null,

    // 收藏夹导航；缓存与分页由独立数据层持有。
    val selectedFolder: BiliFavFolder? = null,
    val folderReturnPage: BiliLibraryPage = BiliLibraryPage.HOME,

    // 单视频 / 链接解析与分P多选
    val linkInput: String = "",
    val resolvingLink: Boolean = false,
    val currentVideoDetails: BiliVideoDetails? = null,
    val currentVideoBvid: String = "",
    val currentVideoTitle: String = "",
    val videoPages: List<BiliPagePart> = emptyList(),
    val selectedCids: Set<Long> = emptySet(),

    // 导入目标与订阅
    val targetPlaylistId: String = UNIFIED_PLAYLIST_ID,
    val targetPlaylistTitle: String = "综合歌单",
    val subscriptions: List<LibrarySubscription> = emptyList(),
    val isSubscribing: Boolean = false,
    val playingFolderId: Long? = null,

    // 操作通用状态（兼容旧代码）
    val working: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)
