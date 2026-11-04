package com.musicone

import com.musicone.source.sdk.*

/** 模板导航只保存公开实体引用，不根据平台前缀猜测专辑。 */
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
