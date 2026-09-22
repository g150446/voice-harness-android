package com.g150446.voiceharness.epub

import java.io.File
import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** EPUB 2/3 container → OPF → spine and outline (EPUB3 nav preferred, EPUB2 NCX as fallback). */
internal object EpubParser {
    private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val FONT_OBFUSCATION = "http://www.idpf.org/2008/embedding"
    private const val FONT_OBFUSCATION_ADOBE = "http://ns.adobe.com/pdf/enc#RC"

    fun parse(file: File, id: String): EpubBook = EpubArchive(file).use { archive ->
        rejectDrm(archive)
        val containerXml = archive.read("META-INF/container.xml")
            ?: throw EpubException("EPUBではありません（container.xmlがありません）")
        val opfPath = xml(containerXml).selectFirst("rootfile[full-path]")?.attr("full-path")
            ?.let(::decodePath)
            ?.takeIf(String::isNotBlank)
            ?: throw EpubException("EPUBの構成情報を読み取れません")
        val opf = xml(archive.read(opfPath) ?: throw EpubException("EPUBの構成情報がありません"))

        val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
        val navPath = manifest.values
            .firstOrNull { "nav" in it.attr("properties").split(' ') }
            ?.let { resolve(opfPath, it.attr("href")) }
        val spine = opf.select("spine > itemref").mapNotNull { ref ->
            val item = manifest[ref.attr("idref")] ?: return@mapNotNull null
            val type = item.attr("media-type")
            val path = resolve(opfPath, item.attr("href"))
            if (type != "application/xhtml+xml" && type != "text/html") return@mapNotNull null
            // The navigation document is shown as our own numbered outline, not as a chapter.
            if (path == navPath) return@mapNotNull null
            if (!archive.has(path)) return@mapNotNull null
            SpineItem(path = path, id = ref.attr("idref"))
        }
        if (spine.isEmpty()) throw EpubException("本文が見つかりません")

        val spineIndexByPath = spine.mapIndexed { index, item -> item.path to index }.toMap()
        val toc = parseNav(archive, navPath, spineIndexByPath).ifEmpty {
            val ncxId = opf.selectFirst("spine[toc]")?.attr("toc").orEmpty()
            val ncxPath = manifest[ncxId]?.let { resolve(opfPath, it.attr("href")) }
                ?: manifest.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                    ?.let { resolve(opfPath, it.attr("href")) }
            parseNcx(archive, ncxPath, spineIndexByPath)
        }

        val title = opf.selectFirst("metadata > dc|title, metadata > title")?.text()?.trim()
            .orEmpty().ifEmpty { file.nameWithoutExtension }
        val author = opf.select("metadata > dc|creator, metadata > creator")
            .joinToString("、") { it.text().trim() }
        EpubBook(id = id, title = title, author = author, spine = spine, toc = toc, file = file)
    }

    private fun rejectDrm(archive: EpubArchive) {
        val encryption = archive.read(ENCRYPTION_PATH) ?: return
        val algorithms = Regex("Algorithm=\"([^\"]+)\"").findAll(encryption).map { it.groupValues[1] }.toList()
        val onlyFonts = algorithms.isNotEmpty() &&
            algorithms.all { it == FONT_OBFUSCATION || it == FONT_OBFUSCATION_ADOBE }
        if (!onlyFonts && encryption.contains("EncryptedData")) {
            throw EpubException("DRM保護されたEPUBは読み込めません")
        }
    }

    private fun parseNav(
        archive: EpubArchive,
        navPath: String?,
        spineIndexByPath: Map<String, Int>,
    ): List<TocEntry> {
        val html = navPath?.let(archive::read) ?: return emptyList()
        val document = Jsoup.parse(html)
        val nav = document.select("nav").firstOrNull { "toc" in it.attr("epub:type").split(' ') }
            ?: document.selectFirst("nav")
            ?: return emptyList()
        val entries = ArrayList<TocEntry>()
        nav.selectFirst("ol, ul")?.let { walkList(it, 0, navPath, spineIndexByPath, entries) }
        return entries
    }

    private fun walkList(
        list: Element,
        depth: Int,
        navPath: String,
        spineIndexByPath: Map<String, Int>,
        out: MutableList<TocEntry>,
    ) {
        for (item in list.children().filter { it.normalName() == "li" }) {
            val label = item.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
            val title = label?.text()?.trim().orEmpty()
            if (label != null && label.normalName() == "a" && title.isNotEmpty()) {
                toEntry(title, depth, label.attr("href"), navPath, spineIndexByPath)?.let(out::add)
            }
            item.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }
                ?.let { walkList(it, depth + 1, navPath, spineIndexByPath, out) }
        }
    }

    private fun parseNcx(
        archive: EpubArchive,
        ncxPath: String?,
        spineIndexByPath: Map<String, Int>,
    ): List<TocEntry> {
        val xml = ncxPath?.let(archive::read) ?: return emptyList()
        val entries = ArrayList<TocEntry>()
        val navMap = xml(xml).selectFirst("navMap") ?: return emptyList()
        fun walk(parent: Element, depth: Int) {
            for (point in parent.children().filter { it.normalName() == "navpoint" }) {
                val title = point.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                val src = point.selectFirst("content")?.attr("src").orEmpty()
                if (title.isNotEmpty()) {
                    toEntry(title, depth, src, ncxPath!!, spineIndexByPath)?.let(entries::add)
                }
                walk(point, depth + 1)
            }
        }
        walk(navMap, 0)
        return entries
    }

    private fun toEntry(
        title: String,
        depth: Int,
        href: String,
        basePath: String,
        spineIndexByPath: Map<String, Int>,
    ): TocEntry? {
        if (href.isBlank()) return null
        val path = resolve(basePath, href.substringBefore('#'))
        val index = spineIndexByPath[path] ?: return null
        val anchor = href.substringAfter('#', "").takeIf(String::isNotEmpty)?.let(::decodePath)
        return TocEntry(title = title, depth = depth, spineIndex = index, anchor = anchor)
    }

    /** Resolves [href] against the directory of [base] inside the zip, collapsing `.` and `..`. */
    fun resolve(base: String, href: String): String {
        val cleaned = decodePath(href.substringBefore('#'))
        val directory = base.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        val joined = if (cleaned.startsWith("/")) cleaned.trimStart('/') else {
            if (directory.isEmpty()) cleaned else "$directory/$cleaned"
        }
        for (part in joined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun decodePath(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        value
    }

    private fun xml(text: String) = Jsoup.parse(text, "", Parser.xmlParser())
}
