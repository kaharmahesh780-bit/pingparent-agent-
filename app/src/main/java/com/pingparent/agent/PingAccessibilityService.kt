package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar

class PingAccessibilityService : AccessibilityService() {

    private var db: FirebaseFirestore? = null
    private var docListener: ListenerRegistration? = null
    private val blockedPackages = HashSet<String>()
    
    private var isDevicePaused = false
    private var isBedtimeEnabled = false
    private var bedtimeStartMinutes = 22 * 60 // 22:00
    private var bedtimeEndMinutes = 6 * 60    // 06:00

    private val safeSystemPackages = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.google.android.apps.nexuslauncher",
        "com.motorola.launcher3",
        "com.sec.android.app.launcher",
        "com.google.android.inputmethod.latin"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            db = FirebaseFirestore.getInstance()
            attachFirestoreSync()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun attachFirestoreSync() {
        try {
            val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
            var deviceId = prefs.getString("pairing_code", null) 
                ?: prefs.getString("paired_code", "") ?: ""

            if (deviceId.isEmpty() || deviceId == "PG-0000") {
                deviceId = "PG-9380"
            }

            val firestore = db ?: FirebaseFirestore.getInstance()
            docListener?.remove()
            docListener = firestore.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    
                    try {
                        // 1. Blocked Apps Sync
                        val list = snapshot.get("blockedApps") as? List<*>
                        synchronized(blockedPackages) {
                            blockedPackages.clear()
                            list?.forEach { item ->
                                if (item is String) blockedPackages.add(item.trim())
                            }
                        }

                        // 2. Bedtime Sync
                        val bedtimeMap = snapshot.get("bedtime") as? Map<*, *>
                        if (bedtimeMap != null) {
                            isBedtimeEnabled = bedtimeMap["enabled"] as? Boolean ?: false
                            val startStr = (bedtimeMap["startTime"] as? String) ?: "22:00"
                            val endStr = (bedtimeMap["endTime"] as? String) ?: "06:00"
                            bedtimeStartMinutes = parseTimeToMinutes(startStr)
                            bedtimeEndMinutes = parseTimeToMinutes(endStr)
                        }

                        // 3. Pause Screen Sync
                        isDevicePaused = snapshot.getBoolean("isPaused") ?: false

                        // Check state immediately
                        if (isDevicePaused || (isBedtimeEnabled && isNowInBedtime())) {
                            triggerHomeScreen()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val rawPackage = event.packageName?.toString() ?: return
            val packageName = rawPackage.trim()

            if (safeSystemPackages.contains(packageName)) return

            // 1. Remote Instant Lock
            if (isDevicePaused) {
                triggerHomeScreen()
                showToast("Device is Locked by Parent")
                return
            }

            // 2. Bedtime Lock
            if (isBedtimeEnabled && isNowInBedtime()) {
                triggerHomeScreen()
                showToast("Bedtime Active: Phone Locked")
                return
            }

            // 3. App Blocking Check (Direct Package Match)
            val shouldBlock = synchronized(blockedPackages) {
                blockedPackages.contains(packageName)
            }

            if (shouldBlock) {
                triggerHomeScreen()
                showToast("App is Blocked by Parent")
                logViolation(packageName)
                return
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerHomeScreen() {
        Handler(Looper.getMainLooper()).post {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {}
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {}
        }
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        return try {
            val parts = timeStr.split(":")
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            (h * 60) + m
        } catch (e: Exception) {
            0
        }
    }

    private fun isNowInBedtime(): Boolean {
        return try {
            val cal = Calendar.getInstance()
            val currentMinutes = (cal.get(Calendar.HOUR_OF_DAY) * 60) + cal.get(Calendar.MINUTE)

            if (bedtimeStartMinutes <= bedtimeEndMinutes) {
                currentMinutes in bedtimeStartMinutes..bedtimeEndMinutes
            } else {
                currentMinutes >= bedtimeStartMinutes || currentMinutes < bedtimeEndMinutes
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun logViolation(blockedApp: String) {
        try {
            val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
            var deviceId = prefs.getString("pairing_code", null) 
                ?: prefs.getString("paired_code", "") ?: ""

            if (deviceId.isEmpty() || deviceId == "PG-0000") {
                deviceId = "PG-9380"
            }

            val alertData = hashMapOf(
                "keyword" to "Blocked App Opened",
                "app" to blockedApp,
                "category" to "Shield Block",
                "timestamp" to FieldValue.serverTimestamp()
            )

            (db ?: FirebaseFirestore.getInstance()).collection("devices").document(deviceId)
                .collection("alerts")
                .add(alertData)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        docListener?.remove()
    }

    override fun onInterrupt() {}
}
