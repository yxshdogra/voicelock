package com.houseoftech.voicelock

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hands a Trigger from wherever it was produced to the main thread.
 *
 * The audio loop runs on its own thread and must stay cheap; BroadcastReceivers
 * and the debug UI arrive on the main thread already. Posting everything
 * through one main-thread Handler means listeners never need to think about
 * where a Trigger came from, and the overlay/WindowManager calls they make are
 * always on the thread that is allowed to make them.
 */
object TriggerBus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Trigger) -> Unit>()

    fun subscribe(listener: (Trigger) -> Unit) { listeners += listener }
    fun unsubscribe(listener: (Trigger) -> Unit) { listeners -= listener }

    /** Safe from any thread. */
    fun fire(trigger: Trigger) {
        main.post { for (l in listeners) l(trigger) }
    }
}
