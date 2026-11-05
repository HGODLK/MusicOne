package com.musicone.source.subsonic.internal

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Subsonic 协议错误；code 沿用协议定义（40 账号或密码错误、50 无权限、70 不存在等）。 */
internal class SubsonicApiException(val code: Int, message: String) : Exception(message)
/** 网络、HTTP 状态或非 Subsonic 响应；消息不含带凭据的地址。 */
internal class SubsonicTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 只发 Subsonic REST 请求；读取用 GET，带大量歌曲编号的写入用表单 POST。 */
internal class SubsonicClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun get(server: SubsonicServer, method: String, parameters: List<Pair<String, String>> = emptyList()): JSONObject =
        execute(server, method, parameters, post = false)

    fun post(server: SubsonicServer, method: String, parameters: List<Pair<String, String>>): JSONObject =
        execute(server, method, parameters, post = true)

    /** 媒体与封面地址直接给播放器和图片加载器，认证参数随地址携带。 */
    fun url(server: SubsonicServer, method: String, parameters: List<Pair<String, String>>): String =
        "${server.baseUrl}/rest/$method?${encode(server.authParameters() + parameters)}"

    private fun execute(server: SubsonicServer, method: String, parameters: List<Pair<String, String>>, post: Boolean): JSONObject {
        val query = encode(server.authParameters() + parameters)
        val target = if (post) "${server.baseUrl}/rest/$method" else "${server.baseUrl}/rest/$method?$query"
        val connection = try { URL(target).openConnection() as HttpURLConnection }
        catch (error: Exception) { throw SubsonicTransportException("服务器地址无效", error) }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "$SUBSONIC_CLIENT_NAME/1.0")
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(query.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return parseSubsonicResponse(text, status)
        } catch (error: SubsonicApiException) { throw error }
        catch (error: SubsonicTransportException) { throw error }
        catch (error: java.net.UnknownHostException) { throw SubsonicTransportException("找不到服务器，请检查地址", error) }
        catch (error: java.net.ConnectException) { throw SubsonicTransportException("无法连接服务器，请确认服务器已启动且网络可达", error) }
        catch (error: java.net.SocketTimeoutException) { throw SubsonicTransportException("连接服务器超时", error) }
        catch (error: javax.net.ssl.SSLException) { throw SubsonicTransportException("HTTPS 证书校验失败，暂不支持自签名证书", error) }
        catch (error: IOException) { throw SubsonicTransportException("与服务器通信失败", error) }
        finally { connection.disconnect() }
    }

    private fun encode(parameters: List<Pair<String, String>>): String = parameters.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
}

/** 解析统一响应外壳；协议错误优先于 HTTP 状态，方便识别账号错误。 */
internal fun parseSubsonicResponse(text: String, status: Int): JSONObject {
    val root = try { JSONObject(text).optJSONObject("subsonic-response") } catch (_: JSONException) { null }
    if (root == null) {
        val reason = when (status) {
            401, 403 -> "服务器拒绝了请求，请检查账号"
            404 -> "该地址不是 Subsonic 服务器，请检查地址或路径"
            in 500..599 -> "服务器内部错误（HTTP $status）"
            else -> if (status in 200..299) "该地址不是 Subsonic 服务器，请检查地址或路径" else "服务器返回异常（HTTP $status）"
        }
        throw SubsonicTransportException(reason)
    }
    if (root.optString("status") != "ok") {
        val error = root.optJSONObject("error")
        val code = error?.optInt("code", 0) ?: 0
        throw SubsonicApiException(code, subsonicErrorMessage(code, error?.optString("message").orEmpty()))
    }
    return root
}

private fun subsonicErrorMessage(code: Int, serverMessage: String): String = when (code) {
    10 -> "请求缺少必要参数"
    20 -> "客户端协议版本过旧，服务器不兼容"
    30 -> "服务器协议版本过旧，请升级服务器"
    40 -> "用户名或密码不正确"
    41 -> "服务器不支持 token 认证"
    42, 43, 44 -> "服务器不支持这种登录方式"
    50 -> "当前账号没有执行此操作的权限"
    60 -> "服务器试用期已结束"
    70 -> "服务器上找不到该内容"
    else -> serverMessage.take(120).ifBlank { "服务器返回错误（$code）" }
}
