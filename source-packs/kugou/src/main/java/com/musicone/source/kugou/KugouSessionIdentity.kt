package com.musicone.source.kugou

/** 判断会话是否真正换了账号或重新登录（只刷同账号资料时返回 false，不打断在途请求）。 */
internal fun kugouSessionChanged(
    previousCookie: String, previousUserId: String?, cookie: String, userId: String, expectedLogin: Long?,
): Boolean = expectedLogin != null || previousCookie != cookie || previousUserId != userId
