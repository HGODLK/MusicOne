package com.musicone.sourceintegration

import com.musicone.source.sdk.SourcePayload

/** 续页只属于一次电台会话和一个音源；旧会话迟到的结果不能写进新会话，也不能交给其他音源。 */
internal class SourceRadioContinuation {
    private var session = 0L
    private var sourceId: String? = null
    private var payload: SourcePayload? = null

    @Synchronized fun reset() {
        session++
        sourceId = null
        payload = null
    }

    @Synchronized fun session(): Long = session

    @Synchronized fun read(session: Long, sourceId: String): SourcePayload? =
        payload.takeIf { session == this.session && sourceId == this.sourceId }

    @Synchronized fun write(session: Long, sourceId: String, value: SourcePayload) {
        if (session != this.session) return
        this.sourceId = sourceId
        payload = value
    }
}
