package com.g150446.voiceharness

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val HARBOR_COMMAND_TOOL_NAME = "harbor_command"

/** Pause inserted between steps so a TUI has finished with one key before the next arrives. */
internal const val HARBOR_STEP_DELAY_MS = 300

/**
 * Pause after a ⇧Tab before reading the mode back. Longer than [HARBOR_STEP_DELAY_MS] because a
 * signed `/screen` round trip follows it, and a screen fetched mid-repaint reads as unknown.
 */
internal const val HARBOR_MODE_SETTLE_MS = 500

internal enum class HarborCommandAction {
    INSTRUCTION,
    KEY,
    MODE,
    SWITCH_WORKSPACE,
}

internal enum class HarborStepAction {
    INSTRUCTION,
    KEY,
    MODE,
}

/**
 * One thing to do in the workspace. A sequence of these is what makes a request like
 * 「モデルを Opus にして」 expressible: the agent's own UI needs `/model`, then arrow keys
 * chosen from what the screen shows, then Enter — all under a single confirmation.
 */
internal data class HarborCommandStep(
    val action: HarborStepAction,
    val command: String = "",
    val key: String? = null,
    /**
     * Where to leave Claude Code's permission mode when [action] is MODE. How many ⇧Tab presses
     * that takes is not decided here: the app reads the screen between presses.
     */
    val mode: ClaudeCodeMode? = null,
    /** Press Enter after the text. False leaves it typed but unsent (Harbor's 貼付). */
    val submit: Boolean = true,
    val waitMs: Int = HARBOR_STEP_DELAY_MS,
)

internal data class HarborCommandArgs(
    val action: HarborCommandAction = HarborCommandAction.INSTRUCTION,
    val command: String = "",
    val key: String? = null,
    /** Target permission mode when [action] is MODE. */
    val mode: ClaudeCodeMode? = null,
    /** Workspace name or directory to activate when [action] is SWITCH_WORKSPACE. */
    val workspace: String? = null,
    /**
     * Workspace the interpretation was made against. Execution targets this rather than
     * whatever is selected at tap time, so a switch between confirm and tap cannot redirect
     * an approved instruction into another workspace.
     */
    val workspaceId: String? = null,
    val intentSummary: String,
    val needsClarification: Boolean = false,
    val question: String? = null,
    val steps: List<HarborCommandStep> = emptyList(),
) {
    /** The steps to execute; a legacy single-action command is one step. */
    val effectiveSteps: List<HarborCommandStep>
        get() = when {
            steps.isNotEmpty() -> steps
            action == HarborCommandAction.KEY ->
                listOf(HarborCommandStep(HarborStepAction.KEY, key = key ?: "enter"))
            action == HarborCommandAction.MODE && mode != null -> listOf(
                HarborCommandStep(
                    HarborStepAction.MODE,
                    mode = mode,
                    waitMs = HARBOR_MODE_SETTLE_MS,
                ),
            )
            action == HarborCommandAction.INSTRUCTION ->
                listOf(HarborCommandStep(HarborStepAction.INSTRUCTION, command = command))
            else -> emptyList()
        }
}

/**
 * Tool definition and parsing for Terminal Harbor command interpretation.
 * Used both in Harbor confirm flow and in AI dialogue when Harbor is paired.
 */
internal object HarborCommandTool {
    /** Exactly what the Terminal Harbor bridge accepts (`terminal_key_code`). */
    val ALLOWED_KEYS = listOf(
        "enter",
        "escape",
        "shift-tab",
        "tab",
        "up",
        "down",
        "left",
        "right",
        "space",
        "ctrl-c",
    )

    const val MAX_STEPS = 6
    const val MAX_WAIT_MS = 3_000

    private val KEY_ALIASES = mapOf(
        "return" to "enter",
        "cr" to "enter",
        "esc" to "escape",
        "shift+tab" to "shift-tab",
        "shift_tab" to "shift-tab",
        "backtab" to "shift-tab",
        "ctrl+c" to "ctrl-c",
        "ctrl_c" to "ctrl-c",
        "^c" to "ctrl-c",
        "arrowup" to "up",
        "arrowdown" to "down",
        "arrowleft" to "left",
        "arrowright" to "right",
        "spacebar" to "space",
    )

    private val KEY_LABELS = mapOf(
        "enter" to "Enter",
        "escape" to "Esc",
        "shift-tab" to "⇧Tab",
        "tab" to "Tab",
        "up" to "↑",
        "down" to "↓",
        "left" to "←",
        "right" to "→",
        "space" to "Space",
        "ctrl-c" to "^C",
    )

