package com.houseoftech.voicelock

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Milestone 0 measurement instrument.
 *
 * Everything the "done" test needs is derived from this one append-only JSONL
 * file: true positives (the user said the phrase and a detection landed),
 * false positives (a detection landed when nobody spoke it -- the user marks
 * these), and battery drain per hour (periodic samples while the service is
 * up). Append-only and flushed per line so a service kill mid-session loses
 * at most one entry, and the file is the evidence even if the UI never opens.
 *
 * Rows:
 *   {"t":<epoch ms>,"up":<ms since service start>,"kind":"detect","kw":<index>}
 *   {"t":...,"up":...,"kind":"false_positive"}         -- user-marked, refers to the last detect
 *   {"t":...,"up":...,"kind":"battery","pct":<0-100>,"charging":<bool>}
 *   {"t":...,"up":...,"kind":"service","event":"start"|"stop"|"error","msg":...}
 */
object SpikeLog {
    private const val FILE = "spike-log.jsonl"

    val detections = AtomicInteger(0)
    val falsePositives = AtomicInteger(0)
    @Volatile var lastDetectionAt: Long = 0L
    @Volatile var lastBatteryPct: Int = -1
    @Volatile var serviceStartedAt: Long = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun append(ctx: Context, row: JSONObject) {
        row.put("t", System.currentTimeMillis())
        row.put("up", if (serviceStartedAt == 0L) -1 else SystemClock.elapsedRealtime() - serviceStartedAt)
        file(ctx).appendText(row.toString() + "\n")
    }

    fun detect(ctx: Context, keywordIndex: Int) {
        detections.incrementAndGet()
        lastDetectionAt = System.currentTimeMillis()
        append(ctx, JSONObject().put("kind", "detect").put("kw", keywordIndex))
    }

    /** The user pressed "that was a false positive" after a detection they did not cause. */
    fun falsePositive(ctx: Context) {
        falsePositives.incrementAndGet()
        append(ctx, JSONObject().put("kind", "false_positive"))
    }

    fun battery(ctx: Context) {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        lastBatteryPct = pct
        append(ctx, JSONObject().put("kind", "battery").put("pct", pct).put("charging", charging))
    }

    fun service(ctx: Context, event: String, msg: String? = null) {
        val row = JSONObject().put("kind", "service").put("event", event)
        if (msg != null) row.put("msg", msg)
        append(ctx, row)
    }

    /** Whole log, newest last. For the on-device viewer and for pulling via adb. */
    fun readAll(ctx: Context): String = file(ctx).takeIf { it.exists() }?.readText() ?: ""

    fun clear(ctx: Context) {
        file(ctx).delete()
        detections.set(0); falsePositives.set(0)
        lastDetectionAt = 0L; lastBatteryPct = -1
    }
}
