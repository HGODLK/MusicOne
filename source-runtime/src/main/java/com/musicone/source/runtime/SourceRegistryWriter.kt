package com.musicone.source.runtime

import org.json.JSONObject

internal object SourceRegistryWriter {
    fun write(states: Collection<InstalledSource>): String = JSONObject().apply {
        states.forEach { state -> put(state.id, JSONObject().apply {
            put("signer", state.signerSha256)
            put("active", state.activeVersion ?: JSONObject.NULL)
            put("pending", state.pendingVersion ?: JSONObject.NULL)
            put("rollback", state.rollbackVersion ?: JSONObject.NULL)
            put("activeInstallId", state.activeInstallId ?: JSONObject.NULL)
            put("pendingInstallId", state.pendingInstallId ?: JSONObject.NULL)
            put("rollbackInstallId", state.rollbackInstallId ?: JSONObject.NULL)
            put("removed", state.removed); put("error", state.error ?: "")
            put("pendingRemoval", state.pendingRemoval)
            put("kind", state.kind?.name ?: JSONObject.NULL); put("name", state.name ?: JSONObject.NULL)
            put("versionName", state.versionName ?: JSONObject.NULL)
            put("pendingVersionName", state.pendingVersionName ?: JSONObject.NULL)
        }) }
    }.toString()
}
