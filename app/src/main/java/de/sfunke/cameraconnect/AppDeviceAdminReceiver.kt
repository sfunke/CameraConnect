package de.sfunke.cameraconnect

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.widget.Toast

/**
 * Created by Steffen Funke on 2019-03-10.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {
    private fun showToast(context: Context, msg: String) {
        context.getString(R.string.admin_receiver_status, msg).let { status ->
            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onEnabled(context: Context, intent: Intent) =
        showToast(context, "onEnabled")

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "onDisableRequested"

    override fun onDisabled(context: Context, intent: Intent) =
        showToast(context, "onDisabled")

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: UserHandle) =
        showToast(context, "onPasswordChanged")

}