package com.houseoftech.voicelock

/**
 * Which of the two honest modes the device is in. Pure, so it is unit-tested.
 *
 * TYPE_APPLICATION_OVERLAY windows are z-ordered BELOW the secure keyguard by
 * design; there is no flag that changes that for a non-Activity window. So:
 *
 *  - NO_KEYGUARD: the user set Android's own lock to None/Swipe. Our overlay
 *    IS the lock screen and the phrase genuinely unlocks the phone.
 *  - SECURE_KEYGUARD: a PIN/pattern/biometric is set. The real keyguard comes
 *    FIRST; our overlay can only appear after it. Voice can lock (Device Admin)
 *    but cannot meaningfully unlock. The UI must say so.
 */
enum class LockMode { NO_KEYGUARD, SECURE_KEYGUARD }

object ModeDecider {
    fun decide(isDeviceSecure: Boolean): LockMode =
        if (isDeviceSecure) LockMode.SECURE_KEYGUARD else LockMode.NO_KEYGUARD
}
