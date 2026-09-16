package com.houseoftech.voicelock

import android.content.Context
import android.util.Log

/**
 * Turns Triggers into effects. Owned by ListenService, which is the one
 * long-lived component with an application context, so it can add overlay
 * windows and call DevicePolicyManager without an Activity.
 *
 * Step 1 of the build: log only. Each later milestone replaces one branch with
 * the real mechanic and leaves the others alone.
 */
class ActionDispatcher(private val ctx: Context) {

    private val overlay = OverlayController(ctx)
    private val lock = LockController(ctx, overlay)
    private val findPhone = FindPhoneController(ctx)

    private val listener: (Trigger) -> Unit = { t -> handle(t) }

    fun start() {
        lock.start()
        TriggerBus.subscribe(listener)
    }

    fun stop() {
        TriggerBus.unsubscribe(listener)
        findPhone.stop("service stopped")
        lock.stop()
    }

    private fun handle(t: Trigger) {
        Log.i(TAG, "trigger: $t")
        when (t) {
            // Until Milestone 0 assigns phrases to actions, any keyword locks.
            is Trigger.KeywordDetected -> { SpikeLog.trigger(ctx, "keyword", "index=${t.index}"); lock.lockAndArm() }
            // A double clap is the find-phone gesture: the product promise is
            // "clap and it rings, even on silent".
            Trigger.DoubleClap -> { SpikeLog.trigger(ctx, "double_clap"); findPhone.start() }
            Trigger.DebugLock -> { SpikeLog.trigger(ctx, "debug_lock"); lock.lockAndArm() }
            Trigger.DismissOverlay -> { SpikeLog.trigger(ctx, "dismiss_overlay"); lock.dismiss() }
            Trigger.DebugFindPhone -> { SpikeLog.trigger(ctx, "debug_find_phone"); findPhone.start() }
            // Any touch on the overlay, its "Found it" button, or the debug button.
            Trigger.StopFindPhone -> { SpikeLog.trigger(ctx, "stop_find_phone"); findPhone.stop() }
        }
    }

    private companion object { const val TAG = "ActionDispatcher" }
}
