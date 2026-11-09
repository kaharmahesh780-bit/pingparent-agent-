package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PingAccessibilityService : AccessibilityService() {

    private val blockedKeywords = listOf(
        "fuck", "porn", "xxx", "sex", "adult", "xvideos", "xnxx", "nude", "pussy"
    )

    private val blockedPackages = listOf(
        "com.instagram.android"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val packageName = event?.packageName?.toString() ?: ""

        // 1. Direct App Block (Instagram)
        if (blockedPackages.any { packageName.contains(it, ignoreCase = true) }) {
            kickOutToHome()
            return
        }

        // 2. Strict Browser Shield (Chrome)
        if (packageName.contains("chrome", ignoreCase = true)) {
            if (scanNodeRecursively(rootNode)) {
                kickOutToHome()
                return
            }
        }
    }

    private fun scanNodeRecursively(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (badWord in blockedKeywords) {
            if (text.contains(badWord) || desc.contains(badWord)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (scanNodeRecursively(child)) {
                return true
            }
        }
        return false
    }

    private fun kickOutToHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {}
}
