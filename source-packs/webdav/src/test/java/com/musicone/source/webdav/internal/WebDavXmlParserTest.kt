package com.musicone.source.webdav.internal

import org.junit.Assert.*
import org.junit.Test

class WebDavXmlParserTest {
    @Test
    fun parsesOpenListMultiStatusWithChineseNamesAndFiltersNonAudio() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/%E9%9F%B3%E4%B9%90/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>音乐</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/%E9%9F%B3%E4%B9%90/%E5%91%A8%E6%9D%B0%E4%BC%A6/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>周杰伦</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/%E9%9F%B3%E4%B9%90/01.%20%E6%99%B4%E5%A4%A9.flac</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>01. 晴天.flac</D:displayname>
                    <D:getcontentlength>35123456</D:getcontentlength>
                    <D:getetag>"abc-123"</D:getetag>
                    <D:getcontenttype>audio/flac</D:getcontenttype>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop><D:resourcetype/></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/%E9%9F%B3%E4%B9%90/01.%20%E6%99%B4%E5%A4%A9.lrc</D:href>
                <D:propstat>
                  <D:prop><D:displayname>01. 晴天.lrc</D:displayname></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/%E9%9F%B3%E4%B9%90/cover.jpg</D:href>
                <D:propstat>
                  <D:prop><D:displayname>cover.jpg</D:displayname></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/%E9%9F%B3%E4%B9%90/readme.txt</D:href>
                <D:propstat>
                  <D:prop><D:displayname>readme.txt</D:displayname></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val listing = WebDavXmlParser.parseDirectoryListing(
            xml = xml,
            baseUrl = "http://192.168.1.10:5244/dav",
            rootPath = "",
            requestedRelativePath = "/音乐",
        )

        assertEquals("/音乐", listing.path)
        assertEquals(1, listing.directories.size)
        assertEquals("/音乐/周杰伦", listing.directories.first().path)
        assertEquals(1, listing.audioFiles.size)
        val audio = listing.audioFiles.first()
        assertEquals("01. 晴天.flac", audio.name)
        assertEquals("/音乐/01. 晴天.flac", audio.path)
        assertEquals("abc-123", audio.etag)
        assertEquals(35123456L, audio.sizeBytes)
        assertNotNull(listing.lyricFilesByStem["01. 晴天"])
        assertEquals("/音乐/cover.jpg", listing.coverFile?.path)
    }

    @Test
    fun parsesFullUrlHrefAndLowercaseNamespace() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>https://dav.jianguoyun.com/dav/Music/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>https://dav.jianguoyun.com/dav/Music/Live+Concert.dsf</d:href>
                <d:propstat>
                  <d:prop><d:getcontentlength>99887766</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val listing = WebDavXmlParser.parseDirectoryListing(
            xml = xml,
            baseUrl = "https://dav.jianguoyun.com/dav",
            rootPath = "/Music",
            requestedRelativePath = "",
        )
        assertEquals(0, listing.directories.size)
        assertEquals(1, listing.audioFiles.size)
        assertEquals("Live+Concert.dsf", listing.audioFiles.first().name)
        assertEquals("/Live+Concert.dsf", listing.audioFiles.first().path)
    }
}
