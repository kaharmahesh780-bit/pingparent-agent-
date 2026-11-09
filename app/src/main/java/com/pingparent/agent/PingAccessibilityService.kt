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

    // Browsers + Social Media platforms
    private val monitoredApps = setOf(
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.opera.browser",
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.twitter.android",
        "com.google.android.youtube"
    )

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

        if (!monitoredApps.contains(packageName)) {
            return
        }

        // 4-second cooldown to prevent duplicate logs
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 4000) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        scanAndBlock(rootNode)
    }

    private fun scanAndBlock(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val fullContent = "$text $contentDesc"

        if (fullContent.isNotBlank()) {
            for (pattern in blockedPatterns) {
                val matcher = pattern.matcher(fullContent)
                if (matcher.find()) {
                    val matchedKeyword = matcher.group()
                    lastTriggerTime = System.currentTimeMillis()

                    logBlockAlert(matchedKeyword)
                    redirectToHome()
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            scanAndBlock(node.getChild(i))
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
