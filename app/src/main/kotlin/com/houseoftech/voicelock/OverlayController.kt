package com.houseoftech.voicelock

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Our lock screen: a full-screen TYPE_APPLICATION_OVERLAY window added by the
 * running service. Plain Views, not Compose -- a ComposeView outside an
 * Activity needs a hand-built lifecycle owner trio, which is not worth it for
 * mechanics work.
 *
 * Honesty is built in: the view reads the current LockMode and says, in words,
 * whether it IS the lock screen (no keyguard) or merely sits behind the real
 * one (secure keyguard). Never let the overlay be mistaken for the OS keyguard.
 *
 * Any touch fires Trigger.StopFindPhone (used by Milestone 3) but does NOT
 * dismiss the overlay; only the unlock Trigger (or the debug button) does.
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing get() = view != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    fun show() {
        if (view != null || !canDraw()) return
        val mode = ModeDecider.decide(ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val v = build(mode)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        // No NOT_TOUCHABLE / NOT_FOCUSABLE: the window consumes every tap, so the
        // launcher underneath is inert while we are up.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        view = v
        SpikeLog.service(ctx, "overlay", "shown mode=$mode")
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: IllegalArgumentException) {}
        view = null
        SpikeLog.service(ctx, "overlay", "hidden")
    }

    private fun build(mode: LockMode): View {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F00E0E12"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(TextView(ctx).apply {
                text = "VoiceLock"
                setTextColor(Color.WHITE)
                textSize = 32f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = when (mode) {
                    LockMode.NO_KEYGUARD ->
                        "Locked. Say your unlock phrase.\n\n(This screen is your lock screen -- your phone has no PIN set.)"
                    LockMode.SECURE_KEYGUARD ->
                        "Voice lock is on.\n\n(Your PIN still protects your phone. This screen appears after you unlock it.)"
                }
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            addView(Button(ctx).apply {
                text = "Unlock (debug)"
                setOnClickListener { TriggerBus.fire(Trigger.DismissOverlay) }
            })
            addView(Button(ctx).apply {
                text = "Found it"
                setOnClickListener { TriggerBus.fire(Trigger.StopFindPhone) }
            })
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) TriggerBus.fire(Trigger.StopFindPhone)
                false
            }
        }
    }
}
