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
 *   {"t":<epoch ms>,"up":<ms since service start>,"kind":"detect","kw":<index>,"text":<what was heard>}
 *   {"t":...,"up":...,"kind":"heard","text":<a finished utterance that did NOT match>}
 *   {"t":...,"up":...,"kind":"false_positive"}         -- user-marked, refers to the last detect
 *   {"t":...,"up":...,"kind":"battery","pct":<0-100>,"charging":<bool>}
 *   {"t":...,"up":...,"kind":"service","event":"start"|"stop"|"error","msg":...}
 *   {"t":...,"up":...,"kind":"trigger","name":<trigger>,"msg":...}   -- a Trigger reached the dispatcher
 *   {"t":...,"up":...,"kind":"clap","db":<spike dB>,"rms":<spike rms>}  -- a double clap completed
 *   {"t":...,"up":...,"kind":"envelope","rms":[...],"ok":<bool>}  -- onset energy curve, for clap tuning
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

    /**
     * A keyword fired. [text] is what the engine actually recognised, when it can
     * say (Vosk can; Porcupine cannot) -- without it a detect row cannot be told
     * apart from a false positive after the fact, which is the whole M0 score.
     */
    fun detect(ctx: Context, keywordIndex: Int, text: String? = null) {
        detections.incrementAndGet()
        lastDetectionAt = System.currentTimeMillis()
        val row = JSONObject().put("kind", "detect").put("kw", keywordIndex)
        if (text != null) row.put("text", text)
        append(ctx, row)
    }

    /**
     * A finished utterance the engine recognised but which did NOT match the wake
     * phrase. These are the near-misses: the denominator that shows whether the
     * grammar is too loose (firing on other speech) or too tight (never firing).
     */
    fun heard(ctx: Context, text: String) {
        append(ctx, JSONObject().put("kind", "heard").put("text", text))
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

    /**
     * The RMS envelope after a loud sharp onset, and whether the detector
     * accepted it as a transient. Claps and speech are separated by the SHAPE of
     * the energy curve (a clap peaks at its onset and collapses; speech keeps
     * pumping energy in), so these rows are what set the rule instead of
     * guesswork -- and what any future re-tune will need.
     */
    fun envelope(ctx: Context, rms: List<Double>, confirmed: Boolean) {
        val arr = org.json.JSONArray()
        rms.forEach { arr.put(Math.round(it).toInt()) }
        append(ctx, JSONObject().put("kind", "envelope").put("rms", arr).put("ok", confirmed))
    }

    /** A double clap completed. [db]/[rms] are the completing spike's, for threshold tuning. */
    fun clap(ctx: Context, db: Double, rms: Double) {
        append(ctx, JSONObject().put("kind", "clap").put("db", db).put("rms", rms))
    }

    /** A Trigger reached the dispatcher. Which mechanic ran (if any) is logged separately. */
    fun trigger(ctx: Context, name: String, msg: String? = null) {
        val row = JSONObject().put("kind", "trigger").put("name", name)
        if (msg != null) row.put("msg", msg)
        append(ctx, row)
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
