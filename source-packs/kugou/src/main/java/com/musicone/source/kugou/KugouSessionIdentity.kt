package com.musicone.source.kugou

/** 同账号资料刷新保留进行中的请求；重新登录与真实会话切换仍隔离旧任务。 */
internal fun kugouSessionChanged(
    previousCookie: String, previousUserId: String?, cookie: String, userId: String, expectedLogin: Long?,
): Boolean = expectedLogin != null || previousCookie != cookie || previousUserId != userId
