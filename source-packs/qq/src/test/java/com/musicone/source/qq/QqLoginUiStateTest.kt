package com.musicone.source.qq

import com.musicone.source.qq.internal.PlatformLoginMethod
import org.junit.Assert.*
import org.junit.Test

class QqLoginUiStateTest {
    @Test fun repeatedNormalizedInputKeepsFailedSmsAttemptRetryable() {
        val failed = QqLoginUiState(phone = "13800138000", captcha = "123456", captchaSent = true,
            resendSeconds = 30, message = "验证码错误")
        assertSame(failed, failed.changePhone("138 0013 8000"))
        assertSame(failed, failed.changeCountry("+86"))
        assertTrue(failed.changePhone(failed.phone).canLogin)
    }
    @Test fun editingThePhoneInvalidatesThePreviouslySentSms() {
        val sent = QqLoginUiState(phone = "13800138000", captcha = "123456", captchaSent = true, resendSeconds = 59)
        assertTrue(sent.canLogin)
        val changed = sent.changePhone("139 0013 8000")
        assertEquals("13900138000", changed.phone)
        assertFalse(changed.canLogin)
        assertFalse(changed.captchaSent)
        assertEquals("", changed.captcha)
        assertEquals(0, changed.resendSeconds)
    }

    @Test fun editingTheCountryInvalidatesThePreviouslySentSms() {
        val changed = QqLoginUiState(phone = "13800138000", captcha = "123456", captchaSent = true,
            resendSeconds = 59).changeCountry("+852")
        assertEquals("852", changed.countryCode)
        assertFalse(changed.canLogin)
        assertFalse(changed.captchaSent)
        assertEquals("", changed.captcha)
    }

    @Test fun resendAndLoginRequireACompleteIdleForm() {
        val phone = QqLoginUiState(phone = "13800138000")
        assertTrue(phone.canSendCaptcha)
        assertFalse(phone.copy(resendSeconds = 1).canSendCaptcha)
        assertFalse(phone.copy(working = true).canSendCaptcha)
        assertFalse(phone.copy(countryCode = "").canSendCaptcha)
        assertFalse(phone.copy(phone = "123").canSendCaptcha)
        assertFalse(phone.copy(captcha = "123456").canLogin)
        assertTrue(phone.copy(captcha = "123456", captchaSent = true).canLogin)
        assertFalse(phone.copy(captcha = "123456", captchaSent = true, working = true).canLogin)
    }

    @Test fun qqRetainsTheExistingSmsEntryMethod() {
        assertEquals(PlatformLoginMethod.SMS, QqLoginUiState().loginMethod)
    }
}
