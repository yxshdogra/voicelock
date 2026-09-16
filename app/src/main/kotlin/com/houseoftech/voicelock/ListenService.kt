package com.houseoftech.voicelock

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * The always-on listener. A foreground service of type "microphone" that owns
 * ONE AudioRecord on a dedicated thread and feeds every frame to two consumers
 * that share the stream: Porcupine keyword spotting (when an AccessKey and
 * model are present) and the clap detector (always). No second AudioRecord,
 * no second thread. Detections and periodic battery samples go to SpikeLog.
 *
 * Porcupine is OPTIONAL here. Milestone 0 (its accuracy) is blocked on a real
 * device and an AccessKey; the loop and the clap detector must work without it,
 * so Milestones 1-3 can be built and verified in the meantime.
 *
 * Milestone 0 has two sub-steps and this service supports both:
 *  - M0a: a BUILT-IN keyword (PORCUPINE) proves the pipeline end to end with
 *    nothing but an AccessKey.
 *  - M0b: a CUSTOM phrase, trained on console.picovoice.ai and dropped in as
 *    assets/keywords/custom.ppn, is the actual accuracy test. If that file is
 *    present it is used instead of the built-in keyword.
 */
class ListenService : Service() {

    private var porcupine: Porcupine? = null
    private val clapDetector = ClapDetector(frameLength = FRAME_LENGTH, sampleRate = SAMPLE_RATE)
    @Volatile private var running = false
    private var audioThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dispatcher: ActionDispatcher
    private val batterySampler = object : Runnable {
        override fun run() {
            SpikeLog.battery(this@ListenService)
            main.postDelayed(this, BATTERY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        dispatcher = ActionDispatcher(applicationContext).also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!running) startListening()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startListening() {
        // The recogniser is best-effort: without an AccessKey the service still
        // runs the mic loop and the clap detector, which is all M1-M3 need.
        porcupine = buildPorcupineOrNull()
        SpikeLog.serviceStartedAt = SystemClock.elapsedRealtime()
        running = true
        audioThread = Thread(::audioLoop, "voicelock-audio").apply { isDaemon = true; start() }
        SpikeLog.battery(this)
        main.postDelayed(batterySampler, BATTERY_INTERVAL_MS)
    }

    private fun buildPorcupineOrNull(): Porcupine? {
        val key = BuildConfig.PICOVOICE_ACCESS_KEY
        if (key.isBlank()) {
            SpikeLog.service(this, "start", "no PICOVOICE_ACCESS_KEY: mic loop + clap detector only (M0 needs the key)")
            return null
        }
        return try {
            val builder = Porcupine.Builder().setAccessKey(key)
            val custom = customKeywordPath()
            if (custom != null) {
                builder.setKeywordPaths(arrayOf(custom))
                SpikeLog.service(this, "start", "custom keyword: $custom")
            } else {
                builder.setKeywords(arrayOf(Porcupine.BuiltInKeyword.PORCUPINE))
                SpikeLog.service(this, "start", "built-in keyword: PORCUPINE (M0a); drop assets/keywords/custom.ppn for M0b")
            }
            builder.build(this).also {
                // The loop hardcodes Porcupine's fixed frame geometry so it can run
                // without the engine; make a mismatch loud rather than silent.
                check(it.frameLength == FRAME_LENGTH && it.sampleRate == SAMPLE_RATE) {
                    "Porcupine wants ${it.frameLength}@${it.sampleRate}, loop uses $FRAME_LENGTH@$SAMPLE_RATE"
                }
            }
        } catch (e: PorcupineException) {
            SpikeLog.service(this, "error", "porcupine init: ${e.message}")
            Log.e(TAG, "porcupine init failed; continuing without it", e)
            null
        }
    }

    /**
     * The one audio thread. Owns the AudioRecord for its whole life so the
     * service can never release it out from under a read in progress; stopping
     * is a flag flip plus a short join.
     */
    private fun audioLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_LENGTH * 2 * 4),
            )
        } catch (e: SecurityException) {
            SpikeLog.service(this, "error", "RECORD_AUDIO not granted: ${e.message}")
            running = false
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            SpikeLog.service(this, "error", "AudioRecord failed to initialise (state=${record.state})")
            record.release()
            running = false
            return
        }
        val frame = ShortArray(FRAME_LENGTH)
        record.startRecording()
        try {
            while (running) {
                val n = record.read(frame, 0, FRAME_LENGTH)
                if (n != FRAME_LENGTH) continue

                porcupine?.let { p ->
                    val idx = try { p.process(frame) } catch (e: PorcupineException) { Log.w(TAG, "process", e); -1 }
                    if (idx >= 0) {
                        SpikeLog.detect(this, idx)
                        TriggerBus.fire(Trigger.KeywordDetected(idx))
                    }
                }
                clapDetector.onFrame(frame)?.let {
                    clapDetector.lastSpike?.let { sp -> SpikeLog.clap(this, sp.db, sp.rms) }
                    TriggerBus.fire(it)
                }
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }

    /**
     * A custom .ppn shipped in assets is copied to internal storage once, since
     * Porcupine wants a real file path. Returns null when there is no custom
     * keyword yet (M0a).
     */
    private fun customKeywordPath(): String? {
        val out = File(filesDir, "custom.ppn")
        if (out.exists()) return out.absolutePath
        return try {
            assets.open("keywords/custom.ppn").use { input -> out.outputStream().use { input.copyTo(it) } }
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(batterySampler)
        running = false
        audioThread?.join(500)
        audioThread = null
        try {
            porcupine?.delete()
        } catch (e: PorcupineException) {
            Log.w(TAG, "porcupine teardown", e)
        }
        porcupine = null
        SpikeLog.service(this, "stop")
        SpikeLog.serviceStartedAt = 0L
        dispatcher.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ListenService"
        private const val CHANNEL = "listening"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.houseoftech.voicelock.STOP"
        /** Porcupine's fixed geometry: 512 samples per frame at 16 kHz = 32 ms. */
        const val FRAME_LENGTH = 512
        const val SAMPLE_RATE = 16_000
        /** Five minutes: fine enough to see drain per hour, coarse enough not to be the drain. */
        private const val BATTERY_INTERVAL_MS = 5 * 60 * 1000L

        fun start(ctx: Context) {
            val i = Intent(ctx, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP))
        }
    }
}
