package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class PingAccessibilityService : AccessibilityService() {

    private val db by lazy { FirebaseFirestore.getInstance() }

    // 1. प्रतिबंधित कीवर्ड्स (वेब व सर्च फ़िल्टर)
    private val blockedKeywords = listOf(
        "porn", "xxx", "adult", "sex", "gambling", "casino", "betting", "rummy", "hack"
    )

    // 2. सेटिंग्स प्रोटेक्शन - जिन पैकेजेस में सेटिंग्स या ऐप मैनेजमेंट होता है
    private val settingsPackages = listOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.google.android.packageinstaller"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // A. SETTINGS SHIELD: अगर बच्चा सेटिंग्स या सिक्योरिटी सेंटर खोलने की कोशिश करे
        if (settingsPackages.any { packageName.contains(it, ignoreCase = true) }) {
            // अगर स्क्रीन पर Accessibility, Apps, या Agent का नाम आ रहा हो
            val screenText = extractText(event).lowercase()
            val dangerousSections = listOf(
                "pingparent", "accessibility", "manage apps", "app info", 
                "special permissions", "uninstall", "force stop", "clear data"
            )

            if (dangerousSections.any { screenText.contains(it) }) {
                // तुरंत होम स्क्रीन पर बाहर भेजें
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(this, "Settings are protected by Parent Shield", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // B. CONTENT FILTER: हानिकारक कीवर्ड्स डिटेक्ट करना
        val textContent = extractText(event).lowercase()
        if (textContent.isNotEmpty()) {
            for (keyword in blockedKeywords) {
                if (textContent.contains(keyword)) {
                    // ब्राउज़र या ऐप तुरंत बंद करके होम स्क्रीन लाएं
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    logViolationToFirebase(keyword, packageName)
                    Toast.makeText(this, "Content blocked by PingParent Shield", Toast.LENGTH_SHORT).show()
                    break
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
