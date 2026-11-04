package com.musicone

import com.musicone.source.sdk.SourceAudioQuality
import com.musicone.source.sdk.SourceEntityRef
import com.musicone.source.sdk.SourcePayload
import org.json.JSONObject

/** 只序列化公开模型外壳，音源私有载荷原样保存，不解释其业务格式。 */
internal fun SourceEntityRef.toStoredEntityReference(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("kind", kind).put("payload", payload.toStoredPayload())

internal fun JSONObject.toStoredEntityReference(): SourceEntityRef = SourceEntityRef(
    getString("id"), getString("name"), getString("kind"), optJSONObject("payload").storedPayload())

internal fun SourceAudioQuality.toStoredAudioQuality(): JSONObject = JSONObject()
    .put("id", id).put("label", label).put("mimeType", mimeType).put("available", available)
    .put("reason", reason).put("payload", payload.toStoredPayload())

internal fun JSONObject.toStoredAudioQuality(): SourceAudioQuality = SourceAudioQuality(
    getString("id"), getString("label"), takeUnless { isNull("mimeType") }?.optString("mimeType"),
    optBoolean("available", true), takeUnless { isNull("reason") }?.optString("reason"),
    optJSONObject("payload").storedPayload())

private fun SourcePayload.toStoredPayload(): JSONObject = JSONObject()
    .put("format", format).put("version", version).put("encoded", encoded)

private fun JSONObject?.storedPayload(): SourcePayload = if (this == null) SourcePayload()
    else SourcePayload(optString("format"), optInt("version", 1), optString("encoded"))
