package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import java.util.regex.Pattern

class PingAccessibilityService : AccessibilityService() {

    // 1. केवल ब्राउज़र्स को टारगेट करना (कंपीटीटर स्टैंडर्ड)
    private val monitoredBrowsers = setOf(
        "com.android.chrome",
        "com.google.android.googlequicksearchbox", // Google Search App
        "org.mozilla.firefox",
        "com.microsoft.emmx",                     // Edge
        "com.sec.android.app.sbrowser",           // Samsung Internet
        "com.brave.browser",
        "com.opera.browser"
    )

    // 2. कंपीटीटर-ग्रेड ब्लैकलिस्ट (एडल्ट साइट्स + कीवर्ड्स बाउंड्री के साथ)
    private val blockedPatterns = listOf(
        Pattern.compile("\\bporn\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bxxx\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bxvideos\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bxnxx\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bpornhub\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bhentai\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bsex\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bnude\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\berotic\\b", Pattern.CASE_INSENSITIVE)
    )

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var lastTriggerTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // कंपीटीटर रूल: अगर ब्राउज़र या सर्च ऐप नहीं है, तो बिल्कुल इग्नोर करो
        if (!monitoredBrowsers.contains(packageName)) {
            return
        }

        // स्पैम प्रिवेंशन: 5 सेकंड का लॉकआउट ताकि पैरेंट को 1 ही क्लीन अलर्ट जाए
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 5000) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        scanBrowserNodes(rootNode)
    }

    private fun scanBrowserNodes(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val combined = "$text $contentDesc"

        if (combined.isNotBlank()) {
            for (pattern in blockedPatterns) {
                val matcher = pattern.matcher(combined)
                if (matcher.find()) {
                    val matchedKeyword = matcher.group()

                    // टाइमर लॉक ताकि मल्टीपल ट्रिगर्स न हों
                    lastTriggerTime = System.currentTimeMillis()

                    // 1. फायरबेस में अलर्ट सेव करो
                    logBlockAlert(matchedKeyword)

                    // 2. तुरंत होम स्क्रीन पर रीडायरेक्ट करो
                    redirectToHome()
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            scanBrowserNodes(node.getChild(i))
        }
    }

    private fun redirectToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
    }

    private fun logBlockAlert(keyword: String) {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "unknown_device") ?: "unknown_device"

        val alertData = hashMapOf(
            "keyword" to keyword,
            "timestamp" to FieldValue.serverTimestamp(),
            "status" to "Blocked"
        )

        db.collection("devices")
            .document(deviceId)
            .collection("alerts")
            .add(alertData)
    }

    override fun onInterrupt() {}
}