    private val ENTER_STT = Regex(
        "(エンター|エンターキー|enter|return|改行).*(送|押|叩)|" +
            "(送|押|叩).*(エンター|エンターキー|enter|return)|" +
            "(この内容|そのまま|これ).*(で)?.*(エンター|enter|送信)|" +
            "^(エンター|enter|送信)(して|を送って)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Matches a trailing quotative "please send this" cue (「〜と送って」「〜と送信して」),
     * as opposed to bare "〜を送って"/"送って", which is ambiguous with genuine instruction
     * content (e.g. "ファイルを送って").
     */
    private val TRAILING_SEND_TRIGGER = Regex("と(送って|送信して)[\\s。、!?！？]*$")

    private fun stripTrailingSendTrigger(text: String): String {
        val trimmed = text.trim()
        val stripped = TRAILING_SEND_TRIGGER.replace(trimmed, "").trim()
        return stripped.ifBlank { trimmed }
    }

    /** Canonical Harbor key name, or null when the caller named something Harbor cannot send. */
    fun normalizeKeyName(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "").orEmpty()
        if (value.isEmpty()) return null
        val canonical = KEY_ALIASES[value] ?: value
        return canonical.takeIf { ALLOWED_KEYS.contains(it) }
    }

    fun keyLabel(key: String): String = KEY_LABELS[key] ?: key

    /** How a mode step reads on the confirmation screen; the press count is not ours to promise. */
    fun modeLabel(mode: ClaudeCodeMode?): String = "モード→${(mode ?: ClaudeCodeMode.NORMAL).label}"

    /** One line naming every step, so a single tap never runs something the user cannot see. */
    fun stepsPreview(args: HarborCommandArgs): String {
        if (args.action == HarborCommandAction.SWITCH_WORKSPACE) return ""
        val steps = args.effectiveSteps
        if (steps.size <= 1 &&
            args.action != HarborCommandAction.KEY &&
            args.action != HarborCommandAction.MODE
        ) {
            return ""
        }
        return steps.joinToString(" → ") { step ->
            when (step.action) {
                HarborStepAction.KEY -> keyLabel(step.key ?: "enter")
                HarborStepAction.MODE -> modeLabel(step.mode)
                HarborStepAction.INSTRUCTION -> {
                    val text = step.command.take(24) + if (step.command.length > 24) "…" else ""
                    if (step.submit) text else "$text(貼付)"
                }
            }
        }
    }

    const val SYSTEM_APPENDIX =
        "The user can operate their own PC terminal through Terminal Harbor. " +
            "Attached Terminal Harbor context shows the selected workspace and the AI coding agent " +
            "session currently running there (scrollback / conversation). " +
            "Treat pronouns like これ/この内容/続けて as references to that terminal conversation. " +
            "Do not re-ask for details already visible in that context. " +
            "When they ask to run commands, press keys, commit, push, send Enter, switch " +
            "workspaces, or otherwise control the terminal, do not refuse. Always call the " +
            "harbor_command function. " +
            "You do not execute the command yourself; the user confirms before it is sent. " +
            "Use action=instruction with command set to the exact text to type/send to the agent " +
            "or shell. Use action=key with one of the listed key names for a single key press " +
            "(エンターを送って / この内容で送信 → enter; 止めて → escape; 中断して → ctrl-c). " +
            "Use steps for anything that takes more than one key or line, so the whole sequence " +
            "runs under one confirmation. " +
            "Claude Code permission modes (通常 / 自動編集 / プラン / オート / 自動拒否 / 権限スキップ) " +
            "are action=mode with mode set to normal, accept_edits, plan, auto, dont_ask or " +
            "bypass_permissions. Never count " +
            "shift-tab presses yourself and never ask what the current mode is: the app presses " +
            "shift-tab one at a time and reads the screen after each press until that mode is " +
            "showing. Use key=shift-tab only when the user asks for that one key press literally. " +
            "Switching model is the /model command: send instruction \"/model\" with submit=false, " +
            "then move with up/down according to the choices the context shows, then enter. " +
            "If the context does not show the model list, do not guess the number of key " +
            "presses: set needs_clarification instead. " +
            "Use action=switch_workspace only when moving to another workspace is the whole " +
            "request, with nothing to do once you arrive (「harbor に切り替えて」 / 「〜に移動して」); " +
            "set workspace to one of the listed workspaces. " +
            "A trailing 「〜と送って」/「〜と送信して」 is a spoken cue meaning send what precedes " +
            "it — exclude that trailing phrase from command itself (「問題なさそうと送って」 → " +
            "command 「問題なさそう」). " +
            "If the user is asking for work to be done, it is action=instruction even when the " +
            "sentence names one or more workspaces. For example " +
            "「voice-harness-even-g2 と terminal-harbor を整理してコミットして」 is action=instruction " +
            "with that whole sentence as command — never a switch. " +
            "Put a one-sentence Japanese confirmation of the intent in intent_summary that names " +
            "the concrete target from context when possible " +
            "(for example 「ドキュメント更新のコミットと push を指示しますか？」). " +
            "If the request is still too ambiguous even with the terminal context, set " +
            "needs_clarification to true and put a short Japanese clarifying question in question."

