package com.g150446.voiceharness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class GestureDiagEntry(
    val tMs: Int,
    val stage: Int,
    val reason: Int,
    val v1: Float,
    val v2: Float,
    val v3: Float,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val fromHistoryBatch: Boolean = false,
) {
    val stageName: String get() = STAGE_NAMES[stage] ?: "stage_0x%02X".format(stage)
    val reasonName: String get() = REASON_NAMES[reason] ?: "reason_0x%02X".format(reason)

    fun summaryLine(): String {
        val t = if (fromHistoryBatch || tMs > 0) {
            "+${tMs}ms"
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(receivedAtMs))
        }
        return "[$t] $stageName/$reasonName  v1=%.2f v2=%.2f v3=%.2f"
            .format(Locale.US, v1, v2, v3)
    }

    /** Human-readable line with measured values and key thresholds. */
    fun historyDetailLine(): String {
        val t = "+${tMs}ms"
        val body = when (stage) {
            0x01 -> // outbound_start
                "シェイク 実測 ptp=%.2f Z=%.2f mean=%.2f | 閾値 ptp≥5.0 Z≥0.80 |mean|<0.4×ptp"
                    .format(Locale.US, v1, v2, v3)
            0x02 -> // outbound_ready
                "掌上 実測 phi=%.1f° 3D=%.1f° Δz=%.2f | 閾値 phi≥12 3D≥20 Δz≥0.35 または gyro"
                    .format(Locale.US, v1, v2, v3)
            0x0F -> // outbound_gyro
                "gyro_y 実測 ∫ωy=%+.1f° peak=%.1f dps | 閾値 |∫|≥25 または peak≥35"
                    .format(Locale.US, v1, v2)
            0x07 ->
                "hold開始 実測 +imp=%.3f -imp=%.3f tilt=%.1f° | 閾値 +imp≥0.04 tilt≤15"
                    .format(Locale.US, v1, v2, v3)
            0x08 ->
                "hold完了 実測 +imp=%.3f hold=%.0f ms tilt=%.1f° | 閾値 hold≥400 tilt≤15"
                    .format(Locale.US, v1, v2, v3)
            0x09 ->
                "match 実測 phi=%.1f° +imp=%.3f hold=%.0f ms"
                    .format(Locale.US, v1, v2, v3)
            0x0C ->
                "停止掌上 実測 phi=%.1f° 3D=%.1f° Δz=%.2f | 閾値 重力または gyro"
                    .format(Locale.US, v1, v2, v3)
            0x0D ->
                "ジャイロON odr=%.0f Hz bias_y=%+.2f"
                    .format(Locale.US, v1, v2)
            0x0E ->
                "ジャイロOFF"
            0x22 ->
                "hold中 実測 RMS=%.2f tilt=%.1f° |gy|=%.1f | 閾値 RMS≤3.0 tilt≤15"
                    .format(Locale.US, v1, v2, v3)
            0x10 ->
                "reject $reasonName  v1=%.2f v2=%.2f v3=%.2f"
                    .format(Locale.US, v1, v2, v3)
            0x80 ->
                "reset $reasonName  v1=%.2f v2=%.2f v3=%.2f"
                    .format(Locale.US, v1, v2, v3)
            else ->
                "$stageName/$reasonName  v1=%.2f v2=%.2f v3=%.2f"
                    .format(Locale.US, v1, v2, v3)
        }
        return "[$t] $body"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tMs", tMs)
        put("stage", stage)
        put("reason", reason)
        put("v1", v1.toDouble())
        put("v2", v2.toDouble())
        put("v3", v3.toDouble())
        put("receivedAtMs", receivedAtMs)
    }

    companion object {
        val STAGE_NAMES = mapOf(
            0x01 to "outbound_start",
            0x02 to "outbound_ready",
            0x07 to "final_hold_start",
            0x08 to "final_ready",
            0x09 to "match",
            0x0C to "stop_palm_up",
            0x0D to "gyro_enabled",
            0x0E to "gyro_disabled",
            0x0F to "outbound_gyro",
            0x10 to "wait_reject",
            0x21 to "final_sample",
            0x22 to "hold_sample",
            0x80 to "reset",
        )
        val REASON_NAMES = mapOf(
            0x00 to "none",
            0x01 to "quiet_not_ready",
            0x02 to "start_not_palm_up",
            0x03 to "outbound_rate_low",
            0x11 to "outbound_timeout",
            0x12 to "incomplete_outbound",
            0x1A to "final_hold_interrupted",
            0x1B to "final_hold_timeout",
            0x1C to "sequence_timeout",
            0x1D to "final_accel_missing",
            0x1E to "final_brake_missing",
            0x1F to "final_brake_ratio_low",
            0x20 to "final_tilt_unstable",
            0x21 to "final_pulse_duration_invalid",
            0x22 to "shake_not_oscillatory",
            0x23 to "lift_palm_still_up",
        )

        /** High-rate samples omitted from voice-history storage. */
        private val SKIP_STAGES_FOR_HISTORY = setOf(0x21)

        fun fromJson(obj: JSONObject): GestureDiagEntry = GestureDiagEntry(
            tMs = obj.optInt("tMs", 0),
            stage = obj.optInt("stage", 0),
            reason = obj.optInt("reason", 0),
            v1 = obj.optDouble("v1", 0.0).toFloat(),
            v2 = obj.optDouble("v2", 0.0).toFloat(),
            v3 = obj.optDouble("v3", 0.0).toFloat(),
            receivedAtMs = obj.optLong("receivedAtMs", 0L),
            fromHistoryBatch = obj.optBoolean("fromHistoryBatch", false),
        )

        fun listToJson(list: List<GestureDiagEntry>): JSONArray {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr
        }

        fun listFromJson(arr: JSONArray?): List<GestureDiagEntry> {
            if (arr == null || arr.length() == 0) return emptyList()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        /**
         * Slice live diags for one recording: pre-roll before 0x01 through post-stop.
         * Drops noisy final_sample; caps count.
         */
        fun sliceForRecording(
            live: List<GestureDiagEntry>,
            recordingStartMs: Long,
            recordingStopMs: Long,
            preRollMs: Long = 8_000L,
            postRollMs: Long = 1_500L,
            maxEntries: Int = 40,
        ): List<GestureDiagEntry> {
            if (recordingStartMs <= 0L) return emptyList()
            val from = recordingStartMs - preRollMs
            val to = if (recordingStopMs > 0L) recordingStopMs + postRollMs else recordingStartMs + 30_000L
            val filtered = live.filter { e ->
                e.receivedAtMs in from..to && e.stage !in SKIP_STAGES_FOR_HISTORY
            }
            if (filtered.isEmpty()) return emptyList()
            val t0 = filtered.first().receivedAtMs
            val renumbered = filtered.map { e ->
                val rel = (e.receivedAtMs - t0).coerceIn(0L, 65535L).toInt()
                e.copy(tMs = rel, fromHistoryBatch = true)
            }
            return if (renumbered.size <= maxEntries) {
                renumbered
            } else {
                // Keep milestones; drop middle hold_samples first.
                val milestones = renumbered.filter { it.stage != 0x22 && it.stage != 0x10 }
                val holds = renumbered.filter { it.stage == 0x22 || it.stage == 0x10 }
                val room = (maxEntries - milestones.size).coerceAtLeast(0)
                val keptHolds = if (room == 0) emptyList() else holds.takeLast(room)
                (milestones + keptHolds).sortedBy { it.tMs }.takeLast(maxEntries)
            }
        }
    }
}

