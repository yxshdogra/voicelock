package com.houseoftech.voicelock

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * "Find my phone": ring at full volume, strobe the torch, vibrate -- until any
 * touch, the "Found it" button, or a timeout. All three converge on [stop],
 * which is idempotent and restores whatever it changed.
 *
 * Why STREAM_ALARM: the phone is very likely on silent or in Do Not Disturb,
 * which is the whole reason the user cannot find it. Silent/vibrate ringer
 * modes mute RING and NOTIFICATION, not ALARM; MUSIC has no DND semantics at
 * all. Alarms are exempt from DND by default, so this rings without any
 * ACCESS_NOTIFICATION_POLICY grant. If the user has personally switched the
 * Alarms exception off in their DND settings, nothing we could request would
 * override that choice, so we do not ask.
 *
 * Why no CAMERA permission: CameraManager.setTorchMode explicitly does not
 * require it. Devices without a flash unit (the emulator) are detected and the
 * strobe is skipped, logged, and everything else still runs.
 */
class FindPhoneController(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    // @Volatile: isActive is read from the audio thread (ListenService's mic
    // loop) to gate the detectors while the alarm rings; writes are on main.
    @Volatile private var player: MediaPlayer? = null
    private var previousAlarmVolume = -1
    private var torchId: String? = null
    private var torchOn = false
    private val timeout = Runnable { stop("timeout") }
    private val strobe = object : Runnable {
        override fun run() {
            val id = torchId ?: return
            torchOn = !torchOn
            try { cameraManager.setTorchMode(id, torchOn) } catch (e: CameraAccessException) { Log.w(TAG, "torch", e) }
            main.postDelayed(this, STROBE_MS)
        }
    }

    private val audioManager get() = ctx.getSystemService(AudioManager::class.java)
    private val cameraManager get() = ctx.getSystemService(CameraManager::class.java)

    val isActive get() = player != null

    fun start() {
        if (player != null) return
        SpikeLog.service(ctx, "find_phone", "start")

        // Ring.
        val am = audioManager
        previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            SpikeLog.service(ctx, "find_phone", "ring failed: ${e.message}")
            Log.e(TAG, "ring", e)
            null
        }

        // Torch: only if there is a flash unit.
        torchId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) { null }
        if (torchId == null) {
            SpikeLog.service(ctx, "find_phone", "no flash unit; torch strobe skipped")
        } else {
            main.post(strobe)
        }

        // Vibrate.
        vibrator().vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400, 600), 0),
        )

        main.postDelayed(timeout, TIMEOUT_MS)
    }

    fun stop(reason: String = "user") {
        if (player == null && torchId == null) return
        main.removeCallbacks(timeout)
        main.removeCallbacks(strobe)

        player?.let { p ->
            try { p.stop() } catch (_: IllegalStateException) {}
            p.release()
        }
        player = null

        torchId?.let { id ->
            try { cameraManager.setTorchMode(id, false) } catch (e: CameraAccessException) { Log.w(TAG, "torch off", e) }
        }
        torchId = null
        torchOn = false

        vibrator().cancel()

        if (previousAlarmVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            previousAlarmVolume = -1
        }
        SpikeLog.service(ctx, "find_phone", "stop ($reason)")
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }

    private companion object {
        const val TAG = "FindPhone"
        const val STROBE_MS = 300L
        const val TIMEOUT_MS = 60_000L
    }
}
