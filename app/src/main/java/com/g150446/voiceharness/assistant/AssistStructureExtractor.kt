package com.g150446.voiceharness.assistant

import android.app.assist.AssistStructure
import android.os.Build
import com.g150446.voiceharness.ScreenContextPrompt

/** Extracts ordered, de-duplicated visible text from AssistStructure. */
object AssistStructureExtractor {
    private const val MAX_CHARS = 24_000

    data class Extracted(
        val text: String,
        val sourcePackage: String?,
        val sourceUri: String?,
    )

    fun extract(structure: AssistStructure?): Extracted {
        if (structure == null) {
            return Extracted(text = "", sourcePackage = null, sourceUri = null)
        }
        val parts = ArrayList<String>()
        val seen = HashSet<String>()
        var sourcePackage: String? = null
        var sourceUri: String? = null

        val windowCount = structure.windowNodeCount
        for (w in 0 until windowCount) {
            val window = structure.getWindowNodeAt(w)
            if (sourcePackage == null) {
                sourcePackage = window.title?.toString()?.takeIf { it.isNotBlank() }
            }
            if (Build.VERSION.SDK_INT >= 23) {
                // package name may appear on root view
            }
            collectNode(window.rootViewNode, parts, seen)
        }

        // Prefer package from first root view if available
        if (windowCount > 0) {
            val root = structure.getWindowNodeAt(0).rootViewNode
            sourcePackage = root.idPackage?.takeIf { it.isNotBlank() } ?: sourcePackage
        }

        val joined = parts.joinToString("\n")
        return Extracted(
            text = ScreenContextPrompt.truncateAssistText(joined, MAX_CHARS),
            sourcePackage = sourcePackage,
            sourceUri = sourceUri,
        )
    }

    private fun collectNode(
        node: AssistStructure.ViewNode?,
        parts: MutableList<String>,
        seen: MutableSet<String>,
    ) {
        if (node == null) return
        addText(node.text?.toString(), parts, seen)
        addText(node.contentDescription?.toString(), parts, seen)
        addText(node.hint?.toString(), parts, seen)
        for (i in 0 until node.childCount) {
            collectNode(node.getChildAt(i), parts, seen)
        }
    }

    private fun addText(raw: String?, parts: MutableList<String>, seen: MutableSet<String>) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return
        if (!seen.add(value)) return
        parts += value
    }
}
