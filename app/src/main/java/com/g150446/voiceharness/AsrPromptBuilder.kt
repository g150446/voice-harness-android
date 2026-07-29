package com.g150446.voiceharness

/**
 * Builds Gemma multimodal ASR text prompts from [SpeechBaseLanguage].
 */
object AsrPromptBuilder {
    fun build(baseLanguage: SpeechBaseLanguage): String {
        val languageBlock = when (baseLanguage) {
            SpeechBaseLanguage.JAPANESE ->
                "The primary language of the speech is Japanese. " +
                    "Transcribe in Japanese. " +
                    "The speaker may occasionally insert English words, brand names, or loanwords; " +
                    "keep those English fragments as spoken and do not translate the Japanese parts into English."
            SpeechBaseLanguage.ENGLISH ->
                "The primary language of the speech is English. " +
                    "Transcribe in English. " +
                    "Occasional non-English proper nouns may appear; keep them as spoken."
            SpeechBaseLanguage.AUTO ->
                "The speech is either Japanese or English. " +
                    "Transcribe in the language that was actually spoken. " +
                    "Do not translate between Japanese and English."
        }

        return buildString {
            append("Transcribe the following speech segment. ")
            append(languageBlock)
            append(" Follow these specific instructions for formatting the answer:\n")
            append("* Only output the transcription, with no newlines.\n")
            append("* Do not output timestamps, labels, speaker tags, or commentary.\n")
            append("* If there is no clear human speech (silence, music, or background noise only), ")
            append("output an empty string and nothing else.\n")
            append("* Do not invent words or digits when speech is unclear.\n")
            append("* When transcribing numbers spoken by a person, write the digits, i.e. write 1.7 and not ")
            append("one point seven, and write 3 instead of three.")
        }
    }
}
