package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A preferred spelling for Gemma multimodal ASR.
 *
 * Add product defaults in [AsrVocabularyCatalog.builtIn].
 * Optional runtime extras: [EXTRA_FILE_NAME] under the app files directory.
 */
data class AsrVocabularyTerm(
    val writtenForm: String,
    val spokenHint: String? = null,
    val note: String? = null,
    val triggerKeywords: List<String> = emptyList()
) {
    fun toPromptBullet(): String = buildString {
        append("- ")
        append(writtenForm)
        val extras = listOfNotNull(
            spokenHint?.takeIf { it.isNotBlank() },
            note?.takeIf { it.isNotBlank() }
        )
        if (extras.isNotEmpty()) {
            append(" (")
            append(extras.joinToString(", "))
            append(")")
        }
    }
}

object AsrVocabularyCatalog {
    private const val TAG = "AsrVocabulary"
    const val EXTRA_FILE_NAME = "asr_vocabulary_extra.txt"
    const val MAX_TERMS_IN_PROMPT = 40
    const val MAX_PROMPT_SECTION_CHARS = 800

    private val chiikawaTriggers = listOf("アニメ", "あにめ", "anime")

    /**
     * Built-in terms. Chiikawa names are omitted from the first ASR pass
     * and attached only when the transcript contains a trigger such as アニメ.
     */
    val builtIn: List<AsrVocabularyTerm> = listOf(
        AsrVocabularyTerm("ちいかわ", "Chiikawa", "anime character", chiikawaTriggers),
        AsrVocabularyTerm("ハチワレ", "Hachiware", "Chiikawa character", chiikawaTriggers),
        AsrVocabularyTerm("うさぎ", "Usagi", "Chiikawa character", chiikawaTriggers)
    )

    fun all(context: Context? = null): List<AsrVocabularyTerm> {
        val extras = context?.let { loadExtras(it) }.orEmpty()
        return mergePreferringFirst(builtIn + extras)
    }

    fun loadExtras(context: Context): List<AsrVocabularyTerm> {
        val file = File(context.applicationContext.filesDir, EXTRA_FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching {
            parseExtraLines(file.readLines())
        }.onFailure { error ->
            Log.w(TAG, "Failed to read $EXTRA_FILE_NAME", error)
        }.getOrDefault(emptyList())
    }

    /**
     * Parses extra vocabulary lines.
     *
     * Formats (blank lines and `#` comments ignored):
     * - `writtenForm`
     * - `writtenForm<TAB>spokenHint`
     * - `writtenForm<TAB>spokenHint<TAB>note`
     */
    fun parseExtraLines(lines: Iterable<String>): List<AsrVocabularyTerm> {
        val terms = ArrayList<AsrVocabularyTerm>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            terms += AsrVocabularyTerm(
                writtenForm = parts[0],
                spokenHint = parts.getOrNull(1),
                note = parts.getOrNull(2)
            )
        }
        return mergePreferringFirst(terms)
    }

    /** Terms with no trigger: safe to put on the first ASR pass. */
    fun firstPassTerms(terms: List<AsrVocabularyTerm>): List<AsrVocabularyTerm> =
        terms.filter { it.triggerKeywords.isEmpty() }

    fun containsTrigger(text: String, terms: List<AsrVocabularyTerm> = builtIn): Boolean =
        terms.asSequence()
            .flatMap { it.triggerKeywords.asSequence() }
            .any { keyword -> triggerMatches(text, keyword) }

    /**
     * True when a trigger was heard and at least one gated name is still missing,
     * so a second ASR pass with those spellings is worth running.
     */
    fun shouldRetryWithTriggeredTerms(
        firstPassText: String,
        terms: List<AsrVocabularyTerm>
    ): Boolean {
        val gated = terms.filter { it.triggerKeywords.isNotEmpty() }
        if (gated.isEmpty() || !containsTrigger(firstPassText, gated)) return false
        return gated.none { term -> firstPassText.contains(term.writtenForm) }
    }

    fun retryTerms(firstPassText: String, terms: List<AsrVocabularyTerm>): List<AsrVocabularyTerm> {
        val ungated = firstPassTerms(terms)
        val gated = terms.filter { term ->
            term.triggerKeywords.isNotEmpty() &&
                term.triggerKeywords.any { triggerMatches(firstPassText, it) }
        }
        return mergePreferringFirst(ungated + gated)
    }

    fun triggerMatches(text: String, keyword: String): Boolean {
        val needle = normalizeTrigger(keyword)
        if (needle.isEmpty()) return false
        return normalizeTrigger(text).contains(needle)
    }

    private fun normalizeTrigger(text: String): String = text.lowercase()

    fun promptSection(terms: List<AsrVocabularyTerm>): String? {
        if (terms.isEmpty()) return null
        val limited = terms.take(MAX_TERMS_IN_PROMPT)
        val header =
            "Preferred spellings for names that may appear in the speech " +
                "(use exactly these written forms when heard):\n"
        val bullets = limited.joinToString("\n") { it.toPromptBullet() }
        val footer =
            "\nDo not replace these with similar-sounding alternatives. " +
                "Use a name only if it was clearly spoken. Do not list unused vocabulary."
        var section = header + bullets + footer
        if (section.length > MAX_PROMPT_SECTION_CHARS) {
            section = section.take(MAX_PROMPT_SECTION_CHARS).trimEnd()
        }
        return section
    }

    private fun mergePreferringFirst(terms: List<AsrVocabularyTerm>): List<AsrVocabularyTerm> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<AsrVocabularyTerm>(terms.size)
        for (term in terms) {
            val key = term.writtenForm.trim()
            if (key.isEmpty() || !seen.add(key)) continue
            out += term.copy(writtenForm = key)
        }
        return out
    }
}
