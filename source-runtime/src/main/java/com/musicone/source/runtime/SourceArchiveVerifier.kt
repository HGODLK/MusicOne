package com.musicone.source.runtime

import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile

data class VerifiedSourceArchive(val file: File, val manifest: SourceManifest, val signerSha256: String)

class SourceArchiveVerifier(private val androidApi: Int = Build.VERSION.SDK_INT,
    private val hostCompatibility: SourceHostCompatibility = SourceHostCompatibility(0, "未知")) {
    fun verify(file: File, originalName: String = file.name): VerifiedSourceArchive {
        require(file.isFile) { "音源包不存在" }
        var signer: String? = null
        var manifestText: String? = null
        var hasDex = false
        var totalBytes = 0L
        // 完整读取每个载荷，触发 JAR v1 摘要和证书验证；不能只读取清单中的自报身份。
        JarFile(file, true).use { jar ->
            val seen = HashSet<String>()
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(seen.add(entry.name)) { "包包含重名文件" }
                require(!entry.name.startsWith('/') && '\\' !in entry.name &&
                    entry.name.split('/').none { it == ".." || it == "." }) { "包包含非法路径" }
                if (entry.isDirectory || entry.name.startsWith("META-INF/", true)) continue
                require(entry.size in 0..128L * 1024 * 1024) { "包文件过大或长度无效" }
                totalBytes += entry.size
                require(totalBytes <= 256L * 1024 * 1024) { "音源包解压后过大" }
                val bytes = jar.getInputStream(entry).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    var consumed = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        consumed += count
                        require(consumed <= entry.size) { "包文件长度与目录不一致" }
                        output.write(buffer, 0, count)
                    }
                    require(consumed == entry.size) { "包文件不完整" }
                    output.toByteArray()
                }
                val certificates = entry.certificates ?: error("包载荷未通过 APK v1 签名验证")
                require(certificates.isNotEmpty()) { "包载荷缺少签名" }
                val identity = MessageDigest.getInstance("SHA-256").digest(certificates.first().encoded)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                require(signer == null || signer == identity) { "包载荷签名身份不一致" }
                signer = identity
                if (entry.name == "assets/musicone-source.json") manifestText = bytes.toString(Charsets.UTF_8)
                if (entry.name == "classes.dex") hasDex = true
            }
        }
        require(hasDex) { "包缺少可执行 DEX" }
        val manifest = SourceManifest.parse(requireNotNull(manifestText) { "包缺少音源清单" })
        manifest.checkCompatibility(androidApi, hostCompatibility)
        require(originalName.substringAfterLast('.', "").lowercase() == manifest.descriptor.kind.extension) {
            "包后缀与主／补充音源类型不一致"
        }
        return VerifiedSourceArchive(file, manifest, requireNotNull(signer) { "包没有有效签名" })
    }
}
