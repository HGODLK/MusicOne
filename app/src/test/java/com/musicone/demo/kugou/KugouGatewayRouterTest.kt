package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouGatewayRouterTest {
    // 路径已带 cloudlist.service 前缀时不能再带 x-router，否则网关二次拼接服务前缀后返回 404。
    @Test fun servicePrefixedRoutesAreResolvedByPathOnly() {
        assertNull(kugouGatewayRouter("https://gateway.kugou.com/cloudlist.service/v6/add_song"))
        assertNull(kugouGatewayRouter("https://gateway.kugou.com/cloudlist.service/v1/modify_list_sort"))
    }

    @Test fun routesWithoutServicePrefixKeepTheCloudListRouter() {
        assertEquals(KUGOU_CLOUD_LIST_ROUTER, kugouGatewayRouter("https://gateway.kugou.com/v4/delete_songs"))
        assertEquals(KUGOU_CLOUD_LIST_ROUTER, kugouGatewayRouter("https://gateway.kugou.com/v4/get_list_all_file"))
        assertEquals(KUGOU_CLOUD_LIST_ROUTER, kugouGatewayRouter("https://gateway.kugou.com/v7/get_all_list"))
    }

    @Test fun queryStringsAndBarePathsDoNotChangeTheDecision() {
        assertEquals(KUGOU_CLOUD_LIST_ROUTER,
            kugouGatewayRouter("https://gateway.kugou.com/v4/get_list_all_file?dfid=-&mid=1"))
        assertNull(kugouGatewayRouter("/cloudlist.service/v6/add_song?dfid=-&mid=1"))
        assertEquals(KUGOU_CLOUD_LIST_ROUTER, kugouGatewayRouter("/v4/delete_songs"))
    }
}
