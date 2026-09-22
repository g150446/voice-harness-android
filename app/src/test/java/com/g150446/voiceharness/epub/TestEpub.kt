package com.g150446.voiceharness.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUB files for tests. */
internal object TestEpub {
    fun write(files: Map<String, String>): File {
        val file = File.createTempFile("test", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>t</title></head><body>$body</body></html>"""

    /** EPUB3: nav document, chapter 2 has an anchored second section. */
    fun epub3(): File = write(
        mapOf(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>吾輩は猫である</dc:title><dc:creator>夏目漱石</dc:creator>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="img" href="cover.png" media-type="image/png"/>
  </manifest>
  <spine><itemref idref="nav"/><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>""",
            "OEBPS/nav.xhtml" to xhtml(
                """<nav epub:type="toc"><ol>
                <li><a href="text/c1.xhtml">はじめに</a></li>
                <li><a href="text/c2.xhtml">第一章 出会い</a>
                  <ol><li><a href="text/c2.xhtml#s2">第一節 猫の名前</a></li></ol></li>
                </ol></nav>""",
            ),
            "OEBPS/text/c1.xhtml" to xhtml("<h1>はじめに</h1><p>吾輩は猫である。</p><p>名前はまだ無い。</p>"),
            "OEBPS/text/c2.xhtml" to xhtml(
                """<h1>第一章</h1><p>どこで生れたか。</p><h2 id="s2">第一節</h2><p>薄暗い所。</p>""",
            ),
        ),
    )

    /** EPUB2: NCX outline, percent-encoded href, nested navPoint. */
    fun epub2(): File = write(
        mapOf(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old Book</dc:title></metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="a" href="a%20b.xhtml" media-type="application/xhtml+xml"/>
    <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine>
</package>""",
            "OEBPS/toc.ncx" to """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
  <navPoint id="p1"><navLabel><text>Part One</text></navLabel><content src="a%20b.xhtml"/>
    <navPoint id="p2"><navLabel><text>Chapter B</text></navLabel><content src="b.xhtml#top"/></navPoint>
  </navPoint>
</navMap></ncx>""",
            "OEBPS/a b.xhtml" to xhtml("<p>Alpha</p>"),
            "OEBPS/b.xhtml" to xhtml("""<p id="top">Beta</p>"""),
        ),
    )

    fun drm(): File = write(
        mapOf(
            "META-INF/container.xml" to CONTAINER,
            "META-INF/encryption.xml" to """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>""",
        ),
    )

    /** Two chapters: the first is long (several chunks), the second has an anchored section. */
    fun long(): File = write(
        mapOf(
            "META-INF/container.xml" to CONTAINER,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Long</dc:title></metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>""",
            "OEBPS/nav.xhtml" to xhtml(
                """<nav epub:type="toc"><ol>
                <li><a href="c1.xhtml">第一章 序</a></li>
                <li><a href="c2.xhtml">第二章 本編</a></li>
                <li><a href="c2.xhtml#end">あとがき</a></li>
                <li><a href="c2.xhtml#ref1">参考文献</a></li>
                <li><a href="c2.xhtml#ref2">参考資料</a></li>
                </ol></nav>""",
            ),
            "OEBPS/c1.xhtml" to xhtml((1..12).joinToString("") { "<p>${it}段落 " + "あ".repeat(300) + "</p>" }),
            "OEBPS/c2.xhtml" to xhtml(
                """<p>本編の冒頭。</p><h2 id="end">あとがき</h2><p>おわり。</p>""" +
                    """<h2 id="ref1">参考文献</h2><p>本</p><h2 id="ref2">参考資料</h2><p>資料</p>""",
            ),
        ),
    )
}
