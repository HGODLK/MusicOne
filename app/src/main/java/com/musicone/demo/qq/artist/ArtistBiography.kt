package com.musicone.demo

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** 详情接口包含完整正文和成员资料，不能使用首页的定长摘要替代。 */
internal fun artistBiography(xml: String): String {
    require(!xml.contains("<!DOCTYPE", ignoreCase = true))
    val factory = DocumentBuilderFactory.newInstance()
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    require(document.getElementsByTagName("code").item(0)?.textContent == "0")
    val info = document.getElementsByTagName("info").item(0) as? Element ?: return ""
    fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    fun Element.fields(): String = children().filter { it.tagName == "item" }.joinToString("\n\n") { item ->
        val key = item.getElementsByTagName("key").item(0)?.textContent.orEmpty().trim()
        val value = item.getElementsByTagName("value").item(0)?.textContent.orEmpty().trim()
        if (key.isBlank()) value else "$key：$value"
    }
    return info.children().mapNotNull { section ->
        when (section.tagName) {
            "id" -> null
            "desc" -> section.textContent.trim()
            "basic" -> section.fields().takeIf(String::isNotBlank)?.let { "基本资料\n\n$it" }
            "group" -> section.children().joinToString("\n\n") { it.fields() }.takeIf(String::isNotBlank)
                ?.let { "成员资料\n\n$it" }
            else -> section.textContent.trim().takeIf(String::isNotBlank)
        }
    }.filter(String::isNotBlank).joinToString("\n\n")
}
