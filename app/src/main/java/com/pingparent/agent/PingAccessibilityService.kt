package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar

class PingAccessibilityService : AccessibilityService() {

    private val TAG = "PingShield"

    private var db: FirebaseFirestore? = null
    private var docListener: ListenerRegistration? = null
    private var attachedDeviceId: String = ""
    private val blockedPackages = HashSet<String>()

    @Volatile private var isDevicePaused = false
    @Volatile private var isBedtimeEnabled = false
    @Volatile private var bedtimeStartMinutes = 22 * 60 // 22:00
    @Volatile private var bedtimeEndMinutes = 6 * 60    // 06:00

    private var lastHomeAt = 0L
    private var lastToastAt = 0L
    private var launcherPackages: Set<String> = emptySet()

    private val safeSystemPackages = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.google.android.apps.nexuslauncher",
        "com.motorola.launcher3",
        "com.sec.android.app.launcher",
        "com.android.launcher3",
        "com.google.android.inputmethod.latin"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            db = FirebaseFirestore.getInstance()
            loadLauncherPackages()
            attachFirestoreSync()
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected error", e)
        }
    }

    // Device ka pairing code (MainActivity jo save karta hai)
    private fun readDeviceId(): String {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val id = prefs.getString("pairing_code", null)
            ?: prefs.getString("paired_code", "") ?: ""
        return if (id == "PG-0000") "" else id
    }

    // Default launcher(s) ko safe list mein daalta hai taaki HOME ka loop na bane
    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            launcherPackages = packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "launcher detect error", e)
        }
    }

    private fun attachFirestoreSync() {
        try {
            val deviceId = readDeviceId()
            if (deviceId.isEmpty()) {
                Log.w(TAG, "Pairing code abhi nahi mila, baad mein dobara try hoga")
                return
            }
            if (deviceId == attachedDeviceId && docListener != null) return

            attachedDeviceId = deviceId
            val firestore = db ?: FirebaseFirestore.getInstance()
            docListener?.remove()
            Log.d(TAG, "Listening to devices/$deviceId")

            docListener = firestore.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Snapshot error", error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                    try {
                        // 1. Blocked apps
                        val list = snapshot.get("blockedApps") as? List<*>
                        synchronized(blockedPackages) {
                            blockedPackages.clear()
                            list?.forEach { item ->
                                if (item is String) blockedPackages.add(item.trim())
                            }
                        }

                        // 2. Bedtime (Number kuch bhi ho: Long/Double/Int)
                        val bedtimeMap = snapshot.get("bedtime") as? Map<*, *>
                        if (bedtimeMap != null) {
                            isBedtimeEnabled = bedtimeMap["enabled"] as? Boolean ?: false

                            val sh = (bedtimeMap["startHour"] as? Number)?.toInt()
                            val sm = (bedtimeMap["startMinute"] as? Number)?.toInt()
                            val eh = (bedtimeMap["endHour"] as? Number)?.toInt()
                            val em = (bedtimeMap["endMinute"] as? Number)?.toInt()

                            if (sh != null && sm != null && eh != null && em != null) {
                                bedtimeStartMinutes = sh * 60 + sm
                                bedtimeEndMinutes = eh * 60 + em
                            } else {
                                bedtimeStartMinutes =
                                    parseTimeToMinutes((bedtimeMap["startTime"] as? String) ?: "22:00")
                                bedtimeEndMinutes =
                                    parseTimeToMinutes((bedtimeMap["endTime"] as? String) ?: "06:00")
                            }
                        } else {
                            isBedtimeEnabled = false
                        }

                        // 3. Pause / force block
                        val paused = snapshot.getBoolean("isPaused") ?: false
                        val forceBlocked = snapshot.getBoolean("isForceBlocked") ?: false
                        isDevicePaused = paused || forceBlocked

                        Log.d(
                            TAG,
                            "Sync: blocked=${blockedPackages.size} bedtime=$isBedtimeEnabled " +
                                "$bedtimeStartMinutes-$bedtimeEndMinutes paused=$isDevicePaused " +
                                "inBedtime=${isNowInBedtime()}"
                        )

                        if (isDevicePaused || (isBedtimeEnabled && isNowInBedtime())) {
                            triggerHomeScreen()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Sync parse error", e)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "attach error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val packageName = event.packageName?.toString()?.trim() ?: return

            // Agar pairing code baad mein bana ya badla, listener dobara jodo
            if (readDeviceId() != attachedDeviceId) attachFirestoreSync()

            if (safeSystemPackages.contains(packageName)) return
            if (launcherPackages.contains(packageName)) return

            // 1. Remote lock
            if (isDevicePaused) {
                triggerHomeScreen()
                showToast("Device is Locked by Parent")
                return
            }

            // 2. Bedtime lock
            if (isBedtimeEnabled && isNowInBedtime()) {
                triggerHomeScreen()
                showToast("Bedtime Active: Phone Locked")
                return
            }

            // 3. App blocking
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
            Log.e(TAG, "event error", e)
        }
    }

    private fun triggerHomeScreen() {
        val now = System.currentTimeMillis()
        if (now - lastHomeAt < 700) return // spam / loop rokne ke liye
        lastHomeAt = now
        Handler(Looper.getMainLooper()).post {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "home action error", e)
            }
        }
    }

    private fun showToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 2500) return
        lastToastAt = now
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
            }
        }
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        return try {
            val parts = timeStr.trim().split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun isNowInBedtime(): Boolean {
        return try {
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val start = bedtimeStartMinutes
            val end = bedtimeEndMinutes

            when {
                start == end -> false
                start < end -> now in start until end
                else -> now >= start || now < end // raat 22:00 se subah 06:00
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun logViolation(blockedApp: String) {
        try {
            val deviceId = readDeviceId()
            if (deviceId.isEmpty()) return

            val alertData = hashMapOf(
                "keyword" to "Blocked App Opened",
                "app" to blockedApp,
                "category" to "Shield Block",
                "timestamp" to FieldValue.serverTimestamp()
            )

            (db ?: FirebaseFirestore.getInstance()).collection("devices").document(deviceId)
                .collection("alerts")
                .add(alertData)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        docListener?.remove()
        docListener = null
        attachedDeviceId = ""
    }

    override fun onInterrupt() {}
}
