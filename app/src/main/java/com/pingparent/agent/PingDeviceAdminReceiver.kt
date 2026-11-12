package com.pingparent.agent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class PingDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        logAlert(context, "Admin Disable Attempt")
        return "Parent ko alert bheja jayega. Isse band karne par uninstall protection hat jayegi."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        logAlert(context, "Uninstall Protection Removed")
    }

    private fun logAlert(context: Context, keyword: String) {
        try {
            val code = context.getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
                .getString("pairing_code", null) ?: return
            val data = hashMapOf(
                "keyword" to keyword,
                "app" to "Device Admin",
                "category" to "Tamper",
                "timestamp" to FieldValue.serverTimestamp()
            )
            FirebaseFirestore.getInstance().collection("devices").document(code)
                .collection("alerts")
                .add(data)
        } catch (e: Exception) {
        }
    }
}
