package com.g150446.voiceharness

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val HARBOR_COMMAND_TOOL_NAME = "harbor_command"

internal enum class HarborCommandAction {
    INSTRUCTION,
    KEY,
    SWITCH_WORKSPACE,
}

internal data class HarborCommandArgs(
    val action: HarborCommandAction = HarborCommandAction.INSTRUCTION,
    val command: String = "",
    val key: String? = null,
    /** Workspace name or directory to activate when [action] is SWITCH_WORKSPACE. */
    val workspace: String? = null,
    val intentSummary: String,
    val needsClarification: Boolean = false,
    val question: String? = null,
)

/**
 * Tool definition and parsing for Terminal Harbor command interpretation.
 * Used both in Harbor confirm flow and in AI dialogue when Harbor is paired.
 */
internal object HarborCommandTool {
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
            "or shell. Use action=key with key=enter when the user wants to press Enter/Return " +
            "(for example エンターを送って / この内容で送信). " +
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
                            put("switch_workspace")
                        })
                        put(
                            "description",
                            "instruction = type/send text to the terminal or agent. " +
                                "key = send a key event (currently only enter). " +
                                "switch_workspace = activate a different workspace.",
                        )
                    })
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "Exact instruction or text to send when action=instruction.",
                        )
                    })
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply { put("enter") })
                        put(
                            "description",
                            "Key name when action=key. Use enter for Enter/Return.",
                        )
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

    fun parse(argumentsJson: String, fallbackCommand: String = ""): HarborCommandArgs {
        val sttFallback = fallbackCommand.trim()
        return try {
            val obj = JSONObject(argumentsJson.ifBlank { "{}" })
            val actionRaw = obj.optString("action", "instruction").trim().lowercase(Locale.ROOT)
            val keyRaw = obj.optString("key", "").trim().lowercase(Locale.ROOT)
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

            val looksLikeEnter = isEnterRequest(sttFallback) ||
                actionRaw == "key" ||
                keyRaw == "enter" ||
                command.equals("enter", ignoreCase = true) ||
                command.equals("return", ignoreCase = true) ||
                command.contains("エンター")

            val args = if (looksLikeEnter && !needsClarification) {
                HarborCommandArgs(
                    action = HarborCommandAction.KEY,
                    command = "",
                    key = "enter",
                    workspace = null,
                    intentSummary = intentSummary.ifBlank { "Enterキーを送りますか？" },
                    needsClarification = false,
                    question = null,
                )
            } else {
                HarborCommandArgs(
                    action = HarborCommandAction.INSTRUCTION,
                    command = command,
                    key = null,
                    workspace = null,
                    intentSummary = intentSummary.ifBlank {
                        if (command.isNotBlank()) "「$command」を送りますか？" else "そのまま送信します"
                    },
                    needsClarification = needsClarification,
                    question = question,
                ).let { base ->
                    if (base.needsClarification) {
                        base.copy(intentSummary = question ?: base.intentSummary)
                    } else {
                        base
                    }
                }
            }
            normalizeWithStt(args, sttFallback)
        } catch (_: Exception) {
            fallback(sttFallback)
        }
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
        if (isEnterRequest(stt) && args.action != HarborCommandAction.KEY) {
            return args.copy(
                action = HarborCommandAction.KEY,
                command = "",
                key = "enter",
                intentSummary = args.intentSummary.ifBlank { "Enterキーを送りますか？" },
            )
        }
        return args
    }
}
