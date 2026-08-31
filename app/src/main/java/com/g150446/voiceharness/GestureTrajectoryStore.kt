package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * One IMU sample from the node (event 0x37).
 *
 * Sampling is **not uniform**: the node polls the IMU every 25 ms while active
 * but every 50 ms in light sleep, so [tMs] is the only trustworthy time base —
 * the `periodMs` on [GestureTrajectory] is nominal. Gyro reads as 0 until the
 * gyroscope is powered up at the palm-up latch; [FLAG_GYRO_ENABLED] says which
 * samples carry real angular rate.
 */
data class GestureTrajectorySample(
    val tMs: Int,
    val flags: Int,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
) {
    val gyroEnabled: Boolean get() = (flags and FLAG_GYRO_ENABLED) != 0

    companion object {
        const val FLAG_GYRO_ENABLED = 0x01
        const val FLAG_GYRO_READ_OK = 0x02
        const val FLAG_GYRO_VALID = 0x04
    }
}

/**
 * A complete gesture attempt: everything from the palm-up candidate to the
 * verdict. [result] is 1 when the node fired a recording and 2 when the
 * sequence failed, so both classes of training data arrive over the same path.
 */
data class GestureTrajectory(
    val session: Int,
    val result: Int,
    val reason: Int,
    /** Nominal poll interval the node reports; real spacing varies, use tMs. */
    val periodMs: Int,
    val gyroBiasY: Float,
    val samples: List<GestureTrajectorySample>,
    val overflow: Boolean,
    val notifyError: Boolean,
    val declaredCount: Int,
    val receivedAtMs: Long,
) {
    val isMatch: Boolean get() = result == 1
    val complete: Boolean get() = samples.size == declaredCount && !overflow && !notifyError

    /** Indices begin never sent, i.e. chunks lost on the way. */
    val missingSamples: Int get() = (declaredCount - samples.size).coerceAtLeast(0)

    /** Measured spacing, so nobody resamples against the nominal period. */
    fun medianPeriodMs(): Int {
        if (samples.size < 2) return periodMs
        val deltas = samples.zipWithNext { a, b -> b.tMs - a.tMs }.sorted()
        return deltas[deltas.size / 2]
    }

    fun toCsv(): String = buildString {
        append("# session=").append(session)
            .append(" result=").append(result)
            .append(" reason=0x").append("%02X".format(reason))
            .append(" nominal_period_ms=").append(periodMs)
            .append(" median_period_ms=").append(medianPeriodMs())
            .append(" gyro_bias_y=").append("%.4f".format(Locale.US, gyroBiasY))
            .append(" declared=").append(declaredCount)
            .append(" received=").append(samples.size)
            .append(" overflow=").append(overflow)
            .append(" notify_error=").append(notifyError)
            .append(" received_at_ms=").append(receivedAtMs)
            .append('\n')
        append("t_ms,flags,ax,ay,az,gx,gy,gz\n")
        samples.forEach { s ->
            append(
                "%d,%d,%.5f,%.5f,%.5f,%.4f,%.4f,%.4f\n".format(
                    Locale.US, s.tMs, s.flags, s.ax, s.ay, s.az, s.gx, s.gy, s.gz,
                )
            )
        }
    }
}

/**
 * Assembles the 0x36 / 0x37 / 0x38 batch and writes each attempt to its own CSV.
 *
 * Trajectories are ~30 KB each, so they go to files rather than into the history
 * SharedPreferences — `voice_history_prefs.xml` is already past half a megabyte
 * and would not survive them. [HistoryEntry] carries only the file name.
 */
object GestureTrajectoryStore {
    private const val TAG = "GestureTrajectory"
    const val DIR_NAME = "gesture_trajectories"

    /** Roughly a week of clinic use; oldest are pruned once over. */
    private const val MAX_FILES = 400

    private var session = 0
    private var result = 0
    private var reason = 0
    private var periodMs = 0
    private var gyroBiasY = 0f
    private var declaredCount = 0
    private var overflow = false
    private var notifyError = false
    private var receiving = false

