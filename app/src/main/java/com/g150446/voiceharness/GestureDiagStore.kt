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

    /** Human-readable line with measured values and key thresholds (FW 0.0.68). */
    fun historyDetailLine(): String {
        val t = "+${tMs}ms"
        val body = when (stage) {
            0x01 -> // outbound_start: z_ratio, 0, linear_accel
                "掌上候補 実測 Z比=%.2f lin=%.2f | 閾値 |Z|≥0.75 rms≤4"
                    .format(Locale.US, v1, v3)
            0x02 -> // outbound_ready: dwell_ms, z_ratio, linear
                "掌上成立 実測 dwell=%.0fms Z比=%.2f | 閾値 dwell≥500 |Z|≥0.75"
                    .format(Locale.US, v1, v2)
            0x0F -> // outbound_gyro (legacy stop path; unused for stop since 0.0.69)
                "gyro 実測 ∫ωy=%+.1f° peak=%.1f dps"
                    .format(Locale.US, v1, v2)
            0x07 ->
                "hold開始 実測 +imp=%.3f -imp=%.3f tilt=%.1f° | 閾値 +imp≥0.30 tilt≤15"
                    .format(Locale.US, v1, v2, v3)
            0x08 ->
                "hold完了 実測 +imp=%.3f hold=%.0f ms tilt=%.1f° | 閾値 hold≥500 tilt≤15"
                    .format(Locale.US, v1, v2, v3)
            0x09 ->
                "match 実測 phi=%.1f° +imp=%.3f hold=%.0f ms"
                    .format(Locale.US, v1, v2, v3)
            0x0A -> {
                val before = (reason and 0x01) != 0
                val waived = (reason and 0x02) != 0
                "match詳細 xy=%.2f lift_imp=%.3f roll_at_lift=%.1f° before_flip=%s xy_waive=%s | 免除: before∧imp≥0.30 または xy≥0.42"
                    .format(
                        Locale.US, v1, v2, v3,
                        if (before) "Y" else "N",
                        if (waived) "Y" else "N",
                    )
            }
            0x0C ->
                // 0.0.69+: hand-lower reverse-lift pulse (opp_imp, peak, pulse_ms)
                "停止手下ろし 実測 opp_imp=%.3f peak=%.2f pulse=%.0fms | 閾値 imp≥0.10 peak≥0.25 pulse60–2000 settle80ms"
                    .format(Locale.US, v1, v2, v3)
            0x0D ->
                "ジャイロON odr=%.0f Hz bias_y=%+.2f"
                    .format(Locale.US, v1, v2)
            0x0E ->
                "ジャイロOFF"
            0x22 ->
                "hold中 実測 RMS=%.2f tilt=%.1f° |gy|=%.1f | 閾値 進入RMS≤3.0 中断>3.5×2 tilt≤15"
                    .format(Locale.US, v1, v2, v3)
            0x23 ->
                "動作完了 実測 elapsed=%.0fms gyPeak=%.0f ∫ωy=%.1f° | 期限<4500ms"
                    .format(Locale.US, v1, v2, v3)
            0x24 ->
                "掌下ゲート $reasonName  v1=%.2f v2=%.2f v3=%.2f"
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
            0x0A to "match_detail",
            0x0C to "stop_hand_lower",
            0x0D to "gyro_enabled",
            0x0E to "gyro_disabled",
            0x0F to "outbound_gyro",
            0x10 to "wait_reject",
            0x21 to "final_sample",
            0x22 to "hold_sample",
            0x23 to "motion_complete",
            0x24 to "palm_down_gate",
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
            0x24 to "motion_too_slow",
            0x25 to "palm_down_gravity_low",
            0x26 to "palm_down_gyro_angle_low",
            0x27 to "palm_down_xy_ratio_low",
            0x28 to "palm_down_gate_failed",
        )

        /** High-rate samples omitted from voice-history storage. */
        private val SKIP_STAGES_FOR_HISTORY = setOf(0x21)

        /** Always keep these when capping history size. */
        private val MILESTONE_STAGES = setOf(
            0x01, 0x02, 0x07, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F,
            0x23, 0x24, 0x80,
        )

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
         * Slice live diags for one recording.
         * Starts at the latest outbound_start inside the pre-roll window (avoids
         * previous-session contamination), drops final_sample, keeps milestones.
         */
        fun sliceForRecording(
            live: List<GestureDiagEntry>,
            recordingStartMs: Long,
            recordingStopMs: Long,
            preRollMs: Long = 8_000L,
            postRollMs: Long = 1_500L,
            maxEntries: Int = 60,
        ): List<GestureDiagEntry> {
            if (recordingStartMs <= 0L) return emptyList()
            val windowFrom = recordingStartMs - preRollMs
            val to = if (recordingStopMs > 0L) {
                recordingStopMs + postRollMs
            } else {
                recordingStartMs + 30_000L
            }
            val inWindow = live.filter { e ->
                e.receivedAtMs in windowFrom..to && e.stage !in SKIP_STAGES_FOR_HISTORY
            }
            if (inWindow.isEmpty()) return emptyList()

            // Prefer sequence start at last outbound_start before recording start.
            val lastOutbound = inWindow
                .filter { it.stage == 0x01 && it.receivedAtMs <= recordingStartMs }
                .maxByOrNull { it.receivedAtMs }
            val fromMs = lastOutbound?.receivedAtMs ?: inWindow.first().receivedAtMs
            val filtered = inWindow.filter { it.receivedAtMs >= fromMs }
            if (filtered.isEmpty()) return emptyList()

            val t0 = filtered.first().receivedAtMs
            val renumbered = filtered.map { e ->
                val rel = (e.receivedAtMs - t0).coerceIn(0L, 65535L).toInt()
                e.copy(tMs = rel, fromHistoryBatch = true)
            }
            if (renumbered.size <= maxEntries) return renumbered

            val milestones = renumbered.filter { it.stage in MILESTONE_STAGES }
            val others = renumbered.filter { it.stage !in MILESTONE_STAGES }
            val room = (maxEntries - milestones.size).coerceAtLeast(0)
            val keptOthers = if (room == 0) emptyList() else others.takeLast(room)
            return (milestones + keptOthers).sortedBy { it.tMs }.takeLast(maxEntries)
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
