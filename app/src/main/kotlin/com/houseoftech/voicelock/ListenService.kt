package com.houseoftech.voicelock

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

/**
 * The always-on listener. A foreground service of type "microphone" that owns
 * ONE AudioRecord on a dedicated thread and feeds every frame to two consumers
 * that share the stream: the [KeywordEngine] (when one is available) and the
 * clap detector (always). No second AudioRecord, no second thread. Detections
 * and periodic battery samples go to SpikeLog.
 *
 * The engine is OPTIONAL and lives behind [KeywordEngine], so which one runs
 * (Porcupine today, openWakeWord next) is a one-line factory swap in
 * [startListening] and nothing downstream changes. Milestone 0 (engine accuracy)
 * is proven on a real device; the loop and the clap detector work without any
 * engine, so Milestones 1-3 are built and verified in the meantime.
 */
class ListenService : Service() {

    // Built on the audio thread, closed on main (onDestroy): @Volatile for the
    // cross-thread handoff.
    @Volatile private var engine: KeywordEngine? = null
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
        // The engine is best-effort and built on the audio thread (Vosk's model
        // load is slow -- never on main); without it the service still runs the
        // mic loop and the clap detector, which is all M1-M3 need.
        SpikeLog.serviceStartedAt = SystemClock.elapsedRealtime()
        running = true
        audioThread = Thread(::audioLoop, "voicelock-audio").apply { isDaemon = true; start() }
        SpikeLog.battery(this)
        main.postDelayed(batterySampler, BATTERY_INTERVAL_MS)
    }

    /**
     * The one audio thread. Owns the AudioRecord for its whole life so the
     * service can never release it out from under a read in progress; stopping
     * is a flag flip plus a short join.
     */
    private fun audioLoop() {
        // Prefer Vosk (Apache-2.0, Indian-English); Porcupine stays as a paid
        // fallback until Vosk passes M0. Built here so the slow model load is off
        // the main thread. Null = no model/key: mic loop + clap detector only.
        engine = VoskEngine.buildOrNull(this) ?: PorcupineEngine.buildOrNull(this)

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

                engine?.let { e ->
                    val idx = e.accept(frame)
                    if (idx >= 0) {
                        SpikeLog.detect(this, idx, e.lastMatchText)
                        TriggerBus.fire(Trigger.KeywordDetected(idx))
                    }
                    e.drainHeard()?.let { SpikeLog.heard(this, it) }
                }
                // The CLAP detector alone is gated while find-phone rings: the mic
                // hears our own alarm at max volume and would re-trigger it (the
                // M4-2 feedback loop). The keyword engine must keep running --
                // gating it too meant one stray clap killed voice unlock for up
                // to 60 s, and silently swallowed an entire M0 trial.
                if (dispatcher.isFindPhoneActive) continue

                val clap = clapDetector.onFrame(frame)
                // TEMPORARY (B2c): record the real onset envelope, accepted or not.
                clapDetector.drainEnvelope()?.let { env ->
                    SpikeLog.envelope(this, env, clapDetector.lastEnvelopeConfirmed)
                }
                clap?.let {
                    clapDetector.lastSpike?.let { sp -> SpikeLog.clap(this, sp.db, sp.rms) }
                    TriggerBus.fire(it)
                }
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(batterySampler)
        running = false
        audioThread?.join(500)
        audioThread = null
        engine?.close()
        engine = null
        SpikeLog.service(this, "stop")
        SpikeLog.serviceStartedAt = 0L
        dispatcher.stop()
        super.onDestroy()
    }

    companion object {
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
