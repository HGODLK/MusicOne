package com.musicone.source.kugou

import org.json.JSONObject
import java.io.File

/** 保存已核对的最新加入歌曲封面；目录和详情共用，排序不会改变封面。 */
internal class KugouCollectionArtworkStore(private val directory: File) {
    private fun customFile(owner: String, target: KugouManagedCollection) = File(directory,
        "${KugouProtocol.md5("$owner:${target.listId}")}.custom.json")

    @Synchronized fun readCustom(owner: String, target: KugouManagedCollection): String? = runCatching {
        val root = JSONObject(customFile(owner, target).readText())
        require(root.getString("pic") == target.raw.optString("pic"))
        root.getString("url")
    }.getOrNull()

    private fun version(target: KugouManagedCollection) = "${target.raw.optString("list_ver")}:${target.raw.optString("count")}"
    private fun file(owner: String, target: KugouManagedCollection) = File(directory,
        "${KugouProtocol.md5("$owner:${target.listId}")}.json")

    @Synchronized fun read(owner: String, target: KugouManagedCollection): String? = runCatching {
        val root = JSONObject(file(owner, target).readText())
        require(root.getString("owner") == owner && root.getString("list") == target.listId &&
            root.getString("version") == version(target))
        root.getString("url").takeIf { it.isNotBlank() }
    }.getOrNull()

    @Synchronized fun save(owner: String, target: KugouManagedCollection, url: String, check: () -> Unit = {}) {
        check()
        directory.mkdirs()
        val file = file(owner, target)
        android.util.AtomicFile(file).let { atomic ->
            val output = atomic.startWrite()
            try {
                output.write(JSONObject().put("owner", owner).put("list", target.listId)
                    .put("version", version(target)).put("url", url).toString().toByteArray(Charsets.UTF_8))
                check()
                atomic.finishWrite(output)
            } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
    }

    @Synchronized fun invalidate(owner: String) {
        directory.listFiles().orEmpty().filter { it.extension == "json" && !it.name.endsWith(".custom.json") }.forEach { file ->
            if (runCatching { JSONObject(file.readText()).optString("owner") == owner }.getOrDefault(false))
                android.util.AtomicFile(file).delete()
        }
    }
}
