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

    // 1. Browsers + Social Media + Video/Streaming Platforms
    private val monitoredApps = setOf(
        // Browsers
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.opera.browser",

        // Social Media & Video Apps
        "com.google.android.youtube",
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.snapchat.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically" // TikTok / Short Video apps
    )

    // 2. Multi-Category Competitor Rule Engine (Adult, Alcohol, Drugs, Gambling, Self-Harm)
    private val safetyRules = listOf(
        // Category: Adult / Explicit
        Pair(Pattern.compile("\\b(porn|xxx|xvideos|xnxx|pornhub|hentai|sex|nude|erotic|brazzers)\\b", Pattern.CASE_INSENSITIVE), "Adult Content"),
        
        // Category: Alcohol & Tobacco
        Pair(Pattern.compile("\\b(beer|whiskey|vodka|rum|alcohol|daroo|wine|cigarette|smoke|vape|hookah)\\b", Pattern.CASE_INSENSITIVE), "Alcohol & Tobacco"),

        // Category: Drugs & Narcotics
        Pair(Pattern.compile("\\b(drugs|cocaine|weed|marijuana|ganja|charas|heroin|lsd|meth)\\b", Pattern.CASE_INSENSITIVE), "Drugs & Narcotics"),

        // Category: Gambling & Betting
        Pair(Pattern.compile("\\b(bet365|1xbet|satta|matka|casino|poker|rummycircle|gambling|betting)\\b", Pattern.CASE_INSENSITIVE), "Gambling & Betting"),

        // Category: Self-Harm & Violence
        Pair(Pattern.compile("\\b(suicide|kill myself|cut wrists|hang myself|how to die)\\b", Pattern.CASE_INSENSITIVE), "Self-Harm / Violence")
    )

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var lastTriggerTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // सिर्फ मॉनिटर्ड ऐप्स (Browser, YouTube, Instagram, FB आदि)
        if (!monitoredApps.contains(packageName)) {
            return
        }

        // 4-सेकंड कूलडाउन ताकि स्पैम अलर्ट न बने
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 4000) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        scanAndEnforce(rootNode, packageName)
    }

    private fun scanAndEnforce(node: AccessibilityNodeInfo?, packageName: String) {
        if (node == null) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val fullContent = "$text $contentDesc"

        if (fullContent.isNotBlank()) {
            for ((pattern, category) in safetyRules) {
                val matcher = pattern.matcher(fullContent)
                if (matcher.find()) {
                    val matchedKeyword = matcher.group()
                    lastTriggerTime = System.currentTimeMillis()

                    val appLabel = getAppNameFromPackage(packageName)
                    logBlockAlert(matchedKeyword, category, appLabel)
                    redirectToHome()
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            scanAndEnforce(node.getChild(i), packageName)
        }
    }

    private fun getAppNameFromPackage(pkg: String): String {
        return when {
            pkg.contains("youtube") -> "YouTube"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("facebook") -> "Facebook"
            pkg.contains("chrome") -> "Google Chrome"
            pkg.contains("firefox") -> "Firefox"
            pkg.contains("snapchat") -> "Snapchat"
            else -> "Web Browser"
        }
    }

    private fun redirectToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
    }

    private fun logBlockAlert(keyword: String, category: String, appName: String) {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "unknown_device") ?: "unknown_device"

        val alertData = hashMapOf(
            "keyword" to keyword,
            "category" to category,
            "app" to appName,
            "timestamp" to FieldValue.serverTimestamp(),
            "status" to "Auto-Blocked & Exit"
        )

        db.collection("devices")
            .document(deviceId)
            .collection("alerts")
            .add(alertData)
    }

    override fun onInterrupt() {}
}
