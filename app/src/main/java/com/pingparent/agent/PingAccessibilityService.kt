package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class PingAccessibilityService : AccessibilityService() {

    private val blockedKeywords = listOf(
        "porn", "xxx", "sex", "adult", "xvideos", "xnxx", "nude", "erotic"
    )

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val deviceId = "child_device_01" // Aapka configured Device ID

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val rootNode = rootInActiveWindow ?: return
        checkAndBlockContent(rootNode)
    }

    private fun checkAndBlockContent(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (keyword in blockedKeywords) {
            if (text.contains(keyword) || contentDesc.contains(keyword)) {
                // 1. Log alert directly to Firestore for Parent Dashboard
                logBlockAlert(keyword)

                // 2. Redirect to Home Screen
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                return
            }
        }

        for (i in 0 until node.childCount) {
            checkAndBlockContent(node.getChild(i))
        }
    }

    private fun logBlockAlert(matchedWord: String) {
        val alertData = hashMapOf(
            "keyword" to matchedWord,
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
