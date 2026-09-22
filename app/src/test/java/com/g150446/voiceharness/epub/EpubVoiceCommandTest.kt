package com.g150446.voiceharness.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubVoiceCommandTest {
    private val titles = listOf(
        "はじめに",
        "第一章 出会い",
        "第二章 旅立ち",
        "第十章 帰郷",
        "あとがき",
        "参考文献",
        "参考資料",
    )

    private fun parse(
        text: String,
        view: EpubView = EpubView.TEXT,
        candidates: List<Int> = emptyList(),
    ) = parseEpubCommand(text, titles, view, candidates)

    @Test
    fun `table of contents words open the outline`() {
        assertEquals(EpubVoiceCommand.OpenToc, parse("目次"))
        assertEquals(EpubVoiceCommand.OpenToc, parse("目次を開いて"))
        assertEquals(EpubVoiceCommand.OpenToc, parse("もくじ"))
        assertEquals(EpubVoiceCommand.OpenToc, parse("アウトラインを見せて"))
    }

    @Test
    fun `numbered entries open by position, in digits or kanji`() {
        assertEquals(EpubVoiceCommand.OpenEntry(2), parse("3番"))
        assertEquals(EpubVoiceCommand.OpenEntry(2), parse("三番目"))
        assertEquals(EpubVoiceCommand.OpenEntry(0), parse("１番を開いて"))
        assertEquals(EpubVoiceCommand.OpenEntry(3), parse("四番目の項目"))
        assertEquals(EpubVoiceCommand.OpenEntry(2), parse("3ばん"))
    }

    @Test
    fun `an out of range number is not found rather than guessed`() {
        assertEquals(EpubVoiceCommand.NotFound("99番"), parse("99番"))
        assertEquals(EpubVoiceCommand.NotFound("0番"), parse("0番"))
    }

    @Test
    fun `a bare number opens an entry only while the outline is showing`() {
        assertEquals(EpubVoiceCommand.OpenEntry(4), parse("5", EpubView.TOC))
        assertEquals(EpubVoiceCommand.OpenEntry(4), parse("五", EpubView.TOC))
        assertTrue(parse("5", EpubView.TEXT) !is EpubVoiceCommand.OpenEntry)
    }

    @Test
    fun `numbers pick from the candidate list while candidates are showing`() {
        val candidates = listOf(5, 6)
        assertEquals(EpubVoiceCommand.OpenEntry(6), parse("2番", EpubView.CANDIDATES, candidates))
        assertEquals(EpubVoiceCommand.OpenEntry(5), parse("1", EpubView.CANDIDATES, candidates))
        assertEquals(EpubVoiceCommand.NotFound("3"), parse("3", EpubView.CANDIDATES, candidates))
    }

    @Test
    fun `a title opens its entry, with speech filler ignored`() {
        assertEquals(EpubVoiceCommand.OpenEntry(0), parse("はじめに"))
        assertEquals(EpubVoiceCommand.OpenEntry(0), parse("はじめにを開いて"))
        assertEquals(EpubVoiceCommand.OpenEntry(4), parse("あとがきに移動して"))
        assertEquals(EpubVoiceCommand.OpenEntry(4), parse("アトガキを開いてください"))
    }

    @Test
    fun `chapter numbers match the title in digits, kanji or full width`() {
        assertEquals(EpubVoiceCommand.OpenEntry(1), parse("第一章"))
        assertEquals(EpubVoiceCommand.OpenEntry(1), parse("第1章を開いて"))
        assertEquals(EpubVoiceCommand.OpenEntry(2), parse("第２章"))
        assertEquals(EpubVoiceCommand.OpenEntry(3), parse("第十章"))
        assertEquals(EpubVoiceCommand.OpenEntry(3), parse("第10章"))
    }

    @Test
    fun `a partial title that fits one entry opens it`() {
        assertEquals(EpubVoiceCommand.OpenEntry(1), parse("出会い"))
        assertEquals(EpubVoiceCommand.OpenEntry(2), parse("旅立ち"))
    }

    @Test
    fun `entries that fit equally well are offered as numbered candidates`() {
        val result = parse("参考")
        assertTrue(result is EpubVoiceCommand.Ambiguous)
        assertEquals(listOf(5, 6), (result as EpubVoiceCommand.Ambiguous).candidates)
    }

    @Test
    fun `an unknown title is reported with what was heard`() {
        assertEquals(EpubVoiceCommand.NotFound("宇宙戦争を開いて"), parse("宇宙戦争を開いて"))
    }

    @Test
    fun `page commands need the page word or are short bare phrases`() {
        assertEquals(EpubVoiceCommand.NextPage(1), parse("次のページ"))
        assertEquals(EpubVoiceCommand.PrevPage(1), parse("ページ戻して"))
        assertEquals(EpubVoiceCommand.NextPage(3), parse("3ページ進めて"))
        assertEquals(EpubVoiceCommand.PrevPage(2), parse("2ページ戻って"))
        assertEquals(EpubVoiceCommand.NextPage(1), parse("次"))
        assertEquals(EpubVoiceCommand.PrevPage(1), parse("戻る"))
    }

    @Test
    fun `chapter commands are not read as page or title commands`() {
        assertEquals(EpubVoiceCommand.NextChapter, parse("次の章"))
        assertEquals(EpubVoiceCommand.PrevChapter, parse("前の章へ"))
        assertEquals(EpubVoiceCommand.NextChapter, parse("次の章に進んで"))
    }

    @Test
    fun `a chapter number is not read as a chapter step`() {
        assertEquals(EpubVoiceCommand.OpenEntry(2), parse("第二章"))
    }

    @Test
    fun `empty speech and books without an outline are unknown`() {
        assertEquals(EpubVoiceCommand.Unknown, parse("  "))
        assertEquals(EpubVoiceCommand.Unknown, parseEpubCommand("はじめに", emptyList()))
        assertEquals(EpubVoiceCommand.NextPage(1), parseEpubCommand("次のページ", emptyList()))
    }

    @Test
    fun `normalization folds width, kana and kanji numerals`() {
        assertEquals("第2章であい", normalizeForMatch("第二章 デアイ!"))
        assertEquals("第10章", normalizeForMatch("第１０章"))
        assertEquals("第12章", normalizeForMatch("第十二章"))
        assertEquals("第120章", normalizeForMatch("第百二十章"))
    }

    @Test
    fun `title score prefers exact over contained over similar`() {
        assertEquals(1.0, titleScore("はじめに", "はじめに"), 0.0)
        assertTrue(titleScore("第2章であい", "であい") > 0.9)
        assertTrue(titleScore("あとがき", "あとがき集") in 0.8..0.9)
        assertEquals(0.0, titleScore("あ", "い"), 0.0)
    }
}
