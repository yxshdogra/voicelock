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

    private val listener: (Trigger) -> Unit = { t -> handle(t) }

    fun start() = TriggerBus.subscribe(listener)
    fun stop() = TriggerBus.unsubscribe(listener)

    private fun handle(t: Trigger) {
        Log.i(TAG, "trigger: $t")
        when (t) {
            is Trigger.KeywordDetected -> SpikeLog.trigger(ctx, "keyword", "index=${t.index}")
            Trigger.DoubleClap -> SpikeLog.trigger(ctx, "double_clap")
            Trigger.DebugLock -> SpikeLog.trigger(ctx, "debug_lock")
            Trigger.DismissOverlay -> SpikeLog.trigger(ctx, "dismiss_overlay")
            Trigger.DebugFindPhone -> SpikeLog.trigger(ctx, "debug_find_phone")
            Trigger.StopFindPhone -> SpikeLog.trigger(ctx, "stop_find_phone")
        }
    }

    private companion object { const val TAG = "ActionDispatcher" }
}
