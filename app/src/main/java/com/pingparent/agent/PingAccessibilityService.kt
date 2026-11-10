package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class PingAccessibilityService : AccessibilityService() {

    private val db by lazy { FirebaseFirestore.getInstance() }

    // 1. Harmful keywords filter (Browsers aur search ke liye)
    private val blockedKeywords = listOf(
        "porn", "xxx", "adult", "sex", "gambling", "casino", "betting", "rummy", "hack"
    )

    // 2. Sirf system settings aur installer packages
    private val settingsPackages = listOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.google.android.packageinstaller"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // A. SETTINGS PROTECTION (Strict & Targeted)
        // Sirf tab trigger kare jab package Android Settings ya MIUI Security Center ho
        if (settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            val screenText = extractText(event).lowercase()

            // Check: Kya screen par PingParent app open hai?
            val isTargetingOurApp = screenText.contains("pingparent")

            // Check: Kya destructive action button samne hai?
            val isDestructiveAction = screenText.contains("uninstall") ||
                    screenText.contains("force stop") ||
                    screenText.contains("clear data") ||
                    screenText.contains("clear storage")

            // Sirf jab dono match honge tabhi block hoga (Poori settings open rahegi)
            if (isTargetingOurApp && isDestructiveAction) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, "PingParent cannot be stopped or uninstalled", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // B. CONTENT FILTER (Browsers & other apps only)
        // Settings app ke andar keyword filter scan nahi karega taaki self-lock na ho
        if (!settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            val textContent = extractText(event).lowercase()
            if (textContent.isNotEmpty()) {
                for (keyword in blockedKeywords) {
                    if (textContent.contains(keyword)) {
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
            "category" to "Protected Keyword",
            "timestamp" to FieldValue.serverTimestamp()
        )

        db.collection("devices").document(deviceId)
            .collection("alerts")
            .add(alertData)
    }

    override fun onInterrupt() {}
}
