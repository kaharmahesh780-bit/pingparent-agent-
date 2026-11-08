package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PingAccessibilityService : AccessibilityService() {

    private var lastBlockedTime: Long = 0L

    // प्रमुख ब्राउज़र्स
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

    // ब्राउज़र के एड्रेस/सर्च बार की इंटरनल IDs
    private val browserUrlNodeIds = setOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "org.mozilla.firefox:id/url_bar_title",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.opera.browser:id/url_field",
        "com.brave.browser:id/url_bar",
        "com.google.android.googlequicksearchbox:id/googleapp_search_box"
    )

    // ब्लॉक किए जाने वाले कीवर्ड्स और डोमेन
    private val blockedKeywords = setOf(
        "porn", "xxx", "sex", "adult", "casino", "betting", 
        "satta", "gambling", "nude", "xvideos", "xnxx", "erotic",
        "redtube", "youporn", "brazzers", "hentai", "stripchat"
    )

    // Incognito / Private टैब के सिग्नल्स
    private val incognitoKeywords = setOf(
        "incognito", "private tab", "private browsing", "inprivate", "गुप्त"
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
            notificationTimeout = 10 // सुपर फ़ास्ट रिस्पॉन्स
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // 1. Instagram डायरेक्ट ब्लॉक
        if (packageName == "com.instagram.android") {
            triggerBlockOverlay("Instagram")
            return
        }

        // 2. ब्राउज़र और सर्च इंस्पेक्शन
        if (targetBrowsers.any { packageName.contains(it) }) {
            val rootNode = rootInActiveWindow ?: return

            // A. Incognito Mode ब्लॉक (Qustodio/Bark का नियम)
            if (isIncognitoModeActive(rootNode, event)) {
                triggerBlockOverlay("Incognito Browsing Blocked")
                return
            }

            // B. सर्च बार में टाइप किया जा रहा टेक्स्ट तुरंत चेक करना
            for (charSeq in event.text) {
                if (containsRestrictedPattern(charSeq.toString())) {
                    triggerBlockOverlay("Restricted Web Search")
                    return
                }
            }

            // C. Chrome URL/एड्रेस बार चेक करना
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

            // D. स्क्रीन पर दिख रहे कंटेंट की चेकिंग
            if (deepInspectHierarchy(rootNode)) {
                triggerBlockOverlay("Restricted Content")
            }
        }
    }

    private fun isIncognitoModeActive(rootNode: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        val eventTexts = event.text.joinToString(" ").lowercase()
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""

        for (incognito in incognitoKeywords) {
            if (eventTexts.contains(incognito) || contentDesc.contains(incognito)) {
                return true
            }
        }

        return searchIncognitoNodes(rootNode)
    }

    private fun searchIncognitoNodes(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (incognito in incognitoKeywords) {
            if (text.contains(incognito) || desc.contains(incognito)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            if (searchIncognitoNodes(node.getChild(i))) {
                return true
            }
        }
        return false
    }

    // स्पेस या डॉट्स (जैसे p o r n या p.o.r.n) को साफ़ करके पकड़ने का फ़िल्टर
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
