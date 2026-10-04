package com.dominik.cardvault.ui

/** Test-enabled diagnostics only. Never stores pixels, recognized text, filenames or exceptions. */
internal enum class OcrTimingEvent {
    CALL_CREATED, BEFORE_RECOGNIZER, RECOGNIZER_RETURNED, BEFORE_PROCESS, PROCESS_RETURNED,
    SUBMISSION_THROWN, TASK_COMPLETE, SERIAL_PROCESSED, LATE_IGNORED_BEFORE_QUEUE,
    LATE_IGNORED_AT_WORKER, LATE_IGNORED_AFTER_BATCH, BATCH_DEADLINE_POSTED,
    BATCH_DEADLINE_PROCESSED, BATCH_DELIVERED, SESSION_DEADLINE, CANCEL_REQUEST,
    RESULT_COMMITTED, CLEANUP_STARTED, NATIVE_CLOSE_REQUESTED, NATIVE_CLOSE_RETURNED,
    CLEANUP_RETURNED,
}
internal enum class OcrTimingOutcome { SUCCESS, FAILURE, CANCELLED }
internal data class OcrTimingCall(
    val id: Int, val probe: Boolean, val profile: String, val variant: String,
    val orientation: Int?, val regions: List<String>, val roi: List<Float>,
)
internal data class OcrTimingEntry(
    val ordinal: Int, val elapsedNanos: Long, val event: OcrTimingEvent,
    val call: OcrTimingCall?, val probe: Boolean?, val outcome: OcrTimingOutcome?,
)
internal data class OcrTimingSnapshot(
    val entries: List<OcrTimingEntry>, val observed: Int, val dropped: Int,
    val clockRegressed: Boolean, val clockFailures: Int,
)

/** No callbacks or I/O under this monitor. Copies are detached from the mutable recorder. */
internal class OcrTimingTrace(private val nowNanos: () -> Long) {
    private val started = nowNanos()
    private val entries = ArrayList<OcrTimingEntry>()
    private var nextCall = 0
    private var observed = 0
    private var previous = Long.MIN_VALUE
    private var clockRegressed = false
    private var clockFailures = 0

    @Synchronized fun newCall(
        probe: Boolean, profile: String, variant: String, orientation: Int?,
        regions: List<String>, roi: List<Float>,
    ): OcrTimingCall = OcrTimingCall(++nextCall, probe, profile, variant, orientation, regions.toList(), roi.toList())

    @Synchronized fun record(
        event: OcrTimingEvent, call: OcrTimingCall? = null, probe: Boolean? = call?.probe,
        outcome: OcrTimingOutcome? = null,
    ) {
        observed++
        if (entries.size >= MAX_ENTRIES) return
        val elapsed = try { nowNanos() - started } catch (_: Exception) {
            clockFailures++
            return
        }
        if (elapsed < 0 || elapsed < previous) clockRegressed = true
        previous = elapsed
        entries += OcrTimingEntry(observed, elapsed, event, call, probe, outcome)
    }

    @Synchronized fun snapshot() = OcrTimingSnapshot(
        entries.toList(), observed, observed - entries.size, clockRegressed, clockFailures,
    )

    companion object { const val MAX_ENTRIES = 256 }
}
