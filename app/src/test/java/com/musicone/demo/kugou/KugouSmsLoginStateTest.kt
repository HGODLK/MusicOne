package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouSmsLoginStateTest {
    @Test fun retriesKeepDeviceAndVerificationCookies() {
        val first = kugouLoginDeviceCookie("verify=passed; KUGOU_API_MID=existing")
        val second = kugouLoginDeviceCookie(first)
        assertEquals(first, second)
        assertEquals("passed", second.cookieValues()["verify"])
        assertEquals("existing", second.cookieValues()["KUGOU_API_MID"])
    }

    @Test fun ambiguousResponseDoesNotClaimSmsWasSent() {
        val error = runCatching { JSONObject("""{"error_code":0}""")
            .requireKugouSmsSuccess("发送失败", "device=stable") }.exceptionOrNull()
        assertTrue(error is KugouSmsRequestException)
        assertEquals("device=stable", (error as KugouSmsRequestException).credentialSeed)
    }

    @Test fun multipleAccountsRequireQrInsteadOfSelectingAnAccount() {
        val error = runCatching { JSONObject("""{"status":0,"error_code":34175,"data":{
            "info_list":[{"userid":1},{"userid":2}]}}""")
            .requireKugouSmsSuccess("登录失败", "device=stable") }.exceptionOrNull()
        assertEquals("此手机号关联多个酷狗账号，请使用扫码登录", error?.message)
        assertEquals(34175, (error as PlatformApiException).apiCode)
    }

    @Test fun onlyExplicitSuccessAcceptsTheSendResponse() {
        JSONObject("""{"status":1,"error_code":0}""").requireKugouSmsSuccess("发送失败", "")
        assertTrue(runCatching { JSONObject("""{"status":2}""")
            .requireKugouSmsSuccess("发送失败", "") }.isFailure)
    }
}
