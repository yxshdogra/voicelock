package com.houseoftech.voicelock

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Lock the screen and arm the overlay to appear on the next screen-on.
 *
 * lockNow() needs Device Admin; without it we still arm, so the overlay shows
 * next time the screen wakes -- that is the degraded mode the onboarding copy
 * promises when the user declines admin.
 *
 * The screen broadcasts are registered at runtime on the running service.
 * ACTION_SCREEN_ON / ACTION_USER_PRESENT have never been manifest-deliverable,
 * and Android 14's rule against starting a foreground service from a background
 * broadcast does not apply: the service is already up and only adds a view.
 */
class LockController(private val ctx: Context, private val overlay: OverlayController) {

    @Volatile private var armed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> if (armed) overlay.show()
                // A secure keyguard was just passed. In that mode the overlay was
                // added behind it on SCREEN_ON and is now visible; nothing to do
                // except record that the user got here.
                Intent.ACTION_USER_PRESENT -> if (armed) SpikeLog.service(ctx, "overlay", "user_present (keyguard passed)")
            }
        }
    }

    fun start() {
        ctx.registerReceiver(
            screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
        )
    }

    fun stop() {
        try { ctx.unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        overlay.hide()
        armed = false
    }

    /** The lock phrase / clap / debug button. */
    fun lockAndArm() {
        armed = true
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (LockDeviceAdminReceiver.isActive(ctx)) {
            try {
                dpm.lockNow()
                SpikeLog.service(ctx, "lock", "lockNow() ok; armed")
            } catch (e: SecurityException) {
                SpikeLog.service(ctx, "lock", "lockNow() refused: ${e.message}; armed anyway")
                Log.w(TAG, "lockNow", e)
            }
        } else {
            // Degraded mode: no admin, so we cannot turn the screen off, but the
            // overlay will still greet the next screen-on.
            SpikeLog.service(ctx, "lock", "device admin inactive: armed only, showing overlay now")
            overlay.show()
        }
    }

    /** The unlock phrase / debug button. */
    fun dismiss() {
        armed = false
        overlay.hide()
        SpikeLog.service(ctx, "lock", "dismissed")
    }

    private companion object { const val TAG = "LockController" }
}