data class GestureDiagSession(
    val sessionId: Int,
    val startedAtMs: Long,
    val entries: List<GestureDiagEntry>,
    val complete: Boolean,
)

/**
 * In-memory store for firmware gesture diagnostics (0x30 live + 0x33–0x35 batch).
 * Also provides snapshots for attachment to voice HistoryEntry.
 */
object GestureDiagStore {
    private const val MAX_LIVE = 200
    private const val MAX_SESSIONS = 20

    private val _liveEntries = MutableStateFlow<List<GestureDiagEntry>>(emptyList())
    val liveEntries: StateFlow<List<GestureDiagEntry>> = _liveEntries.asStateFlow()

    private val _sessions = MutableStateFlow<List<GestureDiagSession>>(emptyList())
    val sessions: StateFlow<List<GestureDiagSession>> = _sessions.asStateFlow()

    private val _statusLine = MutableStateFlow("")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    private var batchExpected = 0
    private var batchSessionId = 0
    private val batchBuffer = mutableListOf<GestureDiagEntry>()
    private var lastBatchEntries: List<GestureDiagEntry> = emptyList()
    private var lastBatchEndedAtMs: Long = 0L

    fun onLiveDiag(stage: Int, reason: Int, v1: Float, v2: Float, v3: Float) {
        val entry = GestureDiagEntry(
            tMs = 0,
            stage = stage,
            reason = reason,
            v1 = v1,
            v2 = v2,
            v3 = v3,
            fromHistoryBatch = false,
        )
        _liveEntries.value = (_liveEntries.value + entry).takeLast(MAX_LIVE)
        if (stage != 0x21) {
            _statusLine.value = entry.summaryLine()
            DebugMessageManager.addMessage("G ${entry.stageName}: ${"%.1f".format(v1)}")
        }
    }

