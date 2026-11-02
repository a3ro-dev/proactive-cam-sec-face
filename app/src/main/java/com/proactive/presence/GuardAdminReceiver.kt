package com.proactive.presence

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
