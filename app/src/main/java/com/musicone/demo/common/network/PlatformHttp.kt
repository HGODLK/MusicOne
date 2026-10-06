package com.musicone.demo

import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class PlatformHttpResponse(
    val status: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
) {
    val text: String get() = bytes.toString(StandardCharsets.UTF_8)

    fun cookies(): Map<String, String> = buildMap {
        headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { header ->
                val pair = header.substringBefore(';').split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) put(pair[0].trim(), pair[1].trim())
            }
    }
}

internal object PlatformHttp {
    private data class PlaybackProbe(
        val url: String,
        val status: Int? = null,
        val contentType: String = "",
        val finalHost: String = "",
        val readable: Boolean = false,
        val errorType: String = "",
    )

    fun get(
        url: String,
        cookie: String = "",
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): PlatformHttpResponse = request("GET", url, null, cookie, headers, followRedirects)

    fun postJson(
        url: String,
        body: String,
        cookie: String = "",
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): PlatformHttpResponse = request(
        method = "POST",
        url = url,
        body = body.toByteArray(StandardCharsets.UTF_8),
        cookie = cookie,
        headers = mapOf("Content-Type" to "application/json; charset=utf-8") + headers,
        followRedirects = true,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    fun postForm(
        url: String,
        values: Map<String, String>,
        cookie: String = "",
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): PlatformHttpResponse {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }.toByteArray(StandardCharsets.UTF_8)
        return request(
            method = "POST",
            url = url,
            body = body,
            cookie = cookie,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8") + headers,
            followRedirects = followRedirects,
        )
    }

    fun postMultipartForm(
        url: String,
        values: Map<String, String>,
        cookie: String = "",
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): PlatformHttpResponse {
        val boundary = "----MusicOne${System.nanoTime()}"
        val body = buildString {
            for ((key, value) in values) {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                append(value).append("\r\n")
            }
            append("--").append(boundary).append("--\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        return request(
            method = "POST",
            url = url,
            body = body,
            cookie = cookie,
            headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary") + headers,
            followRedirects = followRedirects,
        )
    }

    fun firstReadable(
        urls: List<String>,
        headers: Map<String, String> = emptyMap(),
        maxWaitMs: Long = 2_500L,
    ): String? {
        val candidates = urls.distinct().take(MAX_PARALLEL_READ_CANDIDATES)
        if (candidates.isEmpty()) return null
        val executor = Executors.newFixedThreadPool(candidates.size)
        val completion = ExecutorCompletionService<PlaybackProbe>(executor)
        val completedProbes = mutableListOf<PlaybackProbe>()
        return try {
            candidates.forEach { candidate ->
                completion.submit(Callable { probe(candidate, headers, PLAYBACK_PROBE_TIMEOUT_MS) })
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs)
            repeat(candidates.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    logPlaybackProbes(completedProbes, candidates.size)
                    return null
                }
                val completed = completion.poll(remaining, TimeUnit.NANOSECONDS)
                if (completed == null) {
                    logPlaybackProbes(completedProbes, candidates.size)
                    return null
                }
                runCatching { completed.get() }.getOrNull()?.let { probe ->
                    completedProbes += probe
                    if (probe.readable) return probe.url
                }
            }
            logPlaybackProbes(completedProbes, candidates.size)
            null
        } finally {
            executor.shutdownNow()
        }
    }

    fun canRead(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 3_000,
    ): Boolean = probe(url, headers, timeoutMs).readable

    private fun probe(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): PlaybackProbe = try {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val status = connection.responseCode
            val contentType = connection.contentType.orEmpty().substringBefore(';')
            val host = connection.url.host.orEmpty()
            val readable = status in 200..299 && connection.inputStream.use { it.read() >= 0 }
            PlaybackProbe(url, status, contentType, host, readable)
        } finally {
            connection.disconnect()
        }
    } catch (error: Throwable) {
        PlaybackProbe(url, errorType = error::class.java.simpleName)
    }

    private fun logPlaybackProbes(probes: List<PlaybackProbe>, requested: Int) {
        val summary = probes.joinToString("；") { probe ->
            "status=${probe.status ?: "无"}, type=${probe.contentType.ifBlank { "未知" }}, " +
                "host=${probe.finalHost.ifBlank { "未知" }}, read=${probe.readable}, error=${probe.errorType.ifBlank { "无" }}"
        }
        runCatching { Log.w("QqPlayback", "音频探测失败：已完成=${probes.size}/$requested；$summary") }
    }

    private const val MAX_PARALLEL_READ_CANDIDATES = 12
    private const val PLAYBACK_PROBE_TIMEOUT_MS = 2_000
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 12_000
    private const val DEFAULT_READ_TIMEOUT_MS = 25_000

    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        cookie: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): PlatformHttpResponse {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = followRedirects
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..399) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            if (status !in 200..399) throw PlatformApiException("平台请求失败（$status）", status)
            val platformHeaders: Map<String?, List<String>> = connection.headerFields
            val responseHeaders = buildMap {
                for ((name, values) in platformHeaders) if (name != null) put(name, values)
            }
            return PlatformHttpResponse(status, bytes, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 MusicOne/0.1"
}

internal fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

internal fun Map<String, String>.asCookieHeader(): String = entries
    .sortedBy { it.key }
    .joinToString("; ") { (key, value) -> "$key=$value" }

internal fun String.cookieValues(): Map<String, String> = split(';').mapNotNull { part ->
    val pair = part.trim().split('=', limit = 2)
    pair.takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() }
}.toMap()
