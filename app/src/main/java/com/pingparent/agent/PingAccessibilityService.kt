package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PingAccessibilityService : AccessibilityService() {

    // Target browsers
    private val targetBrowsers = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.google.android.googlequicksearchbox"
    )

    // Blocked keywords
    private val blockedKeywords = listOf(
        "porn", "xxx", "sex", "adult", "casino", "betting", 
        "satta", "gambling", "nude", "xvideos", "xnxx", "erotic"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED or
                         AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 40
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // 1. INSTAGRAM BLOCK: Jaise hi Instagram open hoga, seedhe BlockActivity aayegi
        if (packageName == "com.instagram.android") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                launchBlockScreen("Instagram")
            }
            return
        }

        // 2. BROWSERS BLOCK: Chrome ya anya browsers me 18+ content check
        if (targetBrowsers.any { packageName.contains(it) }) {
            val rootNode = rootInActiveWindow ?: return

            if (containsBlockedContent(rootNode)) {
                launchBlockScreen("Restricted Site")
            }
        }
    }

    // Professional clean block overlay launch karne ka method
    private fun launchBlockScreen(appName: String) {
        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("BLOCKED_APP_NAME", appName)
        }
        startActivity(intent)
    }

    private fun containsBlockedContent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        for (word in blockedKeywords) {
            if (text.contains(word) || desc.contains(word) || viewId.contains(word)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (containsBlockedContent(child)) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {}
}
