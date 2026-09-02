package com.g150446.voiceharness.assistant

/**
 * Foldables (e.g. razr) can deliver multiple AssistStructure callbacks in one
 * headless session: the focused app first, then a secondary-display launcher.
 * Always keep the higher-value candidate instead of last-write-wins.
 */
internal object AssistCandidateSelector {
    data class Candidate(
        val text: String?,
        val sourcePackage: String?,
        val sourceUri: String?,
    )

    fun shouldReplace(current: Candidate?, incoming: Candidate): Boolean {
        if (incoming.text.isNullOrBlank() && incoming.sourcePackage.isNullOrBlank()) {
            return false
        }
        if (current == null) return true
        if (current.text.isNullOrBlank() && incoming.text?.isNotBlank() == true) return true
        return score(incoming) > score(current)
    }

    fun score(candidate: Candidate): Int {
        val textLen = candidate.text?.trim()?.length ?: 0
        val pkg = candidate.sourcePackage.orEmpty().lowercase()
        var score = textLen
        when {
            isLowValuePackage(pkg) -> score -= LOW_VALUE_PENALTY
            isHighValuePackage(pkg) -> score += HIGH_VALUE_BONUS
        }
        return score
    }

    private fun isLowValuePackage(pkg: String): Boolean =
        LOW_VALUE_MARKERS.any { pkg.contains(it) }

    private fun isHighValuePackage(pkg: String): Boolean =
        HIGH_VALUE_MARKERS.any { pkg.contains(it) }

    private const val LOW_VALUE_PENALTY = 10_000
    private const val HIGH_VALUE_BONUS = 5_000

    private val LOW_VALUE_MARKERS = listOf(
        "secondarydisplay",
        "systemui",
        "launcher",
        "nexuslauncher",
        "android.launcher",
    )

    private val HIGH_VALUE_MARKERS = listOf(
        "kindle",
        "books",
        "reader",
        "ebook",
        "chrome",
        "browser",
        "firefox",
        "webview",
    )
}
