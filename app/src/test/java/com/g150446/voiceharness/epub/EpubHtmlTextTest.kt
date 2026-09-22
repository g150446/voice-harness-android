package com.g150446.voiceharness.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubHtmlTextTest {
    private fun text(body: String) = EpubHtmlText.extract("<html><body>$body</body></html>")

    @Test
    fun `paragraphs and headings are separated by a blank line`() {
        assertEquals("題\n\n一段落\n\n二段落", text("<h1>題</h1><p>一段落</p><p>二段落</p>").text)
    }

    @Test
    fun `br is a single newline inside a paragraph`() {
        assertEquals("一行目\n二行目", text("<p>一行目<br/>二行目</p>").text)
    }

    @Test
    fun `ruby keeps the base text only`() {
        val result = text("<p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>を読む</p>").text
        assertEquals("漢字を読む", result)
    }

    @Test
    fun `script style and images are dropped and nbsp becomes a space`() {
        val result = text("<style>p{}</style><p>a&nbsp;b<img src=\"x.png\"/></p><script>x()</script>").text
        assertEquals("a b", result)
    }

    @Test
    fun `whitespace in the markup is collapsed`() {
        assertEquals("one two", text("<p>one\n   two</p>").text)
    }

    @Test
    fun `ideographic space at a paragraph start is kept`() {
        assertTrue(text("<p>　吾輩は猫である。</p>").text.startsWith("　吾輩"))
    }

    @Test
    fun `anchors record where each element id starts`() {
        val result = text("<p>前</p><h2 id=\"s2\">節</h2><p id=\"tail\">後</p>")
        assertEquals("前\n\n節\n\n後", result.text)
        assertEquals(3, result.anchors["s2"])
        assertEquals(result.text.indexOf("後"), result.anchors["tail"])
    }

    @Test
    fun `an anchor on an empty element points at the next text`() {
        val result = text("<p>前</p><span id=\"mark\"></span><p>次</p>")
        assertEquals(result.text.indexOf("次"), result.anchors["mark"])
    }

    @Test
    fun `empty documents give empty text`() {
        assertEquals("", text("").text)
        assertFalse(text("<p> </p>").text.isNotEmpty())
    }
}
