package com.musicone.demo

/** 已保存的本平台账号可以先显示个人资料，联网校验继续独立进行。 */
internal fun canShowPlatformLibrary(source: MusicSource, account: MusicAccount?, status: SessionStatus): Boolean =
    status == SessionStatus.CONNECTED ||
        status == SessionStatus.CHECKING && account?.source == source
