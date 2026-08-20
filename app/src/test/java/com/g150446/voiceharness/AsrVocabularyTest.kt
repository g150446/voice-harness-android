package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrVocabularyTest {

    @Test
    fun builtInIncludesChiikawaCast() {
        val forms = AsrVocabularyCatalog.builtIn.map { it.writtenForm }
        assertTrue(forms.contains("ちいかわ"))
        assertTrue(forms.contains("ハチワレ"))
        assertTrue(forms.contains("うさぎ"))
    }

    @Test
    fun parseExtraLinesSupportsTabFieldsAndComments() {
        val terms = AsrVocabularyCatalog.parseExtraLines(
            listOf(
                "# comment",
                "",
                "モモンガ\tMomonga\tChiikawa character",
                "くりまんじゅう"
            )
        )
        assertEquals(2, terms.size)
        assertEquals("モモンガ", terms[0].writtenForm)
        assertEquals("Momonga", terms[0].spokenHint)
        assertEquals("Chiikawa character", terms[0].note)
        assertEquals("くりまんじゅう", terms[1].writtenForm)
    }

    @Test
    fun parseExtraLinesDedupesWrittenForms() {
        val terms = AsrVocabularyCatalog.parseExtraLines(
            listOf("ちいかわ\tfirst", "ちいかわ\tsecond")
        )
        assertEquals(1, terms.size)
        assertEquals("first", terms[0].spokenHint)
    }

    @Test
    fun promptSectionListsPreferredSpellings() {
        val section = AsrVocabularyCatalog.promptSection(AsrVocabularyCatalog.builtIn)
        assertNotNull(section)
        assertTrue(section!!.contains("Preferred spellings"))
        assertTrue(section.contains("ちいかわ"))
        assertTrue(section.contains("ハチワレ"))
        assertTrue(section.contains("うさぎ"))
        assertTrue(section.contains("similar-sounding"))
    }

    @Test
    fun promptSectionRespectsMaxTerms() {
        val many = (1..AsrVocabularyCatalog.MAX_TERMS_IN_PROMPT + 5).map {
            AsrVocabularyTerm("語$it")
        }
        val section = AsrVocabularyCatalog.promptSection(many)!!
        assertTrue(section.contains("語1"))
        assertTrue(section.contains("語${AsrVocabularyCatalog.MAX_TERMS_IN_PROMPT}"))
        assertFalse(section.contains("語${AsrVocabularyCatalog.MAX_TERMS_IN_PROMPT + 1}"))
    }
}