    /**
     * Indexed by the chunk's own start offset rather than appended.
     *
     * The node has been observed emitting two batches of the same window with
     * their chunks interleaved, and appending merged them into one oversized
     * trajectory (746 samples against a declared 377). Placing each sample at
     * the index the packet states makes a duplicate overwrite itself instead of
     * corrupting the window, whatever the sender does.
     */
    private val buffer = sortedMapOf<Int, GestureTrajectorySample>()

    /** Latest completed attempt, consumed when a recording ends. */
    @Volatile
    private var last: GestureTrajectory? = null

    @Volatile
    private var lastEndedAtMs: Long = 0L

    @Synchronized
    fun onBegin(
        session: Int,
        result: Int,
        reason: Int,
        sampleCount: Int,
        periodMs: Int,
        gyroBiasY: Float,
    ) {
        this.session = session
        this.result = result
        this.reason = reason
        this.declaredCount = sampleCount
        this.periodMs = periodMs
        this.gyroBiasY = gyroBiasY
        overflow = false
        notifyError = false
        receiving = true
        buffer.clear()
    }

    @Synchronized
    fun onChunk(startIndex: Int, samples: List<GestureTrajectorySample>) {
        if (!receiving) return
        samples.forEachIndexed { i, sample ->
            val index = startIndex + i
            // Anything past what begin declared is not part of this window.
            if (index < declaredCount) buffer[index] = sample
        }
    }

    @Synchronized
    fun onEnd(sentCount: Int, flags: Int): GestureTrajectory? {
        if (!receiving) return null
        receiving = false
        overflow = (flags and 0x01) != 0
        notifyError = (flags and 0x02) != 0
        val trajectory = GestureTrajectory(
            session = session,
            result = result,
            reason = reason,
            periodMs = periodMs,
            gyroBiasY = gyroBiasY,
            samples = buffer.values.toList(),
            overflow = overflow,
            notifyError = notifyError,
            declaredCount = declaredCount,
            receivedAtMs = System.currentTimeMillis(),
        )
        buffer.clear()
        last = trajectory
        lastEndedAtMs = trajectory.receivedAtMs
        Log.i(
            TAG,
            "Trajectory session=$session result=$result samples=${trajectory.samples.size}" +
                "/$declaredCount sent=$sentCount missing=${trajectory.missingSamples} " +
                "overflow=$overflow notifyError=$notifyError",
        )
        return trajectory
    }

    /**
     * The trajectory belonging to a recording that just stopped, or null.
     *
     * The node flushes a successful attempt inside its recording-stop handler, so
     * it lands within a second or so of the stop and well before ASR finishes.
     */
    @Synchronized
    fun takeForRecording(recordingStopMs: Long, windowMs: Long = 5_000L): GestureTrajectory? {
        if (recordingStopMs <= 0L) return null
        val candidate = last ?: return null
        if (!candidate.isMatch) return null
        // Bounds are on when the batch *arrived*, not on now: the caller runs
        // after ASR, seconds later, but the batch lands right after the stop.
        if (lastEndedAtMs < recordingStopMs - 1_000L) return null
        if (lastEndedAtMs > recordingStopMs + windowMs) return null
        last = null
        return candidate
    }

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** Writes [trajectory] and returns the file name to store on the history entry. */
    fun write(context: Context, entryId: String, trajectory: GestureTrajectory): String? {
        if (trajectory.samples.isEmpty()) return null
        val dir = directory(context)
        val file = File(dir, "$entryId.csv")
        return runCatching {
            file.writeText(trajectory.toCsv())
            prune(dir)
            file.name
        }.onFailure { Log.w(TAG, "Failed to write trajectory", it) }.getOrNull()
    }

    fun read(context: Context, fileName: String): String? {
        val file = File(directory(context), fileName)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        receiving = false
        last = null
        lastEndedAtMs = 0L
    }
}
