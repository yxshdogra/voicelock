package com.houseoftech.voicelock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The Device Admin component. Declares nothing but force-lock (see
 * res/xml/device_admin.xml) and does nothing on enable/disable beyond logging;
 * its whole purpose is to make DevicePolicyManager.lockNow() legal for us.
 *
 * It does NOT block uninstall. A user can revoke it from Settings at any time,
 * and the onboarding copy says so explicitly -- "prevents uninstall" is the
 * malware pattern Play's reviewers look for.
 */
class LockDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        SpikeLog.service(context, "device_admin", "enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        SpikeLog.service(context, "device_admin", "disabled")
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, LockDeviceAdminReceiver::class.java)

        fun isActive(ctx: Context): Boolean =
            ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(ctx))

        /** The system's own Device Admin activation screen, with our explanation. */
        fun requestIntent(ctx: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Used only to turn the screen off when you say your lock phrase or clap. " +
                        "It does not read your data and does not stop you uninstalling the app.",
                )
    }
}
