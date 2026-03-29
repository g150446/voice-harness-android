package com.g150446.voiceharness

import java.util.Locale

object SpeechLanguageResolver {

    fun resolvePreferredLanguageCode(whisperLanguageCode: String?, transcribedText: String): String? =
        normalizeLanguageCode(whisperLanguageCode) ?: detectLanguageCodeFromText(transcribedText)

    fun candidateLocales(languageCode: String?, fallbackLocale: Locale = Locale.getDefault()): List<Locale> {
        val locales = buildList {
            normalizeLanguageCode(languageCode)?.let { normalized ->
                add(Locale.forLanguageTag(normalized))
                val baseLanguage = normalized.substringBefore('-')
                if (baseLanguage != normalized) {
                    add(Locale.forLanguageTag(baseLanguage))
                }
            }
            add(fallbackLocale)
        }

        return locales
            .filter { it.language.isNotBlank() }
            .distinctBy { it.toLanguageTag().lowercase(Locale.ROOT) }
    }

    private fun detectLanguageCodeFromText(text: String): String? {
        var hasLatinScript = false

        for (char in text) {
            when (Character.UnicodeScript.of(char.code)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> return "ja"
                Character.UnicodeScript.HANGUL -> return "ko"
                Character.UnicodeScript.CYRILLIC -> return "ru"
                Character.UnicodeScript.LATIN -> hasLatinScript = true
                else -> Unit
            }
        }

        return if (hasLatinScript) "en" else null
    }

    private fun normalizeLanguageCode(languageCode: String?): String? =
        languageCode
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.ifBlank { null }
}
