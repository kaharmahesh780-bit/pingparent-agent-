package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class PingAccessibilityService : AccessibilityService() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var blockedAppsListener: ListenerRegistration? = null
    private val blockedPackages = mutableSetOf<String>()

    // 1. Pratibandhit keywords
    private val blockedKeywords = listOf(
        "porn", "xxx", "adult", "sex", "gambling", "casino", "betting", "rummy", "hack"
    )

    // 2. Settings protection packages
    private val settingsPackages = listOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.google.android.packageinstaller"
    )

    // 3. System safe packages (Notification shade, keyboard, launchers ko bypass)
    private val systemSafePackages = listOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.mi.android.globalminusscreen",
        "com.preff.kb.xm",
        "com.google.android.inputmethod.latin"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        listenForBlockedApps()
    }

    private fun listenForBlockedApps() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty()) return

        blockedAppsListener?.remove()
        blockedAppsListener = db.collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                
                val list = snapshot.get("blockedApps") as? List<*>
                blockedPackages.clear()
                list?.forEach { 
                    if (it is String) blockedPackages.add(it)
                }
            }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // Agar notification panel, keyboard ya launcher hai toh seedha return
        if (systemSafePackages.any { packageName.contains(it, ignoreCase = true) }) {
            return
        }

        // A. APP BLOCKER: Parent dwara block ki gayi app
        if (blockedPackages.contains(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "This app is locked by Parent Shield", Toast.LENGTH_SHORT).show()
            logViolationToFirebase("Blocked App Opened", packageName)
            return
        }

        // B. SETTINGS SHIELD: PingParent ko rokne ya uninstall karne se bachana
        if (settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            val screenText = extractText(event).lowercase()
            val isTargetingOurApp = screenText.contains("pingparent")
            val isDestructiveAction = screenText.contains("uninstall") ||
                    screenText.contains("force stop") ||
                    screenText.contains("clear data") ||
                    screenText.contains("clear storage")

            if (isTargetingOurApp && isDestructiveAction) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, "PingParent cannot be stopped or uninstalled", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // C. CONTENT FILTER: Sirf external apps/browser me exact word match
        if (!settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            val textContent = extractText(event).lowercase()
            if (textContent.isNotEmpty()) {
                for (keyword in blockedKeywords) {
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(textContent)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        logViolationToFirebase(keyword, packageName)
                        Toast.makeText(this, "Content blocked by PingParent Shield", Toast.LENGTH_SHORT).show()
                        break
                    }
                }
            }
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
        val deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty()) return

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
