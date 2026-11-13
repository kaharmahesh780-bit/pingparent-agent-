package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar

class PingAccessibilityService : AccessibilityService() {

    private val TAG = "PingShield"

    private var db: FirebaseFirestore? = null
    private var docListener: ListenerRegistration? = null
    private var attachedDeviceId: String = ""
    private val blockedPackages = HashSet<String>()

    @Volatile private var isDevicePaused = false
    @Volatile private var allowEmergency = true // dashboard se control hota hai
    @Volatile private var tamperLock = false     // Settings Lock (dashboard se)
    private var lastTamperLogAt = 0L
    @Volatile private var isBedtimeEnabled = false
    @Volatile private var bedtimeStartMinutes = 22 * 60 // 22:00
    @Volatile private var bedtimeEndMinutes = 6 * 60    // 06:00

    private var lastHomeAt = 0L
    private var lastToastAt = 0L
    private var launcherPackages: Set<String> = emptySet()
    private var emergencyPackages: Set<String> = emptySet()

    // Bedtime mein bhi ye apps chalenge (call, SMS, contacts)
    private val emergencyStaticPackages = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.app.contacts",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.emergency"
        // WhatsApp call bhi chahiye to yahan "com.whatsapp" jod do
    )

    // Tamper Lock ON hone par ye apps bhi band (Settings, uninstall screen)
    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    // ---------- Adult content block ----------
    private val browserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.brave.browser", "com.microsoft.emmx", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native",
        "com.mi.globalbrowser", "com.android.browser", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.UCMobile.intl"
    )

    // Ye shabd kisi word ki shuruaat mein ho to block (porn, pornhub, xxx...)
    private val adultStems = listOf(
        "porn", "xxx", "nsfw", "hentai", "xvideo", "xnxx", "xhamster", "redtube",
        "youporn", "onlyfans", "erotic", "nudes", "nude", "chudai", "camgirl"
    )

    // Poora word milna chahiye (sex lekin Essex nahi)
    private val adultWords = setOf(
        "sex", "sexy", "naked", "nangi", "boobs", "pussy", "milf", "bdsm", "fetish"
    )

    // Kai shabdon ke phrase
    private val adultPhrases = listOf(
        "bf video", "bf film", "blue film", "sex video", "sexy video", "mms video", "desi sex"
    )

    // Hindi / Marathi (Devanagari)
    private val adultDevanagari = listOf("सेक्स", "पोर्न", "चुदाई", "नंगी", "ब्लू फिल्म")

    private var lastBrowserScanAt = 0L
    private var lastAdultLogAt = 0L

    private fun findAdultTerm(raw: String): String? {
        if (raw.isBlank()) return null
        for (d in adultDevanagari) {
            if (raw.contains(d)) return d
        }
        val tokens = raw.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (t in tokens) {
            if (adultWords.contains(t)) return t
            for (s in adultStems) {
                if (t.startsWith(s)) return s
            }
        }
        val joined = " " + tokens.joinToString(" ") + " "
        for (p in adultPhrases) {
            if (joined.contains(" $p ")) return p
        }
        return null
    }

    // Browser ke URL bar ka text padhta hai (800ms mein ek baar)
    private fun scanBrowserUrl(pkg: String): String? {
        val now = System.currentTimeMillis()
        if (now - lastBrowserScanAt < 800) return null
        lastBrowserScanAt = now
        val root = rootInActiveWindow ?: return null
        val ids = listOf(
            "$pkg:id/url_bar", "$pkg:id/location_bar_edit_text",
            "$pkg:id/mozac_browser_toolbar_url_view", "$pkg:id/url_field", "$pkg:id/url"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (n in nodes) {
                val t = n.text?.toString() ?: continue
                val hit = findAdultTerm(t)
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun checkAdultContent(event: AccessibilityEvent, pkg: String): String? {
        if (pkg == this.packageName) return null
        val type = event.eventType
        // 1. Kisi bhi app ke search box mein type kiya hua text
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && !event.isPassword) {
            val typed = event.text?.joinToString(" ") ?: ""
            val hit = findAdultTerm(typed)
            if (hit != null) return hit
        }
        // 2. Browser ka URL
        if (browserPackages.contains(pkg) &&
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ) {
            return scanBrowserUrl(pkg)
        }
        return null
    }

    private fun logAdultAttempt(pkg: String, term: String) {
        val now = System.currentTimeMillis()
        if (now - lastAdultLogAt < 30000) return // 30 second mein ek hi alert
        lastAdultLogAt = now
        try {
            val deviceId = readDeviceId()
            if (deviceId.isEmpty()) return
            val alertData = hashMapOf(
                "keyword" to "Adult Content: $term",
                "app" to pkg,
                "category" to "Adult Content",
                "timestamp" to FieldValue.serverTimestamp()
            )
            (db ?: FirebaseFirestore.getInstance()).collection("devices").document(deviceId)
                .collection("alerts")
                .add(alertData)
        } catch (e: Exception) {
        }
    }

    private val safeSystemPackages = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.google.android.apps.nexuslauncher",
        "com.motorola.launcher3",
        "com.sec.android.app.launcher",
        "com.android.launcher3",
        "com.google.android.inputmethod.latin"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            db = FirebaseFirestore.getInstance()
            loadLauncherPackages()
            loadEmergencyPackages()
            attachFirestoreSync()
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected error", e)
        }
    }

    // Phone ka default dialer aur SMS app dhoondh kar emergency list mein daalta hai
    private fun loadEmergencyPackages() {
        val set = HashSet<String>(emergencyStaticPackages)
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            tm?.defaultDialerPackage?.let { set.add(it) }
        } catch (e: Exception) {
            Log.e(TAG, "dialer detect error", e)
        }
        try {
            Telephony.Sms.getDefaultSmsPackage(this)?.let { set.add(it) }
        } catch (e: Exception) {
            Log.e(TAG, "sms detect error", e)
        }
        emergencyPackages = set
        Log.d(TAG, "Emergency apps: $set")
    }

    // Device ka pairing code (MainActivity jo save karta hai)
    private fun readDeviceId(): String {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val id = prefs.getString("pairing_code", null)
            ?: prefs.getString("paired_code", "") ?: ""
        return if (id == "PG-0000") "" else id
    }

    // Default launcher(s) ko safe list mein daalta hai taaki HOME ka loop na bane
    private fun loadLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            launcherPackages = packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "launcher detect error", e)
        }
    }

    private var lastAttachTry = 0L

    // Silent login ke baad hi Firestore se jodo (15 second mein ek hi baar try)
    private fun attachFirestoreSync() {
        val now = System.currentTimeMillis()
        if (now - lastAttachTry < 15000) return
        lastAttachTry = now
        ChildAuth.ensure { attachFirestoreSyncNow() }
    }

    private fun attachFirestoreSyncNow() {
        try {
            val deviceId = readDeviceId()
            if (deviceId.isEmpty()) {
                Log.w(TAG, "Pairing code abhi nahi mila, baad mein dobara try hoga")
                return
            }
            if (deviceId == attachedDeviceId && docListener != null) return

            attachedDeviceId = deviceId
            val firestore = db ?: FirebaseFirestore.getInstance()
            docListener?.remove()
            Log.d(TAG, "Listening to devices/$deviceId")

            docListener = firestore.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Snapshot error", error)
                        docListener?.remove()
                        docListener = null
                        attachedDeviceId = "" // agle event par dobara jodega
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                    try {
                        // 1. Blocked apps
                        val list = snapshot.get("blockedApps") as? List<*>
                        synchronized(blockedPackages) {
                            blockedPackages.clear()
                            list?.forEach { item ->
                                if (item is String) blockedPackages.add(item.trim())
                            }
                        }

                        // 2. Bedtime (Number kuch bhi ho: Long/Double/Int)
                        val bedtimeMap = snapshot.get("bedtime") as? Map<*, *>
                        if (bedtimeMap != null) {
                            isBedtimeEnabled = bedtimeMap["enabled"] as? Boolean ?: false

                            val sh = (bedtimeMap["startHour"] as? Number)?.toInt()
                            val sm = (bedtimeMap["startMinute"] as? Number)?.toInt()
                            val eh = (bedtimeMap["endHour"] as? Number)?.toInt()
                            val em = (bedtimeMap["endMinute"] as? Number)?.toInt()

                            if (sh != null && sm != null && eh != null && em != null) {
                                bedtimeStartMinutes = sh * 60 + sm
                                bedtimeEndMinutes = eh * 60 + em
                            } else {
                                bedtimeStartMinutes =
                                    parseTimeToMinutes((bedtimeMap["startTime"] as? String) ?: "22:00")
                                bedtimeEndMinutes =
                                    parseTimeToMinutes((bedtimeMap["endTime"] as? String) ?: "06:00")
                            }
                        } else {
                            isBedtimeEnabled = false
                        }

                        // 3. Pause / force block
                        val paused = snapshot.getBoolean("isPaused") ?: false
                        val forceBlocked = snapshot.getBoolean("isForceBlocked") ?: false
                        isDevicePaused = paused || forceBlocked
                        allowEmergency = snapshot.getBoolean("allowEmergency") ?: true
                        tamperLock = snapshot.getBoolean("tamperLock") ?: false

                        Log.d(
                            TAG,
                            "Sync: blocked=${blockedPackages.size} bedtime=$isBedtimeEnabled " +
                                "$bedtimeStartMinutes-$bedtimeEndMinutes paused=$isDevicePaused " +
                                "inBedtime=${isNowInBedtime()}"
                        )

                        if (isDevicePaused || (isBedtimeEnabled && isNowInBedtime())) {
                            triggerHomeScreen()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Sync parse error", e)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "attach error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val packageName = event.packageName?.toString()?.trim() ?: return

            // Agar pairing code baad mein bana ya badla, listener dobara jodo
            if (readDeviceId() != attachedDeviceId) attachFirestoreSync()

            if (safeSystemPackages.contains(packageName)) return
            if (launcherPackages.contains(packageName)) return

            // 1. Remote lock
            if (isDevicePaused) {
                if (allowEmergency && emergencyPackages.contains(packageName)) return
                triggerHomeScreen()
                showToast("Device is Locked by Parent")
                return
            }

            // 2. Bedtime lock
            if (isBedtimeEnabled && isNowInBedtime()) {
                // Call / SMS / contacts bedtime mein bhi allowed
                if (allowEmergency && emergencyPackages.contains(packageName)) return

                triggerHomeScreen()
                showToast("Bedtime Active: Phone Locked")
                return
            }

            // 2.5 Settings Lock (Accessibility band karne / uninstall se rokta hai)
            if (tamperLock && settingsPackages.contains(packageName)) {
                triggerHomeScreen()
                showToast("Settings Parent ne lock ki hai")
                logTamperAttempt(packageName)
                return
            }

            // 2.7 Adult content block (search ya URL mein adult shabd)
            val adultTerm = checkAdultContent(event, packageName)
            if (adultTerm != null) {
                triggerHomeScreen()
                showToast("Ye search allowed nahi hai")
                logAdultAttempt(packageName, adultTerm)
                return
            }

            // 3. App blocking
            val shouldBlock = synchronized(blockedPackages) {
                blockedPackages.contains(packageName)
            }

            if (shouldBlock) {
                triggerHomeScreen()
                showToast("App is Blocked by Parent")
                logViolation(packageName)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "event error", e)
        }
    }

    private fun triggerHomeScreen() {
        val now = System.currentTimeMillis()
        if (now - lastHomeAt < 700) return // spam / loop rokne ke liye
        lastHomeAt = now
        Handler(Looper.getMainLooper()).post {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e: Exception) {
                Log.e(TAG, "home action error", e)
            }
        }
    }

    private fun showToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 2500) return
        lastToastAt = now
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
            }
        }
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        return try {
            val parts = timeStr.trim().split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun isNowInBedtime(): Boolean {
        return try {
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val start = bedtimeStartMinutes
            val end = bedtimeEndMinutes

            when {
                start == end -> false
                start < end -> now in start until end
                else -> now >= start || now < end // raat 22:00 se subah 06:00
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun logTamperAttempt(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastTamperLogAt < 60000) return // Firebase kharcha bachane ke liye 1 min mein ek baar
        lastTamperLogAt = now
        try {
            val deviceId = readDeviceId()
            if (deviceId.isEmpty()) return
            val alertData = hashMapOf(
                "keyword" to "Settings Open Attempt",
                "app" to pkg,
                "category" to "Tamper",
                "timestamp" to FieldValue.serverTimestamp()
            )
            (db ?: FirebaseFirestore.getInstance()).collection("devices").document(deviceId)
                .collection("alerts")
                .add(alertData)
        } catch (e: Exception) {
        }
    }

    // Ek hi app ka alert 30 second mein sirf ek baar (har screen event par nahi)
    private val lastViolationAt = HashMap<String, Long>()

    private fun logViolation(blockedApp: String) {
        val now = System.currentTimeMillis()
        if (now - (lastViolationAt[blockedApp] ?: 0L) < 30000) return
        lastViolationAt[blockedApp] = now
        try {
            val deviceId = readDeviceId()
            if (deviceId.isEmpty()) return

            val alertData = hashMapOf(
                "keyword" to "Blocked App Opened",
                "app" to blockedApp,
                "category" to "Shield Block",
                "timestamp" to FieldValue.serverTimestamp()
            )

            (db ?: FirebaseFirestore.getInstance()).collection("devices").document(deviceId)
                .collection("alerts")
                .add(alertData)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        docListener?.remove()
        docListener = null
        attachedDeviceId = ""
    }

    override fun onInterrupt() {}
}
