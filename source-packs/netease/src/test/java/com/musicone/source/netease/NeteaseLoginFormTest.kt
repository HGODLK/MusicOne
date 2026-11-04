package com.musicone.source.netease

import org.junit.Assert.*
import org.junit.Test

class NeteaseLoginFormTest {
    private val sent = NeteaseLoginState(phone = "13800138000", captcha = "1234",
        captchaSent = true, resendSeconds = 50, loginMethod = NeteaseLoginMethod.SMS)

    @Test fun duplicateInputAfterErrorKeepsChallengeAndCode() {
        val failed = sent.copy(message = "验证码错误")
        assertSame(failed, failed.changePhone("138 0013 8000"))
        assertSame(failed, failed.changeCountry("+86"))
        assertTrue(failed.changePhone(failed.phone).captchaSent)
    }

    @Test fun changingAccountInvalidatesOldSmsAndUnlocksForm() {
        for (changed in listOf(sent.copy(working = true).changePhone("13900138000"),
            sent.copy(working = true).changeCountry("+852"), sent.resetSms())) {
            assertFalse(changed.captchaSent)
            assertFalse(changed.working)
            assertEquals("", changed.captcha)
            assertEquals(0, changed.resendSeconds)
        }
    }
}
