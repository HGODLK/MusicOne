package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

class PlatformErrorMessagesTest {
    @Test fun certificateHostnameFailureDoesNotExposeCertificateDomainList() {
        val error = SSLPeerUnverifiedException("Hostname mobilecdn.kugou.com not verified; subjectAltNames: [*.example.com]")

        assertEquals("平台接口证书与域名不匹配，请稍后重试", error.asUserMessage())
    }
}
