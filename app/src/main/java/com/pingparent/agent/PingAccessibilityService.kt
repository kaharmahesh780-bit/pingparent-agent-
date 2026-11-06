package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class PingAccessibilityService : AccessibilityService() {

    private val blockedKeywords = listOf(
        "porn", "xxx", "xvideos", "xnxx", "adult", "sex", "erotic", "nude", "casino", "betting"
    )

    private val db = FirebaseFirestore.getInstance()
    private var lastBlockedTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""
        
        val isBrowser = packageName.contains("chrome") || 
                        packageName.contains("browser") || 
                        packageName.contains("firefox")

        if (isBrowser) {
            val rootNode = rootInActiveWindow ?: return
            checkAndBlock(rootNode)
        }
    }

    private fun checkAndBlock(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        val isViolated = blockedKeywords.any { text.contains(it) || contentDesc.contains(it) }

        if (isViolated) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBlockedTime > 3000) {
                lastBlockedTime = currentTime
                triggerBlockAction()
            }
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                checkAndBlock(child)
            }
        }
    }

    private fun triggerBlockAction() {
        performGlobalAction(GLOBAL_ACTION_HOME)

        val sharedPrefs = getSharedPreferences("PingParentPrefs", MODE_PRIVATE)
        val deviceCode = sharedPrefs.getString("device_code", "823171") ?: "823171"

        db.collection("devices").document(deviceCode).update(
            "blockedAttemptsCount", FieldValue.increment(1),
            "lastBlockedAt", System.currentTimeMillis()
        )
    }

    override fun onInterrupt() {}
}
