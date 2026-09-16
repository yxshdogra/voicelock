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

    private val listener: (Trigger) -> Unit = { t -> handle(t) }

    fun start() {
        lock.start()
        TriggerBus.subscribe(listener)
    }

    fun stop() {
        TriggerBus.unsubscribe(listener)
        lock.stop()
    }

    private fun handle(t: Trigger) {
        Log.i(TAG, "trigger: $t")
        when (t) {
            // Until Milestone 0 assigns phrases to actions, any keyword locks.
            is Trigger.KeywordDetected -> { SpikeLog.trigger(ctx, "keyword", "index=${t.index}"); lock.lockAndArm() }
            // A double clap is the find-phone gesture (M3); for now it also
            // exercises the lock path so M2 is testable by clapping.
            Trigger.DoubleClap -> { SpikeLog.trigger(ctx, "double_clap"); lock.lockAndArm() }
            Trigger.DebugLock -> { SpikeLog.trigger(ctx, "debug_lock"); lock.lockAndArm() }
            Trigger.DismissOverlay -> { SpikeLog.trigger(ctx, "dismiss_overlay"); lock.dismiss() }
            Trigger.DebugFindPhone -> SpikeLog.trigger(ctx, "debug_find_phone")
            Trigger.StopFindPhone -> SpikeLog.trigger(ctx, "stop_find_phone")
        }
    }

    private companion object { const val TAG = "ActionDispatcher" }
}
