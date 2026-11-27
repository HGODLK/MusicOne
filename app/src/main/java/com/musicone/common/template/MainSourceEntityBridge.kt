package com.musicone

import com.musicone.source.sdk.*

/** 把 SDK 实体转成模板歌单对象，只保留公开的实体引用，不靠 ID 前缀硬猜专辑。 */
internal fun SourceEntity.asTemplatePlaylist(source: MusicSource): MusicPlaylist = MusicPlaylist(
    "${source.id}:album:${reference.id}", source, reference.name, "", description,
    attributes["trackCount"]?.toIntOrNull() ?: 0, artwork.startArgb, artwork.endArgb, artwork.mark,
    emptyList(), artwork.url, providerData = mapOf("templateEntityKind" to reference.kind,
        "templateEntityId" to reference.id, "templateEntityPayloadFormat" to reference.payload.format,
        "templateEntityPayloadVersion" to reference.payload.version.toString(), "templateEntityPayload" to reference.payload.encoded),
    remoteKey = reference.id,
)
internal fun MusicPlaylist.templateEntityReference(): SourceEntityRef? = providerData["templateEntityId"]?.let { id ->
    SourceEntityRef(id, title, providerData["templateEntityKind"].orEmpty(), SourcePayload(
        providerData["templateEntityPayloadFormat"].orEmpty(), providerData["templateEntityPayloadVersion"]?.toIntOrNull() ?: 1,
        providerData["templateEntityPayload"].orEmpty()))
}
