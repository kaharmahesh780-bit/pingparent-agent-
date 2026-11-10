package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PingAccessibilityService : AccessibilityService() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var blockedAppsListener: ListenerRegistration? = null
    private val blockedPackages = mutableSetOf<String>()
    
    // Remote Lock & Bedtime Flags
    private var isDevicePaused = false
    private var isBedtimeEnabled = false
    private var bedtimeStart = "22:00"
    private var bedtimeEnd = "06:00"

    private val blockedKeywords = listOf(
        "porn", "xxx", "adult", "sex", "gambling", "casino", "betting", "rummy", "hack"
    )

    private val settingsPackages = listOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.google.android.packageinstaller"
    )

    private val systemSafePackages = listOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.motorola.launcher3",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.mi.android.globalminusscreen",
        "com.preff.kb.xm",
        "com.google.android.inputmethod.latin"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        listenForDeviceConfig()
    }

    private fun listenForDeviceConfig() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty() || deviceId == "PG-0000") {
            deviceId = "PG-8083"
        }

        blockedAppsListener?.remove()
        blockedAppsListener = db.collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                
                try {
                    // 1. Blocked Apps Sync
                    val list = snapshot.get("blockedApps") as? List<*>
                    blockedPackages.clear()
                    list?.forEach { 
                        if (it is String) blockedPackages.add(it)
                    }

                    // 2. Bedtime Settings Sync
                    val bedtimeMap = snapshot.get("bedtime") as? Map<*, *>
                    if (bedtimeMap != null) {
                        isBedtimeEnabled = bedtimeMap["enabled"] as? Boolean ?: false
                        bedtimeStart = (bedtimeMap["startTime"] as? String) ?: "22:00"
                        bedtimeEnd = (bedtimeMap["endTime"] as? String) ?: "06:00"
                    }

                    // 3. Remote Pause Sync
                    isDevicePaused = snapshot.getBoolean("isPaused") ?: false

                    // INSTANT ACTION: Agar dashboard se Pause dabaya ya Bedtime chalu hai, turant Home bhejo!
                    if (isDevicePaused || (isBedtimeEnabled && isCurrentTimeInBedtime(bedtimeStart, bedtimeEnd))) {
                        Handler(Looper.getMainLooper()).post {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // Launchers, Keyboards, System UI ko bypass karein taaki phone freeze na ho
        if (systemSafePackages.any { packageName.contains(it, ignoreCase = true) }) {
            return
        }

        // 1. REMOTE PAUSE ENFORCEMENT (Sabse pehle lock)
        if (isDevicePaused) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Device is Locked by Parent", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. BEDTIME ENFORCEMENT
        if (isBedtimeEnabled && isCurrentTimeInBedtime(bedtimeStart, bedtimeEnd)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Bedtime Active: Phone Locked", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. APP BLOCKER
        if (blockedPackages.contains(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Parent Shield: App Blocked", Toast.LENGTH_SHORT).show()
            logViolationToFirebase("Blocked App Opened", packageName)
            return
        }

        // 4. SETTINGS ANTI-TAMPER SHIELD
        if (settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            val screenText = extractText(event).lowercase()
            val isTargetingOurApp = screenText.contains("pingparent")
            val isTryingToTamper = screenText.contains("accessibility") ||
                    screenText.contains("uninstall") ||
                    screenText.contains("force stop") ||
                    screenText.contains("clear data") ||
                    screenText.contains("clear storage") ||
                    screenText.contains("off")

            if (isTargetingOurApp && isTryingToTamper) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, "PingParent settings are locked", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 5. CONTENT FILTER
        if (!settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            val textContent = extractText(event).lowercase()
            if (textContent.isNotEmpty()) {
                for (keyword in blockedKeywords) {
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(textContent)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        logViolationToFirebase(keyword, packageName)
                        Toast.makeText(this, "Content blocked by Shield", Toast.LENGTH_SHORT).show()
                        break
                    }
                }
            }
        }
    }

    private fun isCurrentTimeInBedtime(startStr: String, endStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val nowCalendar = Calendar.getInstance()
            val nowStr = sdf.format(nowCalendar.time)
            
            val now = sdf.parse(nowStr)
            val start = sdf.parse(startStr)
            val end = sdf.parse(endStr)

            if (now != null && start != null && end != null) {
                if (start.before(end)) {
                    now.after(start) && now.before(end)
                } else {
                    now.after(start) || now.before(end)
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extractText(event: AccessibilityEvent): String {
        val sb = java.lang.StringBuilder()
        event.text?.forEach { sb.append(it).append(" ") }
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val textList = mutableListOf<String>()
            findTextNodes(rootNode, textList)
            textList.forEach { sb.append(it).append(" ") }
        }
        return sb.toString()
    }

    private fun findTextNodes(node: android.view.accessibility.AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        node.text?.let { list.add(it.toString()) }
        node.contentDescription?.let { list.add(it.toString()) }
        for (i in 0 until node.childCount) {
            findTextNodes(node.getChild(i), list)
        }
    }

    private fun logViolationToFirebase(keyword: String, sourceApp: String) {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty() || deviceId == "PG-0000") {
            deviceId = "PG-8083"
        }

        val alertData = hashMapOf(
            "keyword" to keyword,
            "app" to sourceApp,
            "category" to "Shield Block",
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("devices").document(deviceId)
            .collection("alerts")
            .add(alertData)
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedAppsListener?.remove()
    }

    override fun onInterrupt() {}
}
