package com.houseoftech.voicelock

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
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
 * The always-on listener. A foreground service of type "microphone" holding
 * one PorcupineManager, which owns the AudioRecord and runs keyword spotting
 * on its own thread. Detections and periodic battery samples go to SpikeLog.
 *
 * Milestone 0 has two sub-steps and this service supports both:
 *  - M0a: a BUILT-IN keyword (PORCUPINE) proves the pipeline end to end with
 *    nothing but an AccessKey.
 *  - M0b: a CUSTOM phrase, trained on console.picovoice.ai and dropped in as
 *    assets/keywords/custom.ppn, is the actual accuracy test. If that file is
 *    present it is used instead of the built-in keyword.
 */
class ListenService : Service() {

    private var porcupine: PorcupineManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val batterySampler = object : Runnable {
        override fun run() {
            SpikeLog.battery(this@ListenService)
            main.postDelayed(this, BATTERY_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (porcupine == null) startListening()
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
        val key = BuildConfig.PICOVOICE_ACCESS_KEY
        if (key.isBlank()) {
            SpikeLog.service(this, "error", "PICOVOICE_ACCESS_KEY is empty -- add it to local.properties")
            stopSelf()
            return
        }
        try {
            val builder = PorcupineManager.Builder().setAccessKey(key)
            val custom = customKeywordPath()
            if (custom != null) {
                builder.setKeywordPaths(arrayOf(custom))
                SpikeLog.service(this, "start", "custom keyword: $custom")
            } else {
                builder.setKeywords(arrayOf(Porcupine.BuiltInKeyword.PORCUPINE))
                SpikeLog.service(this, "start", "built-in keyword: PORCUPINE (M0a); drop assets/keywords/custom.ppn for M0b")
            }
            SpikeLog.serviceStartedAt = SystemClock.elapsedRealtime()
            porcupine = builder.build(this) { keywordIndex ->
                // Runs on Porcupine's audio thread. Log and return; keep it cheap.
                SpikeLog.detect(this, keywordIndex)
                Log.i(TAG, "detected keyword index=$keywordIndex")
            }
            porcupine?.start()
            SpikeLog.battery(this)
            main.postDelayed(batterySampler, BATTERY_INTERVAL_MS)
        } catch (e: PorcupineException) {
            SpikeLog.service(this, "error", "porcupine init: ${e.message}")
            Log.e(TAG, "porcupine init failed", e)
            stopSelf()
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
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (e: PorcupineException) {
            Log.w(TAG, "porcupine teardown", e)
        }
        porcupine = null
        SpikeLog.service(this, "stop")
        SpikeLog.serviceStartedAt = 0L
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ListenService"
        private const val CHANNEL = "listening"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.houseoftech.voicelock.STOP"
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