    fun onHistoryBegin(count: Int, sessionId: Int) {
        batchExpected = count.coerceAtLeast(0)
        batchSessionId = sessionId
        batchBuffer.clear()
        _statusLine.value = "history begin session=$sessionId count=$count"
    }

    fun onHistoryEntry(tMs: Int, stage: Int, reason: Int, v1: Float, v2: Float, v3: Float) {
        batchBuffer.add(
            GestureDiagEntry(
                tMs = tMs,
                stage = stage,
                reason = reason,
                v1 = v1,
                v2 = v2,
                v3 = v3,
                fromHistoryBatch = true,
            )
        )
    }

    fun onHistoryEnd(count: Int, sessionId: Int) {
        val entries = batchBuffer.toList()
        batchBuffer.clear()
        val session = GestureDiagSession(
            sessionId = sessionId,
            startedAtMs = System.currentTimeMillis(),
            entries = entries,
            complete = count == entries.size || batchExpected == entries.size,
        )
        _sessions.value = (listOf(session) + _sessions.value).take(MAX_SESSIONS)
        _liveEntries.value = (_liveEntries.value + entries).takeLast(MAX_LIVE)
        lastBatchEntries = entries
        lastBatchEndedAtMs = System.currentTimeMillis()
        _statusLine.value =
            "history end session=$sessionId entries=${entries.size} (hdr count=$count)"
        DebugMessageManager.addMessage("G history #${sessionId}: ${entries.size} pts")
        batchExpected = 0
    }

    /**
     * Snapshot diags for the recording that just stopped.
     * Prefers a FW debug batch if it ended within 3s of stop; else time-sliced live 0x30.
     */
    fun snapshotForRecording(recordingStartMs: Long, recordingStopMs: Long): List<GestureDiagEntry> {
        if (lastBatchEntries.isNotEmpty() &&
            lastBatchEndedAtMs >= recordingStopMs - 500L &&
            lastBatchEndedAtMs <= recordingStopMs + 3_000L
        ) {
            return lastBatchEntries.takeLast(40)
        }
        return GestureDiagEntry.sliceForRecording(
            live = _liveEntries.value,
            recordingStartMs = recordingStartMs,
            recordingStopMs = recordingStopMs,
        )
    }

    fun clear() {
        _liveEntries.value = emptyList()
        _sessions.value = emptyList()
        _statusLine.value = ""
        batchBuffer.clear()
        batchExpected = 0
        lastBatchEntries = emptyList()
        lastBatchEndedAtMs = 0L
    }

    fun parseFloatLe(data: ByteArray, offset: Int): Float {
        if (offset + 4 > data.size) return 0f
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    fun parseU16Le(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }
}
