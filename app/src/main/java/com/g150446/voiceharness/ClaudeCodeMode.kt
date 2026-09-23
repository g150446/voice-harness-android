package com.g150446.voiceharness

import java.util.Locale

/**
 * A Claude Code permission mode, as ⇧Tab cycles through them.
 *
 * The cycle order and even the set of modes depend on how the session was started — a session
 * without `--dangerously-skip-permissions` never reaches [BYPASS_PERMISSIONS] — so nothing here
 * assumes a fixed order or a fixed number of presses.
 */
internal enum class ClaudeCodeMode(val wire: String, val label: String) {
    NORMAL("normal", "通常"),
    ACCEPT_EDITS("accept_edits", "自動編集"),
    PLAN("plan", "プラン"),
    AUTO("auto", "オート"),
    DONT_ASK("dont_ask", "自動拒否"),
    BYPASS_PERMISSIONS("bypass_permissions", "権限スキップ");

    companion object {
        fun fromWire(raw: String?): ClaudeCodeMode? {
            val value = raw?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("[\\s+_-]+"), "")
            if (value.isNullOrEmpty()) return null
            return entries.firstOrNull { it.wire.replace("_", "") == value }
                ?: ALIASES[value]
        }

        private val ALIASES = mapOf(
            "planmode" to PLAN,
            "planning" to PLAN,
            "プラン" to PLAN,
            "プランモード" to PLAN,
            "計画" to PLAN,
            "acceptedits" to ACCEPT_EDITS,
            "autoaccept" to ACCEPT_EDITS,
            "autoacceptedits" to ACCEPT_EDITS,
            "自動編集" to ACCEPT_EDITS,
            "編集許可" to ACCEPT_EDITS,
            "automode" to AUTO,
            "オート" to AUTO,
            "dontask" to DONT_ASK,
            "自動拒否" to DONT_ASK,
            "bypasspermissions" to BYPASS_PERMISSIONS,
            "bypass" to BYPASS_PERMISSIONS,
            "権限スキップ" to BYPASS_PERMISSIONS,
            "default" to NORMAL,
            "manual" to NORMAL,
            "manualmode" to NORMAL,
            "通常" to NORMAL,
            "通常モード" to NORMAL,
        )
    }
}

/**
 * The mode Claude Code's footer is currently showing, or null when the screen does not say.
 *
 * Null is a real answer, not a failure: while the agent is working its input box — and with it
 * the mode line — is off the screen entirely. A caller that pressed ⇧Tab on a guess there would
 * be cycling blind, which is the thing this file exists to stop.
 *
 * Only the bottom of the screen counts, and a mode line only counts when it *begins* the line
 * once the ⏸ / ⏵⏵ glyph is stripped. The agent says "plan mode" in ordinary conversation often
 * enough that a looser search would read it talking about plan mode as being in it.
 */
internal fun readClaudeCodeMode(screenText: String): ClaudeCodeMode? {
    val tail = filterHarborDisplayText(screenText)
        .lineSequence()
        .toList()
        .takeLast(MODE_FOOTER_LINES)
        // The typographic apostrophe in "don't ask" is one character in the TUI and another
        // in this file; folding it means the table can be written the obvious way.
        .map { it.lowercase(Locale.ROOT).replace('’', '\'') }
    var footerSeen = false
    for (line in tail.asReversed()) {
        // Leading glyphs and box borders are not letters; the agent's own bullets (●, ⎿) go
        // the same way, leaving its Japanese text starting with a letter and matching nothing.
        val stripped = line.dropWhile { !it.isLetterOrDigit() }
        MODE_MARKERS.firstOrNull { (marker, _) -> stripped.startsWith(marker) }
            ?.let { return it.second }
        if (FOOTER_MARKERS.any(line::contains)) footerSeen = true
    }
    // The footer is up but naming no mode: that is what normal mode looks like.
    return if (footerSeen) ClaudeCodeMode.NORMAL else null
}

/**
 * How Claude Code's footer starts, lowercased. Read off the 2.1.280 binary, which carries
 * "plan mode on", "accept edits on" and "auto mode on" verbatim. Kept in one place because the
 * wording moves between releases: when a version renames a mode line, correct it here and
 * nowhere else. An unlisted mode reads as unknown, which refuses the change rather than
 * cycling past it.
 */
private val MODE_MARKERS = listOf(
    "plan mode on" to ClaudeCodeMode.PLAN,
    "accept edits on" to ClaudeCodeMode.ACCEPT_EDITS,
    "auto mode on" to ClaudeCodeMode.AUTO,
    "don't ask on" to ClaudeCodeMode.DONT_ASK,
    "bypass permissions on" to ClaudeCodeMode.BYPASS_PERMISSIONS,
    "bypassing permissions" to ClaudeCodeMode.BYPASS_PERMISSIONS,
    "manual mode on" to ClaudeCodeMode.NORMAL,
)

/**
 * Evidence the input box is on screen at all, which is what tells normal mode from unknown.
 * Matched loosely: the cost of a wrong read here is one ⇧Tab that the next read corrects,
 * whereas missing the footer entirely refuses a mode change that was perfectly possible.
 */
private val FOOTER_MARKERS = listOf(
    "shift+tab to cycle",
    "? for shortcuts",
)

private const val MODE_FOOTER_LINES = 12

/** The terminal side of a mode change, so [cycleToClaudeMode] itself makes no network calls. */
internal interface ClaudeModeCycler {
    /** The mode the screen shows now, or null when it does not say. */
    fun readMode(): ClaudeCodeMode?

    /** One ⇧Tab. */
    fun pressCycleKey()

    /** Wait for the agent to redraw. */
    fun settle()
}

internal data class ClaudeModeCycleResult(val mode: ClaudeCodeMode, val presses: Int)

/**
 * Presses ⇧Tab until the screen shows [target], reading the screen after every press.
 *
 * Nothing here counts presses in advance. It stops as soon as the target is on screen, refuses
 * to start when the current mode cannot be read, and gives up when the cycle comes back around
 * to a mode it has already seen — which is what a session that simply has no [target] looks
 * like from the outside.
 */
internal fun cycleToClaudeMode(
    target: ClaudeCodeMode,
    cycler: ClaudeModeCycler,
    maxPresses: Int = MAX_MODE_PRESSES,
): ClaudeModeCycleResult {
    val start = cycler.readMode() ?: error(
        "画面から現在のモードを判定できません。ターミナルの入力欄が見える状態でもう一度お願いします",
    )
    if (start == target) return ClaudeModeCycleResult(start, 0)

    val seen = mutableSetOf(start)
    var current = start
    for (presses in 1..maxPresses) {
        cycler.pressCycleKey()
        cycler.settle()
        // A single missed read is usually just a redraw that has not landed yet; a second one
        // means the input box is gone, and guessing past that is how you overshoot the cycle.
        current = cycler.readMode() ?: run {
            cycler.settle()
            cycler.readMode()
        } ?: error(
            "⇧Tabを${presses}回送ったところで画面からモードを判定できなくなりました。" +
                "ターミナルを確認してください",
        )
        if (current == target) return ClaudeModeCycleResult(current, presses)
        if (!seen.add(current)) break
    }
    error(
        "このセッションでは${target.label}モードに切り替えられません" +
            "（${current.label}モードのままです）",
    )
}

/**
 * A backstop only — [cycleToClaudeMode] normally stops when the cycle repeats a mode. One lap
 * of every mode there is, plus one, so a session that gains a mode later still gets its lap.
 */
internal val MAX_MODE_PRESSES = ClaudeCodeMode.entries.size + 1
