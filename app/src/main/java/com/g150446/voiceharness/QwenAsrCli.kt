package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

internal data class QwenAsrCliResult(
    val text: String,
    val languageCode: String?
)

internal object QwenAsrOutputParser {
    private val transcriptionPattern = Regex("<asr_text>(.*)", setOf(RegexOption.DOT_MATCHES_ALL))
    private val languagePattern = Regex("(?:^|\\n)language\\s+([^<\\r\\n]+)", RegexOption.IGNORE_CASE)

    fun parse(output: String): QwenAsrCliResult {
        val tagMatch = transcriptionPattern.find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.removeSuffix("<|im_end|>")
            ?.trim()

        // The pinned llama-mtmd-cli runtime does not actually emit an <asr_text>
        // marker in practice — fall back to the raw stdout as the transcript.
        val transcription = if (!tagMatch.isNullOrBlank()) {
            tagMatch
        } else {
            output.trim().removeSuffix("<|im_end|>").trim()
        }
        require(transcription.isNotBlank()) {
            "Qwen3-ASR output did not contain a transcription"
        }
        val language = languagePattern.find(output)?.groupValues?.get(1)?.trim()
        return QwenAsrCliResult(
            text = transcription,
            languageCode = languageToCode(language)
        )
    }

    private fun languageToCode(language: String?): String? = when (language?.lowercase()) {
        "japanese" -> "ja"
        "english" -> "en"
        "chinese" -> "zh"
        "cantonese" -> "yue"
        "korean" -> "ko"
        "german" -> "de"
        "french" -> "fr"
        "spanish" -> "es"
        "italian" -> "it"
        "portuguese" -> "pt"
        else -> null
    }
}

/** Runs the pinned upstream llama.cpp Qwen3-ASR binary as an isolated process. */
internal class QwenAsrCli(private val context: Context) {
    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    val executable: File
        get() = File(nativeDir, EXECUTABLE_NAME)

    fun transcribe(audioFile: File, decoder: File, projector: File): QwenAsrCliResult {
        require(audioFile.isFile) { "Audio file missing: ${audioFile.absolutePath}" }
        require(decoder.isFile) { "Qwen3-ASR decoder missing: ${decoder.absolutePath}" }
        require(projector.isFile) { "Qwen3-ASR audio projector missing: ${projector.absolutePath}" }
        require(executable.isFile && executable.canExecute()) {
            "Qwen3-ASR native runtime is not available"
        }

        val command = listOf(
            executable.absolutePath,
            "-m", decoder.absolutePath,
            "--mmproj", projector.absolutePath,
            "--audio", audioFile.absolutePath,
            "-p", ASR_PROMPT,
            "-n", "64",
            "-c", "1024",
            "-t", "4",
            "-ngl", "0",
            "--no-mmproj-offload",
            "--temp", "0",
            "--no-warmup"
        )
        Log.d(TAG, "Starting Qwen3-ASR process")
        val process = ProcessBuilder(command)
            .directory(context.cacheDir)
            .redirectErrorStream(false)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
            }
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        fun startReader(name: String, stream: java.io.InputStream, sink: StringBuilder, logPrefix: String) =
            Thread {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        sink.appendLine(line)
                        Log.d(TAG, "$logPrefix: ${line.take(500)}")
                    }
                }
            }.apply {
                this.name = name
                start()
            }

        val stdoutReader = startReader("qwen-asr-stdout", process.inputStream, stdout, "stdout")
        val stderrReader = startReader("qwen-asr-stderr", process.errorStream, stderr, "stderr")

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            error("Qwen3-ASR timed out after $TIMEOUT_SECONDS seconds")
        }
        stdoutReader.join(2_000)
        stderrReader.join(2_000)
        check(process.exitValue() == 0) {
            "Qwen3-ASR failed with exit ${process.exitValue()}: ${stderr.takeLast(1_000)}"
        }
        return QwenAsrOutputParser.parse(stdout.toString())
    }

    private companion object {
        private const val TAG = "QwenAsrCli"
        private const val EXECUTABLE_NAME = "libqwen_asr_cli.so"
        private const val TIMEOUT_SECONDS = 60L
        private const val ASR_PROMPT =
            "Transcribe the audio in its original language. Output only the transcription."
    }
}
