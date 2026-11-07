package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PingAccessibilityService : AccessibilityService() {

    private var lastBlockedTime: Long = 0L

    // Market leader browser targets & system search engines
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

    // Known browser address/URL bar IDs used by top parental apps
    private val browserUrlNodeIds = setOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "org.mozilla.firefox:id/url_bar_title",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.opera.browser:id/url_field",
        "com.brave.browser:id/url_bar",
        "com.google.android.googlequicksearchbox:id/googleapp_search_box"
    )

    // Comprehensive content filter list (keywords & adult domains)
    private val blockedKeywords = setOf(
        "porn", "xxx", "sex", "adult", "casino", "betting", 
        "satta", "gambling", "nude", "xvideos", "xnxx", "erotic",
        "redtube", "youporn", "brazzers", "hentai", "stripchat"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 10 // Ultra-fast response
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // 1. Direct App Shield (Instagram)
        if (packageName == "com.instagram.android") {
            triggerBlockOverlay("Instagram")
            return
        }

        // 2. Browser Content & URL Inspection
        if (targetBrowsers.any { packageName.contains(it) }) {
            // Priority Check 1: Typed Search Event Text
            for (charSeq in event.text) {
                if (containsRestrictedPattern(charSeq.toString())) {
                    triggerBlockOverlay("Restricted Web Search")
                    return
                }
            }

            // Priority Check 2: Address Bar View IDs
            val rootNode = rootInActiveWindow ?: return
            for (viewId in browserUrlNodeIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                for (node in nodes) {
                    val urlText = node.text?.toString() ?: ""
                    if (containsRestrictedPattern(urlText)) {
                        triggerBlockOverlay("Restricted URL")
                        return
                    }
                }
            }

            // Priority Check 3: Full DOM/Screen Node Hierarchy Scan
            if (deepInspectHierarchy(rootNode)) {
                triggerBlockOverlay("Restricted Content")
            }
        }
    }

    // Normalizes spaces and special characters to prevent evasion (e.g., 'p o r n' or 'p-o-r-n')
    private fun containsRestrictedPattern(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val clean = rawText.lowercase()
        val unified = clean.replace(Regex("[^a-zA-Z0-9]"), "")

        for (keyword in blockedKeywords) {
            if (clean.contains(keyword) || unified.contains(keyword)) {
                return true
            }
        }
        return false
    }

    private fun deepInspectHierarchy(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""

        if (containsRestrictedPattern(nodeText) || containsRestrictedPattern(nodeDesc)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (deepInspectHierarchy(child)) {
                return true
            }
        }
        return false
    }

    private fun triggerBlockOverlay(reason: String) {
        val currentTime = System.currentTimeMillis()
        // Prevents duplicate intent flooding (1.5-second debounce)
        if (currentTime - lastBlockedTime < 1500) return
        lastBlockedTime = currentTime

        val intent = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("BLOCKED_APP_NAME", reason)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
}
