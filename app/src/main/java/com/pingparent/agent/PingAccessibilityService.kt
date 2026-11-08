package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PingAccessibilityService : AccessibilityService() {

    private var lastBlockedTime: Long = 0L

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

    private val browserUrlNodeIds = listOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "org.mozilla.firefox:id/url_bar_title",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.opera.browser:id/url_field",
        "com.brave.browser:id/url_bar",
        "com.google.android.googlequicksearchbox:id/googleapp_search_box"
    )

    private val blockedKeywords = setOf(
        "porn", "xxx", "sex", "adult", "casino", "betting", 
        "satta", "gambling", "nude", "xvideos", "xnxx", "erotic",
        "redtube", "youporn", "brazzers", "hentai", "stripchat"
    )

    private val incognitoKeywords = setOf(
        "incognito", "private tab", "private browsing", "inprivate", "गुप्त"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 100
            }
            serviceInfo = info
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val packageName = event.packageName?.toString() ?: ""

            // 1. Direct App Shield (Instagram)
            if (packageName == "com.instagram.android") {
                triggerBlockOverlay("Instagram")
                return
            }

            // 2. Browser Tracking
            if (targetBrowsers.any { packageName.contains(it) }) {
                // Check event text safely
                event.text?.forEach { charSeq ->
                    val txt = charSeq?.toString() ?: ""
                    if (isIncognitoText(txt)) {
                        triggerBlockOverlay("Incognito Browsing Blocked")
                        return
                    }
                    if (containsRestrictedPattern(txt)) {
                        triggerBlockOverlay("Restricted Web Search")
                        return
                    }
                }

                // Check URL/Address bars specifically
                val rootNode = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
                for (viewId in browserUrlNodeIds) {
                    val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Throwable) { null }
                    if (!nodes.isNullOrEmpty()) {
                        for (node in nodes) {
                            val urlText = node.text?.toString() ?: ""
                            val descText = node.contentDescription?.toString() ?: ""

                            if (isIncognitoText(urlText) || isIncognitoText(descText)) {
                                triggerBlockOverlay("Incognito Browsing Blocked")
                                return
                            }
                            if (containsRestrictedPattern(urlText) || containsRestrictedPattern(descText)) {
                                triggerBlockOverlay("Restricted URL")
                                return
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun isIncognitoText(text: String): Boolean {
        if (text.isBlank()) return false
        val clean = text.lowercase()
        return incognitoKeywords.any { clean.contains(it) }
    }

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

    private fun triggerBlockOverlay(reason: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockedTime < 2000) return
        lastBlockedTime = currentTime

        try {
            val intent = Intent(this, BlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("BLOCKED_APP_NAME", reason)
            }
            startActivity(intent)
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {}
}
