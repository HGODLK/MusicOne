package com.musicone.demo

import android.webkit.JavascriptInterface
import org.json.JSONObject

internal enum class BiliGeetestFailure(val message: String) {
    SCRIPT("安全验证脚本加载失败，请重新获取验证码"),
    CONFIG("安全验证配置加载失败，请重新获取验证码"),
    INITIALIZE("安全验证初始化失败，请重新获取验证码"),
    VERIFY("安全验证失败，请重新获取验证码"),
    TIMEOUT("安全验证加载超时，请检查网络后重新获取验证码"),
    RESULT("安全验证结果不完整，请重新获取验证码"),
    NETWORK("安全验证网页加载失败，请检查网络后重新获取验证码"),
}

internal fun BiliGeetestResult.isComplete() =
    challenge.isNotBlank() && validate.isNotBlank() && seccode.isNotBlank()

/** 网页只回传固定错误阶段，避免将第三方脚本的原始错误及票据写入日志。 */
internal class BiliGeetestBridge(
    private val onReady: () -> Unit,
    private val onSuccess: (BiliGeetestResult) -> Unit,
    private val onClose: () -> Unit,
    private val onFailure: (BiliGeetestFailure) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(type: String, dataJson: String) {
        when (type) {
            "ready" -> onReady()
            "success" -> {
                val result = runCatching {
                    val obj = JSONObject(dataJson)
                    BiliGeetestResult(obj.opt("geetest_challenge") as? String ?: "",
                        obj.opt("geetest_validate") as? String ?: "", obj.opt("geetest_seccode") as? String ?: "")
                }.getOrNull()
                if (result != null && result.isComplete()) onSuccess(result)
                else onFailure(BiliGeetestFailure.RESULT)
            }
            "close" -> onClose()
            "error" -> {
                val stage = runCatching { JSONObject(dataJson).optString("stage") }.getOrDefault("")
                onFailure(BiliGeetestFailure.entries.firstOrNull { it.name == stage } ?: BiliGeetestFailure.VERIFY)
            }
        }
    }
}
