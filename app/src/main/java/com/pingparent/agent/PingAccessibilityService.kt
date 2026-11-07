package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PingAccessibilityService : AccessibilityService() {

    // केवल मुख्य ब्राउज़र ऐप्स को टारगेट करेंगे (ताकि Gemini, GitHub या Settings बैक न हों)
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

    // ब्लॉक किए जाने वाले कीवर्ड्स
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
            notificationTimeout = 40 // स्क्रीन बदलते ही 40ms में तुरंत चेक करेगा
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // जब यूज़र केवल ब्राउज़र के अंदर होगा तभी चेकिंग चालू होगी
        if (targetBrowsers.any { packageName.contains(it) }) {
            val rootNode = rootInActiveWindow ?: return

            if (containsBlockedContent(rootNode)) {
                // 1. ब्राउज़र के पेज/वीडियो को बंद करने के लिए बैक दबाएगा
                performGlobalAction(GLOBAL_ACTION_BACK)

                // 2. तुरंत Android होम स्क्रीन पर ले जाएगा
                performGlobalAction(GLOBAL_ACTION_HOME)

                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(homeIntent)
            }
        }
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