    fun toolDefinitionJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", HARBOR_COMMAND_TOOL_NAME)
            put(
                "description",
                "Send an instruction or keystroke to the user's Terminal Harbor workspace " +
                    "after they confirm. Use this instead of refusing terminal or PC operations.",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("instruction")
                            put("key")
                            put("mode")
                            put("switch_workspace")
                        })
                        put(
                            "description",
                            "instruction = type/send text to the terminal or agent. " +
                                "key = send one key event. " +
                                "mode = leave Claude Code in a permission mode. " +
                                "switch_workspace = activate a different workspace. " +
                                "Ignored when steps is set.",
                        )
                    })
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "Exact instruction or text to send when action=instruction.",
                        )
                    })
                    put("key", keySchema("Key name when action=key."))
                    put("mode", modeSchema("Target permission mode when action=mode."))
                    put("submit", JSONObject().apply {
                        put("type", "boolean")
                        put(
                            "description",
                            "Press Enter after the text (default true). Use false for a slash " +
                                "command whose menu you then navigate with keys.",
                        )
                    })
                    put("steps", JSONObject().apply {
                        put("type", "array")
                        put("maxItems", MAX_STEPS)
                        put(
                            "description",
                            "Ordered steps for a request that needs more than one key or line. " +
                                "Executed in order under a single user confirmation.",
                        )
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("action", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray().apply {
                                        put("instruction")
                                        put("key")
                                        put("mode")
                                    })
                                })
                                put("command", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Text to send when action=instruction.")
                                })
                                put("key", keySchema("Key name when action=key."))
                                put(
                                    "mode",
                                    modeSchema("Target permission mode when action=mode."),
                                )
                                put("submit", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "Press Enter after the text (default true).")
                                })
                                put("wait_ms", JSONObject().apply {
                                    put("type", "integer")
                                    put("minimum", 0)
                                    put("maximum", MAX_WAIT_MS)
                                    put(
                                        "description",
                                        "Pause after this step, in milliseconds, when the agent " +
                                            "needs longer to redraw. Default $HARBOR_STEP_DELAY_MS.",
                                    )
                                })
                            })
                            put("required", JSONArray().apply { put("action") })
                        })
                    })
                    put("workspace", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "Workspace name or directory to activate when " +
                                "action=switch_workspace. Must come from the listed workspaces.",
                        )
                    })
                    put("intent_summary", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "One short Japanese sentence confirming what will be sent, " +
                                "ending as a question when asking for confirmation.",
                        )
                    })
                    put("needs_clarification", JSONObject().apply {
                        put("type", "boolean")
                        put(
                            "description",
                            "True when the spoken request is too ambiguous to send yet.",
                        )
                    })
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "Short Japanese clarifying question when needs_clarification is true.",
                        )
                    })
                })
                put("required", JSONArray().apply {
                    put("action")
                    put("intent_summary")
                })
            })
        })
    }

    private fun keySchema(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("enum", JSONArray().apply { ALLOWED_KEYS.forEach(::put) })
        put("description", description)
    }

    private fun modeSchema(description: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("enum", JSONArray().apply { ClaudeCodeMode.entries.forEach { put(it.wire) } })
        put(
            "description",
            "$description The app presses shift-tab one at a time and checks the screen after " +
                "each press, so do not plan a number of presses.",
        )
    }

    fun parse(argumentsJson: String, fallbackCommand: String = ""): HarborCommandArgs {
        val sttFallback = fallbackCommand.trim()
        return try {
            val obj = JSONObject(argumentsJson.ifBlank { "{}" })
            val actionRaw = obj.optString("action", "instruction").trim().lowercase(Locale.ROOT)
            val explicitKey = normalizeKeyName(obj.optString("key", ""))
            val explicitMode = ClaudeCodeMode.fromWire(obj.optString("mode", ""))
            val rawCommand = obj.optString("command", "").trim()
                .ifBlank { sttFallback }
            val command = stripTrailingSendTrigger(rawCommand)
            val workspace = obj.optString("workspace", "").trim().takeIf { it.isNotEmpty() }
            val intentSummary = obj.optString("intent_summary", "").trim()
            val needsClarification = obj.optBoolean("needs_clarification", false)
            val question = obj.optString("question", "").trim().takeIf { it.isNotEmpty() }

            // A switch wins over the Enter heuristics below; a workspace name can
            // otherwise look like ordinary text to them.
            if (actionRaw == "switch_workspace" && !needsClarification) {
                val target = workspace ?: command
                if (target.isNotBlank()) {
                    return HarborCommandArgs(
                        action = HarborCommandAction.SWITCH_WORKSPACE,
                        command = "",
                        key = null,
                        workspace = target,
                        intentSummary = intentSummary.ifBlank { "「$target」に切り替えますか？" },
                        needsClarification = false,
                        question = null,
                    )
                }
            }

            // A mode change also wins outright: it names a destination rather than text, so
            // letting the heuristics below read the utterance would only re-decide it.
            if (actionRaw == "mode" && !needsClarification) {
                return if (explicitMode == null) {
                    HarborCommandArgs(
                        action = HarborCommandAction.INSTRUCTION,
                        intentSummary = "どのモードにしますか？（通常 / 自動編集 / プラン）",
                        needsClarification = true,
                        question = "どのモードにしますか？（通常 / 自動編集 / プラン）",
                    )
                } else {
                    HarborCommandArgs(
                        action = HarborCommandAction.MODE,
                        command = "",
                        key = null,
                        mode = explicitMode,
                        workspace = null,
                        intentSummary = intentSummary.ifBlank {
                            "${explicitMode.label}モードに切り替えますか？"
                        },
                        needsClarification = false,
                        question = null,
                    )
                }
            }

            val steps = parseSteps(obj.optJSONArray("steps"))
            if (steps.isNotEmpty() && !needsClarification) {
                // A multi-step plan is the model's considered sequence; the Enter heuristics
                // below exist for one-shot utterances and would flatten it into a single key.
                val first = steps.first()
                return HarborCommandArgs(
                    action = when (first.action) {
                        HarborStepAction.KEY -> HarborCommandAction.KEY
                        HarborStepAction.MODE -> HarborCommandAction.MODE
                        HarborStepAction.INSTRUCTION -> HarborCommandAction.INSTRUCTION
                    },
                    command = first.command,
                    key = first.key,
                    mode = first.mode,
                    workspace = null,
                    intentSummary = intentSummary.ifBlank { defaultStepsSummary(steps) },
                    needsClarification = false,
                    question = null,
                    steps = steps,
                )
            }

            val looksLikeEnter = explicitKey == null && !needsClarification && (
                isEnterRequest(sttFallback) ||
                    command.equals("enter", ignoreCase = true) ||
                    command.equals("return", ignoreCase = true) ||
                    command.contains("エンター")
                )

            val args = when {
                needsClarification -> HarborCommandArgs(
                    action = HarborCommandAction.INSTRUCTION,
                    command = command,
                    key = null,
                    workspace = null,
                    intentSummary = question ?: intentSummary.ifBlank { "もう一度お願いします" },
                    needsClarification = true,
                    question = question,
                )

                explicitKey != null || actionRaw == "key" -> {
                    val key = explicitKey ?: "enter"
                    HarborCommandArgs(
                        action = HarborCommandAction.KEY,
                        command = "",
                        key = key,
                        workspace = null,
                        intentSummary = intentSummary.ifBlank { "${keyLabel(key)}キーを送りますか？" },
                        needsClarification = false,
                        question = null,
                    )
                }

                looksLikeEnter -> HarborCommandArgs(
                    action = HarborCommandAction.KEY,
                    command = "",
                    key = "enter",
                    workspace = null,
                    intentSummary = intentSummary.ifBlank { "Enterキーを送りますか？" },
                    needsClarification = false,
                    question = null,
                )

                else -> {
                    val submit = obj.optBoolean("submit", true)
                    HarborCommandArgs(
                        action = HarborCommandAction.INSTRUCTION,
                        command = command,
                        key = null,
                        workspace = null,
                        intentSummary = intentSummary.ifBlank {
                            if (command.isNotBlank()) {
                                if (submit) "「$command」を送りますか？" else "「$command」を貼り付けますか？"
                            } else {
                                "そのまま送信します"
                            }
                        },
                        needsClarification = false,
                        question = null,
                        // Only an unsent paste needs an explicit step; a plain send is the
                        // legacy single-action shape every existing call site already handles.
                        steps = if (submit) {
                            emptyList()
                        } else {
                            listOf(
                                HarborCommandStep(
                                    action = HarborStepAction.INSTRUCTION,
                                    command = command,
                                    submit = false,
                                ),
                            )
                        },
                    )
                }
            }
            normalizeWithStt(args, sttFallback)
        } catch (_: Exception) {
            fallback(sttFallback)
        }
    }

    private fun parseSteps(array: JSONArray?): List<HarborCommandStep> {
        if (array == null) return emptyList()
        val steps = mutableListOf<HarborCommandStep>()
        for (index in 0 until array.length()) {
            if (steps.size >= MAX_STEPS) break
            val item = array.optJSONObject(index) ?: continue
            val action = item.optString("action").trim().lowercase(Locale.ROOT)
            val defaultWait =
                if (action == "mode") HARBOR_MODE_SETTLE_MS else HARBOR_STEP_DELAY_MS
            val waitMs = item.optInt("wait_ms", defaultWait).coerceIn(0, MAX_WAIT_MS)
            if (action == "key") {
                // A key Harbor cannot send is dropped rather than guessed at: sending the
                // wrong key into someone's terminal is worse than sending one fewer.
                val key = normalizeKeyName(item.optString("key")) ?: continue
                steps += HarborCommandStep(HarborStepAction.KEY, key = key, waitMs = waitMs)
            } else if (action == "mode") {
                // Same reasoning as an unsendable key: a mode we cannot name is a mode we
                // cannot verify on screen, so the step is dropped instead of guessed at.
                val mode = ClaudeCodeMode.fromWire(item.optString("mode")) ?: continue
                steps += HarborCommandStep(HarborStepAction.MODE, mode = mode, waitMs = waitMs)
            } else {
                val command = item.optString("command").trim()
                if (command.isEmpty()) continue
                steps += HarborCommandStep(
                    action = HarborStepAction.INSTRUCTION,
                    command = stripTrailingSendTrigger(command),
                    submit = item.optBoolean("submit", true),
                    waitMs = waitMs,
                )
            }
        }
        return steps
    }

    private fun defaultStepsSummary(steps: List<HarborCommandStep>): String {
        val preview = steps.joinToString(" → ") { step ->
            when (step.action) {
                HarborStepAction.KEY -> keyLabel(step.key ?: "enter")
                HarborStepAction.MODE -> modeLabel(step.mode)
                HarborStepAction.INSTRUCTION -> step.command.take(20)
            }
        }
        return "$preview を実行しますか？"
    }

    fun fallback(rawStt: String): HarborCommandArgs {
        val trimmedStt = rawStt.trim()
        if (isEnterRequest(trimmedStt)) {
            return HarborCommandArgs(
                action = HarborCommandAction.KEY,
                key = "enter",
                intentSummary = "Enterキーを送りますか？",
            )
        }
        val command = stripTrailingSendTrigger(trimmedStt)
        return HarborCommandArgs(
            action = HarborCommandAction.INSTRUCTION,
            command = command,
            intentSummary = if (command.isNotBlank()) "そのまま送信します" else "指示を認識できませんでした",
            needsClarification = command.isBlank(),
            question = if (command.isBlank()) "もう一度、送る内容を話してください" else null,
        )
    }

    fun isEnterRequest(text: String): Boolean {
        val compact = text.lowercase(Locale.ROOT)
            .replace(Regex("[\\s　、。,.!！?？]+"), "")
        if (compact.isEmpty()) return false
        return ENTER_STT.containsMatchIn(text) ||
            compact == "エンター" ||
            compact == "enter" ||
            compact == "送信" ||
            compact == "送信して"
    }

    private fun normalizeWithStt(args: HarborCommandArgs, stt: String): HarborCommandArgs {
        if (args.needsClarification) return args
        if (args.action == HarborCommandAction.SWITCH_WORKSPACE) return args
        if (args.action == HarborCommandAction.MODE) return args
        // Only a single-step command may be rewritten from the transcript. A planned
        // sequence, or a key the model named outright, is left as it decided.
        if (args.steps.size > 1) return args
        if (args.key != null) return args
        if (isEnterRequest(stt) && args.action != HarborCommandAction.KEY) {
            return args.copy(
                action = HarborCommandAction.KEY,
                command = "",
                key = "enter",
                steps = emptyList(),
                intentSummary = args.intentSummary.ifBlank { "Enterキーを送りますか？" },
            )
        }
        return args
    }
}
