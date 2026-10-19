package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlatformQrLoginControllerTest {
    private val account = MusicAccount(MusicSource.QQ, "123456", "测试账号", null)
    private fun challenge(method: PlatformLoginMethod = PlatformLoginMethod.QR_CODE) =
        PlatformQrChallenge(MusicSource.QQ, method.name, loginMethod = method, scanMessage = "请扫码")
    private fun success(credential: String = "music-key") =
        PlatformQrResult(PlatformQrLoginStatus.SUCCESS, "登录成功", credential, account)

    @Test
    fun 切换方式后忽略旧二维码的迟到成功() = runBlocking {
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newSaved = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val statuses = mutableListOf<PlatformQrLoginStatus>()
        val controller = PlatformQrLoginController(this, { _, method, _ -> challenge(method) }, { qr ->
            if (qr.loginMethod == PlatformLoginMethod.QR_CODE) {
                oldEntered.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
                success("old")
            } else success("new")
        }, { status, _, _ -> statuses += status }, { credential, _ ->
            saved += credential
            newSaved.complete(Unit)
        }, { _, _ -> false }, pollIntervalMs = 1)
        try {
            controller.start(MusicSource.QQ, PlatformLoginMethod.QR_CODE, "device")
            withTimeout(2_000) { oldEntered.await() }
            controller.start(MusicSource.QQ, PlatformLoginMethod.WECHAT_QR_CODE, "device")
            withTimeout(2_000) { newSaved.await() }
            releaseOld.complete(Unit)
            yield()
            assertEquals(listOf("new"), saved)
            assertFalse(statuses.contains(PlatformQrLoginStatus.FAILED))
        } finally {
            releaseOld.complete(Unit)
            controller.stop()
        }
    }

    @Test
    fun 离页后迟到的二维码创建结果不能再显示或轮询() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val statuses = mutableListOf<PlatformQrLoginStatus>()
        var checks = 0
        val controller = PlatformQrLoginController(this, { _, _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            challenge()
        }, { checks++; success() }, { status, _, _ -> statuses += status }, { _, _ -> fail("不能保存") },
            { _, _ -> false }, pollIntervalMs = 1)
        controller.start(MusicSource.QQ, PlatformLoginMethod.QR_CODE, "device")
        entered.await()
        controller.stop()
        release.complete(Unit)
        yield()
        assertEquals(listOf(PlatformQrLoginStatus.LOADING), statuses)
        assertEquals(0, checks)
    }

    @Test
    fun 已扫码后长轮询无事件不退回未扫码提示() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val statuses = mutableListOf<PlatformQrLoginStatus>()
        var checks = 0
        val controller = PlatformQrLoginController(this, { _, _, _ -> challenge() }, {
            when (++checks) {
                1 -> PlatformQrResult(PlatformQrLoginStatus.WAITING_CONFIRM, "已扫码", "seed")
                2 -> PlatformQrResult(PlatformQrLoginStatus.WAITING_SCAN, "等待扫码", "seed")
                else -> success()
            }
        }, { status, _, _ -> statuses += status }, { _, _ -> done.complete(Unit) }, { _, _ -> false }, pollIntervalMs = 1)
        try {
            controller.start(MusicSource.QQ, PlatformLoginMethod.WECHAT_QR_CODE, "device")
            withTimeout(2_000) { done.await() }
            assertEquals(listOf(PlatformQrLoginStatus.LOADING, PlatformQrLoginStatus.WAITING_SCAN,
                PlatformQrLoginStatus.WAITING_CONFIRM, PlatformQrLoginStatus.WAITING_CONFIRM, PlatformQrLoginStatus.SUCCESS), statuses)
        } finally { controller.stop() }
    }

    @Test
    fun 到期停止轮询并允许重新生成() = runBlocking {
        var now = 0L
        var checks = 0
        val expired = CompletableDeferred<Unit>()
        val controller = PlatformQrLoginController(this, { _, _, _ -> challenge() }, {
            checks++
            now = 180_000
            PlatformQrResult(PlatformQrLoginStatus.WAITING_SCAN, "等待扫码", "seed")
        }, { status, _, _ -> if (status == PlatformQrLoginStatus.EXPIRED) expired.complete(Unit) },
            { _, _ -> fail("不能保存") }, { _, _ -> false }, pollIntervalMs = 1, nowMillis = { now })
        try {
            controller.start(MusicSource.QQ, PlatformLoginMethod.QR_CODE, "device")
            withTimeout(2_000) { expired.await() }
            assertEquals(1, checks)
        } finally { controller.stop() }
    }

    @Test
    fun 缺失账号的成功响应不能保存会话() = runBlocking {
        val failed = CompletableDeferred<String?>()
        val controller = PlatformQrLoginController(this, { _, _, _ -> challenge() }, { success().copy(account = null) },
            { status, message, _ -> if (status == PlatformQrLoginStatus.FAILED) failed.complete(message) },
            { _, _ -> fail("不能保存") }, { _, _ -> false }, pollIntervalMs = 1)
        try {
            controller.start(MusicSource.QQ, PlatformLoginMethod.QR_CODE, "device")
            assertTrue(withTimeout(2_000) { failed.await() }!!.contains("完整登录结果"))
        } finally { controller.stop() }
    }

    @Test
    fun 安全验证后继续检查时合并会话凭据() = runBlocking {
        val paused = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        var checks = 0
        val controller = PlatformQrLoginController(this, { _, _, _ -> challenge().copy(credentialSeed = "session=one") }, { qr ->
            if (++checks == 1) throw PlatformApiException("需要验证")
            assertEquals("session=one; verify=passed", qr.credentialSeed)
            success()
        }, { _, _, _ -> }, { _, _ -> done.complete(Unit) }, { _, checking ->
            assertTrue(checking)
            paused.complete(Unit)
            true
        }, pollIntervalMs = 1)
        try {
            controller.start(MusicSource.QQ, PlatformLoginMethod.QR_CODE, "device")
            withTimeout(2_000) { paused.await() }
            controller.mergeVerificationCredential("verify=passed")
            controller.resume()
            withTimeout(2_000) { done.await() }
            assertEquals(2, checks)
        } finally { controller.stop() }
    }

    @Test
    fun QQ安全验证后重建二维码并只向换票传递验证凭据() = runBlocking {
        val paused = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        var creates = 0
        var checks = 0
        val controller = PlatformQrLoginController(this, { _, method, deviceId ->
            creates++
            assertEquals("device", deviceId)
            challenge(method).copy(key = "qr-$creates", credentialSeed = "qrsig=fresh-$creates", deviceId = deviceId)
        }, { qr ->
            if (++checks == 1) throw PlatformApiException("需要验证")
            assertEquals("qr-2", qr.key)
            assertEquals("qrsig=fresh-2", qr.credentialSeed)
            assertEquals("verify=passed", qr.verificationCredential)
            success()
        }, { _, _, _ -> }, { _, _ -> done.complete(Unit) }, { _, _ -> paused.complete(Unit); true }, pollIntervalMs = 1)
        try {
            controller.start(MusicSource.QQ, PlatformLoginMethod.QR_CODE, "device")
            withTimeout(2_000) { paused.await() }
            assertTrue(controller.restartAfterVerification("verify=passed"))
            withTimeout(2_000) { done.await() }
            assertEquals(2, creates)
        } finally { controller.stop() }
    }
}
