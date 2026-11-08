package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class PingAccessibilityService : AccessibilityService() {

    private var lastBlockedTime: Long = 0L

    private val targetBrowsers = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.brave.browser",
        "com.sec.android.app.sbrowser"
    )

    private val blockedKeywords = listOf(
        "porn", "xxx", "sex", "adult", "casino", "betting", 
        "satta", "gambling", "nude", "xvideos", "xnxx", "erotic"
    )

    private val incognitoMarkers = listOf("incognito", "private tab", "inprivate", "गुप्त")

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                notificationTimeout = 100
            }
            serviceInfo = info
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val pkg = event.packageName?.toString() ?: ""

            // 1. INSTAGRAM SHIELD
            if (pkg == "com.instagram.android") {
                executeImmediateBlock("Instagram is blocked by PingParent")
                return
            }

            // 2. BROWSER & SEARCH FILTER
            if (targetBrowsers.any { pkg.contains(it) }) {
                // A. Check event text safely
                val combinedText = event.text?.joinToString(" ")?.lowercase() ?: ""
                
                // Incognito check
                if (incognitoMarkers.any { combinedText.contains(it) }) {
                    executeImmediateBlock("Incognito browsing is not allowed")
                    return
                }

                // Keyword check
                if (isRestricted(combinedText)) {
                    executeImmediateBlock("Adult content is restricted")
                    return
                }

                // B. Fast Node Search (No recursive DOM traversal)
                val rootNode = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
                for (kw in blockedKeywords) {
                    val matching = try { rootNode.findAccessibilityNodeInfosByText(kw) } catch (_: Throwable) { null }
                    if (!matching.isNullOrEmpty()) {
                        executeImmediateBlock("Restricted site blocked")
                        return
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun isRestricted(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val clean = rawText.lowercase().replace(Regex("[^a-z0-9]"), "")
        return blockedKeywords.any { clean.contains(it) || rawText.lowercase().contains(it) }
    }

    private fun executeImmediateBlock(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 2500) return
        lastBlockedTime = now

        try {
            // First: Send user directly to home screen (Instant eject, zero flicker)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Second: Show prompt on main thread
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "PingParent: $reason", Toast.LENGTH_LONG).show()
            }

            // Third: Launch Block Screen
            val intent = Intent(this, BlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("BLOCKED_APP_NAME", reason)
            }
            startActivity(intent)
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {}
}
