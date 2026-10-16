package com.minimalist.launcher

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ============================================================================
// THEME & MONOCHROMATIC PALETTE
// ============================================================================
private val BgBlack = Color(0xFF0A0A0A)
private val SurfaceDark = Color(0xFF141414)
private val BorderSubtle = Color(0xFF262626)
private val TextPrimary = Color(0xFFF2F2F2)
private val TextSecondary = Color(0xFF8C8C8C)
private val TextMuted = Color(0xFF555555)
private val TierGreen = Color(0xFF4CAF50)
private val TierYellow = Color(0xFFFFC107)
private val TierRed = Color(0xFFF44336)

// ============================================================================
// DATA MODELS & BILINGUAL STRINGS (ENGLISH + ARABIC RTL)
// ============================================================================
enum class AppTier { GREEN, YELLOW, RED }

data class AppModel(
    val label: String,
    val packageName: String,
    val tier: AppTier = AppTier.GREEN,
    val dailyQuotaMinutes: Int = 0 // 0 = disabled, 1..120 = minutes/day (Red Tier)
)

data class IntentionEntry(
    val timestamp: Long,
    val appLabel: String,
    val intention: String,
    val durationMin: Int
)

data class ActiveSession(
    val packageName: String,
    val appLabel: String,
    val tier: AppTier,
    val endTimeMillis: Long
)

data class Strings(
    val searchPlaceholder: String,
    val activeSessionBanner: (String, String) -> String,
    val cooldownLabel: (Int) -> String,
    val quotaReachedLabel: String,
    val quotaProgress: (Int, Int) -> String,
    val breathingTitle: String,
    val breathingSubtitle: (Int) -> String,
    val intentionTitle: (String) -> String,
    val intentionPlaceholder: String,
    val intentionMinCharsWarning: (Int) -> String,
    val durationLabel: (Int) -> String,
    val beginSessionBtn: String,
    val cancelBtn: String,
    val recentIntentionsTitle: String,
    val settingsTitle: String,
    val unlockSettingsBtn: (Int) -> String,
    val settingsLockedHint: String,
    val settingsUnlockedBadge: String,
    val languageLabel: String,
    val focusModeLabel: String,
    val focusModeDesc: String,
    val showQuotesLabel: String,
    val quotesManageTitle: String,
    val addQuotePlaceholder: String,
    val addBtn: String,
    val showWidgetLabel: String,
    val selectWidgetBtn: String,
    val removeWidgetBtn: String,
    val preSessionDelayLabel: (Int) -> String,
    val redCooldownLabel: (Int) -> String,
    val impactReportTitle: String,
    val grantUsagePermBtn: String,
    val grantOverlayPermBtn: String,
    val setDefaultHomeBtn: String,
    val todayUsageLabel: (Int) -> String,
    val sevenDayAvgLabel: (Int) -> String,
    val impactDiffSaved: (Int) -> String,
    val impactDiffExceeded: (Int) -> String,
    val appManagementTitle: String,
    val dailyQuotaSliderLabel: (Int) -> String,
    val violationInterceptedMsg: (String) -> String
)

private val EnglishStrings = Strings(
    searchPlaceholder = "Search apps...",
    activeSessionBanner = { app, rem -> "ACTIVE: $app ($rem)" },
    cooldownLabel = { mins -> "COOLDOWN (${mins}m left)" },
    quotaReachedLabel = "DAILY QUOTA LOCKED UNTIL 00:00",
    quotaProgress = { used, max -> "${used}/${max}m" },
    breathingTitle = "Take a mindful breath.",
    breathingSubtitle = { sec -> "Wait $sec seconds before continuing..." },
    intentionTitle = { app -> "Intention for $app" },
    intentionPlaceholder = "Why are you opening this app? (min 6 chars)",
    intentionMinCharsWarning = { rem -> "$rem more character(s) required" },
    durationLabel = { mins -> "Session Duration: $mins min" },
    beginSessionBtn = "Begin Session",
    cancelBtn = "Cancel",
    recentIntentionsTitle = "Recent Intentions Log",
    settingsTitle = "LAUNCHER SETTINGS",
    unlockSettingsBtn = { sec -> if (sec > 0) "Hold on... Unlocking in ${sec}s" else "Unlock Settings (5s Gate)" },
    settingsLockedHint = "Critical controls are locked to prevent impulsive changes.",
    settingsUnlockedBadge = "SETTINGS UNLOCKED",
    languageLabel = "Language / اللغة",
    focusModeLabel = "Strict Focus Mode",
    focusModeDesc = "Blocks all Yellow & Red tier apps immediately",
    showQuotesLabel = "Show Motivational Quote on Home",
    quotesManageTitle = "Motivational Quotes (Random on Unlock)",
    addQuotePlaceholder = "Enter a new quote or reminder...",
    addBtn = "Add",
    showWidgetLabel = "Enable Home Screen Widget Container",
    selectWidgetBtn = "Select / Change Widget",
    removeWidgetBtn = "Remove Widget",
    preSessionDelayLabel = { sec -> "Breathing Delay: ${sec}s" },
    redCooldownLabel = { min -> "Red Tier Cooldown: ${min}m" },
    impactReportTitle = "BEFORE / AFTER IMPACT REPORT",
    grantUsagePermBtn = "Grant Usage Access Permission",
    grantOverlayPermBtn = "Grant Overlay Permission (Strict Interrupts)",
    setDefaultHomeBtn = "Set as Default Home Launcher",
    todayUsageLabel = { min -> "Today's Total Screen Time: ${min}m" },
    sevenDayAvgLabel = { min -> "Past 7-Day Daily Average: ${min}m" },
    impactDiffSaved = { min -> "✓ $min minutes saved today vs 7-day average" },
    impactDiffExceeded = { min -> "⚠ $min minutes above your 7-day average" },
    appManagementTitle = "APP TIERS & RED DAILY QUOTAS",
    dailyQuotaSliderLabel = { min -> if (min == 0) "Daily Quota: Disabled (0m)" else "Daily Quota: ${min}m / day" },
    violationInterceptedMsg = { app -> "Session expired or unauthorized Recents access blocked for $app." }
)

private val ArabicStrings = Strings(
    searchPlaceholder = "ابحث عن التطبيقات...",
    activeSessionBanner = { app, rem -> "جلسة نشطة: $app ($rem)" },
    cooldownLabel = { mins -> "فترة تهدئة (متبقي ${mins} د)" },
    quotaReachedLabel = "انتهت الحصة اليومية حتى منتصف الليل",
    quotaProgress = { used, max -> "${used}/${max} د" },
    breathingTitle = "خذ نفساً عميقاً بوعي.",
    breathingSubtitle = { sec -> "انتظر $sec ثوانٍ قبل المتابعة..." },
    intentionTitle = { app -> "النية لفتح $app" },
    intentionPlaceholder = "لماذا تفتح هذا التطبيق؟ (6 أحرف على الأقل)",
    intentionMinCharsWarning = { rem -> "مطلوب $rem أحرف إضافية" },
    durationLabel = { mins -> "مدة الجلسة: $mins دقيقة" },
    beginSessionBtn = "بدء الجلسة",
    cancelBtn = "إلغاء",
    recentIntentionsTitle = "سجل النوايا السابقة",
    settingsTitle = "إعدادات المشغل",
    unlockSettingsBtn = { sec -> if (sec > 0) "يرجى الانتظار... يفتح خلال ${sec}ث" else "فتح الإعدادات (بوابة 5 ثوانٍ)" },
    settingsLockedHint = "الإعدادات الحساسة مقفلة لمنع التغييرات الاندفاعية.",
    settingsUnlockedBadge = "الإعدادات مفتوحة الآن",
    languageLabel = "اللغة / Language",
    focusModeLabel = "وضع التركيز الصارم",
    focusModeDesc = "يحظر جميع تطبيقات الفئة الصفراء والحمراء فوراً",
    showQuotesLabel = "إظهار اقتباس تحفيزي في الشاشة الرئيسية",
    quotesManageTitle = "الاقتباسات التحفيزية (عشوائي عند فتح القفل)",
    addQuotePlaceholder = "أضف اقتباساً أو تذكيراً جديداً...",
    addBtn = "إضافة",
    showWidgetLabel = "تفعيل حاوية الويدجت في الشاشة الرئيسية",
    selectWidgetBtn = "اختيار / تغيير الويدجت",
    removeWidgetBtn = "إزالة الويدجت",
    preSessionDelayLabel = { sec -> "تأخير التنفس قبل الجلسة: ${sec} ث" },
    redCooldownLabel = { min -> "تهدئة الفئة الحمراء: ${min} د" },
    impactReportTitle = "تقرير الأثر (قبل / بعد)",
    grantUsagePermBtn = "منح إذن الوصول لبيانات الاستخدام",
    grantOverlayPermBtn = "منح إذن الظهور فوق التطبيقات",
    setDefaultHomeBtn = "تعيين كمشغل الشاشة الرئيسية الافتراضي",
    todayUsageLabel = { min -> "إجمالي الاستخدام اليوم: ${min} دقيقة" },
    sevenDayAvgLabel = { min -> "متوسط آخر 7 أيام: ${min} دقيقة" },
    impactDiffSaved = { min -> "✓ وفرت $min دقيقة اليوم مقارنة بمتوسط 7 أيام" },
    impactDiffExceeded = { min -> "⚠ تجاوزت متوسط 7 أيام بمقدار $min دقيقة" },
    appManagementTitle = "فئات التطبيقات والحصة اليومية",
    dailyQuotaSliderLabel = { min -> if (min == 0) "الحصة اليومية: غير مفعلة (0 د)" else "الحصة اليومية: ${min} د / يوم" },
    violationInterceptedMsg = { app -> "انتهت الجلسة أو تم حظر الوصول العشوائي لتطبيق $app." }
)

// ============================================================================
// PERSISTENCE & STATE REPOSITORY
// ============================================================================
class LauncherRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("minimalist_launcher_prefs", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString("language", "en") ?: "en"
        set(value) = prefs.edit().putString("language", value).apply()

    var preSessionDelaySec: Int
        get() = prefs.getInt("pre_session_delay", 5).coerceIn(3, 10)
        set(value) = prefs.edit().putInt("pre_session_delay", value.coerceIn(3, 10)).apply()

    var redCooldownMin: Int
        get() = prefs.getInt("red_cooldown_min", 15).coerceIn(5, 60)
        set(value) = prefs.edit().putInt("red_cooldown_min", value.coerceIn(5, 60)).apply()

    var focusMode: Boolean
        get() = prefs.getBoolean("focus_mode", false)
        set(value) = prefs.edit().putBoolean("focus_mode", value).apply()

    var showWidget: Boolean
        get() = prefs.getBoolean("show_widget", false)
        set(value) = prefs.edit().putBoolean("show_widget", value).apply()

    var appWidgetId: Int
        get() = prefs.getInt("app_widget_id", AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) = prefs.edit().putInt("app_widget_id", value).apply()

    var showQuotes: Boolean
        get() = prefs.getBoolean("show_quotes", true)
        set(value) = prefs.edit().putBoolean("show_quotes", value).apply()

    fun getQuotes(): List<String> {
        val raw = prefs.getString("quotes_json", null)
        if (raw == null) {
            val defaults = listOf(
                "Discipline equals freedom.",
                "Spend your attention where it matters.",
                "Do not let the urgent drown out the important."
            )
            saveQuotes(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveQuotes(quotes: List<String>) {
        val arr = JSONArray()
        quotes.forEach { arr.put(it) }
        prefs.edit().putString("quotes_json", arr.toString()).apply()
    }

    fun getAppTier(pkg: String): AppTier {
        val name = prefs.getString("tier_$pkg", AppTier.GREEN.name) ?: AppTier.GREEN.name
        return try {
            AppTier.valueOf(name)
        } catch (e: Exception) {
            AppTier.GREEN
        }
    }

    fun setAppTier(pkg: String, tier: AppTier) {
        prefs.edit().putString("tier_$pkg", tier.name).apply()
    }

    fun getDailyQuota(pkg: String): Int =
        prefs.getInt("quota_$pkg", 0).coerceIn(0, 120)

    fun setDailyQuota(pkg: String, minutes: Int) {
        prefs.edit().putInt("quota_$pkg", minutes.coerceIn(0, 120)).apply()
    }

    fun getCooldownUntil(pkg: String): Long =
        prefs.getLong("cooldown_until_$pkg", 0L)

    fun setCooldownUntil(pkg: String, timestampMillis: Long) {
        prefs.edit().putLong("cooldown_until_$pkg", timestampMillis).apply()
    }

    fun getIntentions(): List<IntentionEntry> {
        val raw = prefs.getString("intentions_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<IntentionEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    IntentionEntry(
                        timestamp = obj.getLong("ts"),
                        appLabel = obj.getString("app"),
                        intention = obj.getString("text"),
                        durationMin = obj.getInt("dur")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addIntention(entry: IntentionEntry) {
        val current = getIntentions().toMutableList()
        current.add(0, entry)
        val trimmed = current.take(40)
        val arr = JSONArray()
        trimmed.forEach {
            val obj = JSONObject()
            obj.put("ts", it.timestamp)
            obj.put("app", it.appLabel)
            obj.put("text", it.intention)
            obj.put("dur", it.durationMin)
            arr.put(obj)
        }
        prefs.edit().putString("intentions_json", arr.toString()).apply()
    }
}

// ============================================================================
// MAIN ACTIVITY (SINGLE-FILE ARCHITECTURE)
// ============================================================================
class MainActivity : ComponentActivity() {

    private lateinit var repo: LauncherRepository
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private val APPWIDGET_HOST_ID = 2048

    // Observable UI States
    private val installedApps = mutableStateListOf<AppModel>()
    private var activeSession by mutableStateOf<ActiveSession?>(null)
    private var currentQuote by mutableStateOf("")
    private var quotesList = mutableStateListOf<String>()
    private var showQuotesState by mutableStateOf(true)
    private var languageState by mutableStateOf("en")
    private var focusModeState by mutableStateOf(false)
    private var showWidgetState by mutableStateOf(false)
    private var widgetIdState by mutableIntStateOf(AppWidgetManager.INVALID_APPWIDGET_ID)
    private var preSessionDelayState by mutableIntStateOf(5)
    private var redCooldownState by mutableIntStateOf(15)
    private var intentionsHistory = mutableStateListOf<IntentionEntry>()
    private var interceptionBannerMessage by mutableStateOf<String?>(null)
    private var currentTimeMillis by mutableLongStateOf(System.currentTimeMillis())

    // Dynamic Usage Tracking Maps (Updated every tick)
    private val appDailyUsedMinutes = mutableStateMapOf<String, Int>()
    private val appCooldownUntilMap = mutableStateMapOf<String, Long>()
    private var todayTotalScreenMinutes by mutableIntStateOf(0)
    private var sevenDayAverageMinutes by mutableIntStateOf(0)
    private var hasUsagePermissionState by mutableStateOf(false)

    // Receiver for rotating random motivational quote on every phone unlock
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT || intent?.action == Intent.ACTION_SCREEN_ON) {
                pickRandomQuote()
            }
        }
    }

    // Widget selection result handler
    private val widgetPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (result.resultCode == Activity.RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val info = appWidgetManager.getAppWidgetInfo(id)
            if (info?.configure != null) {
                val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                }
                widgetConfigLauncher.launch(configIntent)
            } else {
                saveSelectedWidget(id)
            }
        } else if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetHost.deleteAppWidgetId(id)
        }
    }

    private val widgetConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (result.resultCode == Activity.RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            saveSelectedWidget(id)
        } else if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetHost.deleteAppWidgetId(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent sensitive snapshots in Android's Recents screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        repo = LauncherRepository(this)
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(applicationContext, APPWIDGET_HOST_ID)
        appWidgetHost.startListening()

        // Initialize persisted states
        languageState = repo.language
        focusModeState = repo.focusMode
        showWidgetState = repo.showWidget
        widgetIdState = repo.appWidgetId
        preSessionDelayState = repo.preSessionDelaySec
        redCooldownState = repo.redCooldownMin
        showQuotesState = repo.showQuotes
        quotesList.clear()
        quotesList.addAll(repo.getQuotes())
        intentionsHistory.clear()
        intentionsHistory.addAll(repo.getIntentions())
        pickRandomQuote()

        // Register unlock broadcast receiver for quote rotation
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }

        loadInstalledApps()
        startEnforcementAndMonitorLoop()

        setContent {
            val strings = if (languageState == "ar") ArabicStrings else EnglishStrings
            val layoutDirection = if (languageState == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = BgBlack,
                        surface = SurfaceDark,
                        primary = TextPrimary,
                        onBackground = TextPrimary,
                        onSurface = TextPrimary
                    )
                ) {
                    // Consume system Back button on Home Screen so it does nothing
                    BackHandler(enabled = true) { /* Strictly no-op on home screen */ }

                    LauncherRootScreen(strings = strings)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasUsagePermissionState = hasUsageStatsPermission()
        loadInstalledApps()
        updateUsageStatsAndQuotas()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(unlockReceiver)
            appWidgetHost.stopListening()
        } catch (_: Exception) {}
    }

    private fun pickRandomQuote() {
        if (quotesList.isNotEmpty()) {
            currentQuote = quotesList.random()
        } else {
            currentQuote = ""
        }
    }

    private fun saveSelectedWidget(id: Int) {
        if (widgetIdState != AppWidgetManager.INVALID_APPWIDGET_ID && widgetIdState != id) {
            appWidgetHost.deleteAppWidgetId(widgetIdState)
        }
        repo.appWidgetId = id
        widgetIdState = id
        repo.showWidget = true
        showWidgetState = true
    }

    private fun launchWidgetPicker() {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        widgetPickerLauncher.launch(pickIntent)
    }

    private fun removeCurrentWidget() {
        if (widgetIdState != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetHost.deleteAppWidgetId(widgetIdState)
        }
        repo.appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        widgetIdState = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val myPkg = packageName
        val loaded = resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == myPkg) return@mapNotNull null
                val label = ri.loadLabel(pm)?.toString()?.trim() ?: pkg
                val tier = repo.getAppTier(pkg)
                val quota = repo.getDailyQuota(pkg)
                appCooldownUntilMap[pkg] = repo.getCooldownUntil(pkg)
                AppModel(label = label, packageName = pkg, tier = tier, dailyQuotaMinutes = quota)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }

        installedApps.clear()
        installedApps.addAll(loaded)
    }

    // ========================================================================
    // ENFORCEMENT ENGINE: TIMERS, COOLDOWNS & RECENTS LOOPHOLE PREVENTION
    // ========================================================================
    private fun startEnforcementAndMonitorLoop() {
        lifecycleScope.launch {
            var tickCounter = 0
            while (isActive) {
                val now = System.currentTimeMillis()
                currentTimeMillis = now

                // 1. Check active timed session expiration
                val session = activeSession
                if (session != null && now >= session.endTimeMillis) {
                    val expiredApp = session.appLabel
                    val expiredPkg = session.packageName
                    val expiredTier = session.tier
                    activeSession = null

                    // If Red Tier, lock app into mandatory cooldown
                    if (expiredTier == AppTier.RED) {
                        val cooldownEnd = now + (redCooldownState * 60_000L)
                        repo.setCooldownUntil(expiredPkg, cooldownEnd)
                        appCooldownUntilMap[expiredPkg] = cooldownEnd
                    }

                    val strings = if (languageState == "ar") ArabicStrings else EnglishStrings
                    interceptionBannerMessage = strings.violationInterceptedMsg(expiredApp)
                    bringLauncherToFrontImmediately()
                }

                // 2. Recents Screen Loophole Guard:
                // Every second, check the foreground app via UsageEvents. If the user hopped
                // into a Yellow/Red app via the Android Recents Switcher without an authorized
                // active session (or while in Focus Mode / Cooldown / Daily Quota Lock), intercept immediately!
                if (hasUsageStatsPermission()) {
                    val fgPkg = getCurrentForegroundPackage()
                    if (fgPkg != null && fgPkg != packageName && !isSystemUiOrSettings(fgPkg)) {
                        val tier = repo.getAppTier(fgPkg)
                        if (tier == AppTier.YELLOW || tier == AppTier.RED) {
                            val validSessionForFg = activeSession?.let {
                                it.packageName == fgPkg && now < it.endTimeMillis
                            } ?: false

                            val cooldownUntil = repo.getCooldownUntil(fgPkg)
                            val inCooldown = tier == AppTier.RED && now < cooldownUntil

                            val quota = repo.getDailyQuota(fgPkg)
                            val usedToday = appDailyUsedMinutes[fgPkg] ?: 0
                            val quotaExceeded = tier == AppTier.RED && quota > 0 && usedToday >= quota

                            if (focusModeState || !validSessionForFg || inCooldown || quotaExceeded) {
                                val appName = installedApps.find { it.packageName == fgPkg }?.label ?: fgPkg
                                val strings = if (languageState == "ar") ArabicStrings else EnglishStrings
                                interceptionBannerMessage = strings.violationInterceptedMsg(appName)
                                bringLauncherToFrontImmediately()
                            }
                        }
                    }
                }

                // Refresh usage stats & daily quotas every 10 seconds
                if (tickCounter % 10 == 0) {
                    updateUsageStatsAndQuotas()
                }
                tickCounter++
                delay(1000L)
            }
        }
    }

    private fun isSystemUiOrSettings(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
                pkg == "com.android.settings" ||
                pkg.contains("inputmethod") ||
                pkg.contains("keyboard") ||
                pkg == "com.google.android.permissioncontroller"
    }

    private fun bringLauncherToFrontImmediately() {
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(homeIntent)
    }

    private fun launchExternalApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            // Exclude regulated launches from leaving easy recents snapshots where possible
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            startActivity(launchIntent)
        }
    }

    // ========================================================================
    // USAGE STATS MANAGER (7-DAY IMPACT REPORT & DAILY QUOTA CALCULATION)
    // ========================================================================
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getCurrentForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 15_000L
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        var lastForegroundPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg
    }

    private fun updateUsageStatsAndQuotas() {
        if (!hasUsageStatsPermission()) {
            hasUsagePermissionState = false
            return
        }
        hasUsagePermissionState = true
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        // Calculate Midnight Today
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val midnightToday = cal.timeInMillis

        // 1. Today's per-app usage & total usage
        val todayStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, midnightToday, now)
        var todayTotalMs = 0L
        val perAppMs = mutableMapOf<String, Long>()

        todayStats?.forEach { stat ->
            if (stat.lastTimeUsed >= midnightToday && stat.packageName != packageName && !isSystemUiOrSettings(stat.packageName)) {
                val fgTime = stat.totalTimeInForeground
                if (fgTime > 0) {
                    perAppMs[stat.packageName] = (perAppMs[stat.packageName] ?: 0L) + fgTime
                    todayTotalMs += fgTime
                }
            }
        }

        perAppMs.forEach { (pkg, ms) ->
            appDailyUsedMinutes[pkg] = (ms / 60_000L).toInt()
        }
        todayTotalScreenMinutes = (todayTotalMs / 60_000L).toInt()

        // 2. Past 7-Day Average Calculation
        val sevenDaysAgo = midnightToday - (7L * 24L * 60L * 60L * 1000L)
        val weekStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sevenDaysAgo, midnightToday)
        var weekTotalMs = 0L
        weekStats?.forEach { stat ->
            if (stat.packageName != packageName && !isSystemUiOrSettings(stat.packageName)) {
                weekTotalMs += stat.totalTimeInForeground
            }
        }
        sevenDayAverageMinutes = ((weekTotalMs / 7L) / 60_000L).toInt()
    }

    // ========================================================================
    // SMART PREFIX-FIRST SEARCH & APP ENGAGEMENT LOGIC
    // ========================================================================
    private fun filterAndSortApps(query: String, apps: List<AppModel>): List<AppModel> {
        val cleanQuery = query.trim().lowercase(Locale.getDefault())
        if (cleanQuery.isEmpty()) return emptyList()

        val startsWithList = mutableListOf<AppModel>()
        val containsList = mutableListOf<AppModel>()

        for (app in apps) {
            val lowerLabel = app.label.lowercase(Locale.getDefault())
            when {
                lowerLabel.startsWith(cleanQuery) -> startsWithList.add(app)
                lowerLabel.contains(cleanQuery) -> containsList.add(app)
            }
        }

        startsWithList.sortBy { it.label.lowercase(Locale.getDefault()) }
        containsList.sortBy { it.label.lowercase(Locale.getDefault()) }

        return startsWithList + containsList
    }

    private fun isAppLockedOrInCooldown(app: AppModel, nowMillis: Long): Pair<Boolean, String?> {
        val strings = if (languageState == "ar") ArabicStrings else EnglishStrings
        if (focusModeState && (app.tier == AppTier.YELLOW || app.tier == AppTier.RED)) {
            return true to strings.focusModeLabel
        }
        if (app.tier == AppTier.RED) {
            val cooldownEnd = appCooldownUntilMap[app.packageName] ?: 0L
            if (nowMillis < cooldownEnd) {
                val minsLeft = ((cooldownEnd - nowMillis + 59_999L) / 60_000L).toInt()
                return true to strings.cooldownLabel(minsLeft)
            }
            if (app.dailyQuotaMinutes > 0) {
                val used = appDailyUsedMinutes[app.packageName] ?: 0
                if (used >= app.dailyQuotaMinutes) {
                    return true to strings.quotaReachedLabel
                }
            }
        }
        return false to null
    }

    // ========================================================================
    // JETPACK COMPOSE UI: ROOT, HOME SCREEN, DIALOGS, & GATED DRAWER
    // ========================================================================
    @Composable
    private fun LauncherRootScreen(strings: Strings) {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current

        var searchQuery by remember { mutableStateOf("") }
        var pendingMindfulApp by remember { mutableStateOf<AppModel?>(null) }

        // Reset Settings 5-second Security Gate whenever the drawer closes
        var isSettingsUnlocked by remember { mutableStateOf(false) }
        var unlockCountdownSec by remember { mutableIntStateOf(0) }

        LaunchedEffect(drawerState.isClosed) {
            if (drawerState.isClosed) {
                isSettingsUnlocked = false
                unlockCountdownSec = 0
            } else {
                // Clear keyboard when drawer is swiped open
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }

        LaunchedEffect(unlockCountdownSec) {
            if (unlockCountdownSec > 0) {
                delay(1000L)
                unlockCountdownSec -= 1
                if (unlockCountdownSec == 0) {
                    isSettingsUnlocked = true
                }
            }
        }

        // Responsive Tablet / Phone width constraint
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp
        val contentMaxWidth = if (screenWidthDp >= 600) 560.dp else Dp.Unspecified

        val matchingApps = remember(searchQuery, installedApps.toList()) {
            filterAndSortApps(searchQuery, installedApps)
        }

        fun handleAppSelection(app: AppModel) {
            val (locked, _) = isAppLockedOrInCooldown(app, currentTimeMillis)
            if (locked) return

            focusManager.clearFocus()
            keyboardController?.hide()
            searchQuery = ""

            when (app.tier) {
                AppTier.GREEN -> {
                    launchExternalApp(app.packageName)
                }
                AppTier.YELLOW, AppTier.RED -> {
                    // If already has an active session for this exact app, resume it directly
                    val current = activeSession
                    if (current != null && current.packageName == app.packageName && currentTimeMillis < current.endTimeMillis) {
                        launchExternalApp(app.packageName)
                    } else {
                        pendingMindfulApp = app
                    }
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = BgBlack,
                    drawerContentColor = TextPrimary,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 420.dp)
                        .border(1.dp, BorderSubtle)
                ) {
                    SettingsDrawerContent(
                        strings = strings,
                        isUnlocked = isSettingsUnlocked,
                        unlockCountdown = unlockCountdownSec,
                        onStartUnlockCountdown = {
                            if (!isSettingsUnlocked && unlockCountdownSec == 0) {
                                unlockCountdownSec = 5
                            }
                        }
                    )
                }
            }
        ) {
            // STRICT ZERO-CLUTTER HOME SCREEN
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgBlack)
                    .pointerInput(Unit) {
                        // Tapping anywhere on blank background clears search focus and hides keyboard
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            interceptionBannerMessage = null
                        })
                    }
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (contentMaxWidth != Dp.Unspecified) Modifier.width(contentMaxWidth) else Modifier.fillMaxWidth()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(36.dp))

                    // (1) MINIMALIST CLOCK
                    MinimalistClock(currentTimeMillis = currentTimeMillis, isArabic = languageState == "ar")

                    // OPTIONAL CENTERED MOTIVATIONAL QUOTE (Random on Phone Unlock)
                    if (showQuotesState && currentQuote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "\"$currentQuote\"",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }

                    // (2) ACTIVE SESSION COUNTDOWN INDICATOR (Only visible when a timed app is running)
                    val session = activeSession
                    if (session != null && currentTimeMillis < session.endTimeMillis) {
                        val remSec = ((session.endTimeMillis - currentTimeMillis) / 1000L).coerceAtLeast(0L)
                        val mm = remSec / 60
                        val ss = remSec % 60
                        val formatted = String.format(Locale.US, "%02d:%02d", mm, ss)

                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceDark)
                                .border(1.dp, if (session.tier == AppTier.RED) TierRed else TierYellow, RoundedCornerShape(6.dp))
                                .clickable { launchExternalApp(session.packageName) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (session.tier == AppTier.RED) TierRed else TierYellow)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = strings.activeSessionBanner(session.appLabel, formatted),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Interception Notice (if a session expired or Recents bypass was blocked)
                    AnimatedVisibility(visible = interceptionBannerMessage != null) {
                        interceptionBannerMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = msg,
                                color = TierRed,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceDark)
                                    .border(1.dp, TierRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(10.dp)
                            )
                        }
                    }

                    // (3) OPTIONAL WIDGET CONTAINER (Only visible if toggled on in settings)
                    if (showWidgetState && widgetIdState != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        Spacer(modifier = Modifier.height(20.dp))
                        HomeWidgetContainer(appWidgetId = widgetIdState)
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // (4) NON-AUTO-FOCUSING SEARCH BAR
                    MinimalistSearchBar(
                        query = searchQuery,
                        placeholder = strings.searchPlaceholder,
                        onQueryChange = {
                            searchQuery = it
                            interceptionBannerMessage = null
                        },
                        onKeyboardEnter = {
                            if (matchingApps.isNotEmpty()) {
                                handleAppSelection(matchingApps.first())
                            }
                        }
                    )

                    // HIDDEN APP LIST UNTIL SEARCH IS ACTIVELY TYPED
                    if (searchQuery.trim().isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp)),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            items(matchingApps, key = { it.packageName }) { app ->
                                AppSearchResultRow(
                                    app = app,
                                    strings = strings,
                                    nowMillis = currentTimeMillis,
                                    onClick = { handleAppSelection(app) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // PRE-SESSION BREATHING DELAY + INTENTION PROMPT DIALOG (Yellow & Red Tiers)
        pendingMindfulApp?.let { targetApp ->
            MindfulSessionDialog(
                app = targetApp,
                strings = strings,
                breathingDelaySec = preSessionDelayState,
                intentionsHistory = intentionsHistory,
                onDismiss = { pendingMindfulApp = null },
                onStartSession = { intentionText, durationMinutes ->
                    val now = System.currentTimeMillis()
                    repo.addIntention(
                        IntentionEntry(
                            timestamp = now,
                            appLabel = targetApp.label,
                            intention = intentionText,
                            durationMin = durationMinutes
                        )
                    )
                    intentionsHistory.clear()
                    intentionsHistory.addAll(repo.getIntentions())

                    activeSession = ActiveSession(
                        packageName = targetApp.packageName,
                        appLabel = targetApp.label,
                        tier = targetApp.tier,
                        endTimeMillis = now + (durationMinutes * 60_000L)
                    )
                    pendingMindfulApp = null
                    launchExternalApp(targetApp.packageName)
                }
            )
        }
    }

    @Composable
    private fun MinimalistClock(currentTimeMillis: Long, isArabic: Boolean) {
        val locale = if (isArabic) Locale("ar") else Locale.ENGLISH
        val timeStr = remember(currentTimeMillis / 1000L, isArabic) {
            SimpleDateFormat("HH:mm", Locale.US).format(Date(currentTimeMillis))
        }
        val dateStr = remember(currentTimeMillis / 60_000L, isArabic) {
            SimpleDateFormat("EEEE, d MMMM", locale).format(Date(currentTimeMillis))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeStr,
                color = TextPrimary,
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = dateStr,
                color = TextSecondary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun HomeWidgetContainer(appWidgetId: Int) {
        val info = remember(appWidgetId) { appWidgetManager.getAppWidgetInfo(appWidgetId) }
        if (info != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val hostView = appWidgetHost.createView(ctx, appWidgetId, info)
                        hostView.setAppWidget(appWidgetId, info)
                        addView(hostView)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(6.dp)
            )
        }
    }

    @Composable
    private fun MinimalistSearchBar(
        query: String,
        placeholder: String,
        onQueryChange: (String) -> Unit,
        onKeyboardEnter: () -> Unit
    ) {
        // Never requests focus automatically on open/return
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    color = TextMuted,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { onKeyboardEnter() },
                    onDone = { onKeyboardEnter() },
                    onSearch = { onKeyboardEnter() }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun AppSearchResultRow(
        app: AppModel,
        strings: Strings,
        nowMillis: Long,
        onClick: () -> Unit
    ) {
        val (isLocked, lockReason) = isAppLockedOrInCooldown(app, nowMillis)
        val dotColor = when (app.tier) {
            AppTier.GREEN -> TierGreen
            AppTier.YELLOW -> TierYellow
            AppTier.RED -> TierRed
        }
        val usedMins = appDailyUsedMinutes[app.packageName] ?: 0

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isLocked) { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isLocked) TextMuted else dotColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = app.label,
                    color = if (isLocked) TextMuted else TextPrimary,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Display used/total daily quota next to Red apps if enabled
                if (app.tier == AppTier.RED && app.dailyQuotaMinutes > 0) {
                    Text(
                        text = strings.quotaProgress(usedMins, app.dailyQuotaMinutes),
                        color = if (isLocked) TextMuted else TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (isLocked && lockReason != null) {
                    Text(
                        text = lockReason,
                        color = TierRed.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // ========================================================================
    // PRE-SESSION BREATHING DELAY + INTENTION & DURATION DIALOG
    // ========================================================================
    @Composable
    private fun MindfulSessionDialog(
        app: AppModel,
        strings: Strings,
        breathingDelaySec: Int,
        intentionsHistory: List<IntentionEntry>,
        onDismiss: () -> Unit,
        onStartSession: (String, Int) -> Unit
    ) {
        var remainingBreathingSec by remember { mutableIntStateOf(breathingDelaySec) }
        var intentionText by remember { mutableStateOf("") }
        val maxSliderMinutes = if (app.tier == AppTier.RED) 30f else 60f
        var durationMinutes by remember { mutableFloatStateOf(if (app.tier == AppTier.RED) 10f else 15f) }

        LaunchedEffect(Unit) {
            while (remainingBreathingSec > 0) {
                delay(1000L)
                remainingBreathingSec -= 1
            }
        }

        val trimmedLength = intentionText.trim().length
        val isIntentionValid = trimmedLength >= 6

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 460.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(
                        1.dp,
                        if (app.tier == AppTier.RED) TierRed.copy(alpha = 0.6f) else TierYellow.copy(alpha = 0.6f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(22.dp)
            ) {
                if (remainingBreathingSec > 0) {
                    // PHASE 1: Mandatory Pre-Session Breathing Delay
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = strings.breathingTitle,
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "$remainingBreathingSec",
                            color = if (app.tier == AppTier.RED) TierRed else TierYellow,
                            fontSize = 44.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = strings.breathingSubtitle(remainingBreathingSec),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = onDismiss,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Text(strings.cancelBtn, color = TextSecondary, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    // PHASE 2: Intention Input (>= 6 chars) + Duration Slider + Constrained Scrollable History
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = strings.intentionTitle(app.label),
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = intentionText,
                            onValueChange = { intentionText = it },
                            placeholder = {
                                Text(
                                    strings.intentionPlaceholder,
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (!isIntentionValid) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = strings.intentionMinCharsWarning(6 - trimmedLength),
                                color = TierYellow,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = strings.durationLabel(durationMinutes.roundToInt()),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Slider(
                            value = durationMinutes,
                            onValueChange = { durationMinutes = it },
                            valueRange = 1f..maxSliderMinutes,
                            colors = SliderDefaults.colors(
                                thumbColor = if (app.tier == AppTier.RED) TierRed else TierYellow,
                                activeTrackColor = if (app.tier == AppTier.RED) TierRed else TierYellow
                            )
                        )

                        // Scrollable Intention History inside a strictly constrained height box
                        if (intentionsHistory.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = strings.recentIntentionsTitle,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BgBlack)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                items(intentionsHistory) { item ->
                                    val timeFmt = remember(item.timestamp) {
                                        SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(item.timestamp))
                                    }
                                    Text(
                                        text = "[$timeFmt] ${item.appLabel} (${item.durationMin}m): ${item.intention}",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text(strings.cancelBtn, color = TextSecondary, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onStartSession(intentionText.trim(), durationMinutes.roundToInt())
                                },
                                enabled = isIntentionValid,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (app.tier == AppTier.RED) TierRed else TierYellow,
                                    contentColor = BgBlack,
                                    disabledContainerColor = BorderSubtle,
                                    disabledContentColor = TextMuted
                                )
                            ) {
                                Text(
                                    strings.beginSessionBtn,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // HIDDEN, GATED SETTINGS NAVIGATION DRAWER
    // ========================================================================
    @Composable
    private fun SettingsDrawerContent(
        strings: Strings,
        isUnlocked: Boolean,
        unlockCountdown: Int,
        onStartUnlockCountdown: () -> Unit
    ) {
        var newQuoteInput by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(18.dp)
        ) {
            Text(
                text = strings.settingsTitle,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 5-SECOND ANTI-IMPULSE SECURITY GATE
            if (!isUnlocked) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                        .border(1.dp, TierYellow.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = strings.settingsLockedHint,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onStartUnlockCountdown,
                        enabled = unlockCountdown == 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TierYellow,
                            contentColor = BgBlack
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = strings.unlockSettingsBtn(unlockCountdown),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                Text(
                    text = strings.settingsUnlockedBadge,
                    color = TierGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(14.dp))

            // BEFORE / AFTER 7-DAY USAGE IMPACT REPORT
            Text(
                text = strings.impactReportTitle,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!hasUsagePermissionState) {
                OutlinedButton(
                    onClick = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.grantUsagePermBtn, color = TierYellow, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = strings.todayUsageLabel(todayTotalScreenMinutes),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.sevenDayAvgLabel(sevenDayAverageMinutes),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val diff = sevenDayAverageMinutes - todayTotalScreenMinutes
                    if (diff >= 0) {
                        Text(
                            text = strings.impactDiffSaved(diff),
                            color = TierGreen,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = strings.impactDiffExceeded(-diff),
                            color = TierRed,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // SYSTEM PERMISSIONS & DEFAULT HOME LAUNCHER BUTTONS
            Spacer(modifier = Modifier.height(10.dp))
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                OutlinedButton(
                    onClick = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.grantOverlayPermBtn, color = TierYellow, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }

            OutlinedButton(
                onClick = {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.setDefaultHomeBtn, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(14.dp))

            // LANGUAGE SWITCHER (ENGLISH / ARABIC RTL)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.languageLabel, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Row {
                    FilterChip(
                        selected = languageState == "en",
                        onClick = {
                            languageState = "en"
                            repo.language = "en"
                        },
                        label = { Text("EN", fontFamily = FontFamily.Monospace) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = languageState == "ar",
                        onClick = {
                            languageState = "ar"
                            repo.language = "ar"
                        },
                        label = { Text("العربية", fontFamily = FontFamily.Monospace) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // STRICT FOCUS MODE (Gated when ON so user can't impulsively turn it off)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.focusModeLabel, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(strings.focusModeDesc, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Switch(
                    checked = focusModeState,
                    enabled = isUnlocked || !focusModeState,
                    onCheckedChange = {
                        focusModeState = it
                        repo.focusMode = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // MOTIVATIONAL QUOTES TOGGLE & RANDOM-ON-UNLOCK LIST MANAGER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    strings.showQuotesLabel,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showQuotesState,
                    onCheckedChange = {
                        showQuotesState = it
                        repo.showQuotes = it
                    }
                )
            }

            if (showQuotesState) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(strings.quotesManageTitle, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newQuoteInput,
                        onValueChange = { newQuoteInput = it },
                        placeholder = {
                            Text(strings.addQuotePlaceholder, color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val trimmed = newQuoteInput.trim()
                            if (trimmed.isNotEmpty()) {
                                quotesList.add(trimmed)
                                repo.saveQuotes(quotesList)
                                if (currentQuote.isEmpty()) currentQuote = trimmed
                                newQuoteInput = ""
                            }
                        }
                    ) {
                        Text(strings.addBtn, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    quotesList.forEachIndexed { idx, q ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• $q",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "✕",
                                color = TierRed,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clickable {
                                        quotesList.removeAt(idx)
                                        repo.saveQuotes(quotesList)
                                        pickRandomQuote()
                                    }
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // WIDGET HOST TOGGLE & PICKER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    strings.showWidgetLabel,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showWidgetState,
                    onCheckedChange = {
                        showWidgetState = it
                        repo.showWidget = it
                    }
                )
            }

            if (showWidgetState) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launchWidgetPicker() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings.selectWidgetBtn, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (widgetIdState != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        OutlinedButton(
                            onClick = { removeCurrentWidget() }
                        ) {
                            Text(strings.removeWidgetBtn, color = TierRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(14.dp))

            // GATED SLIDERS: PRE-SESSION BREATHING DELAY (3-10s) & RED COOLDOWN (5-60m)
            Text(
                text = strings.preSessionDelayLabel(preSessionDelayState),
                color = if (isUnlocked) TextPrimary else TextMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Slider(
                value = preSessionDelayState.toFloat(),
                onValueChange = {
                    val v = it.roundToInt().coerceIn(3, 10)
                    preSessionDelayState = v
                    repo.preSessionDelaySec = v
                },
                valueRange = 3f..10f,
                steps = 6,
                enabled = isUnlocked
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.redCooldownLabel(redCooldownState),
                color = if (isUnlocked) TextPrimary else TextMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Slider(
                value = redCooldownState.toFloat(),
                onValueChange = {
                    val v = it.roundToInt().coerceIn(5, 60)
                    redCooldownState = v
                    repo.redCooldownMin = v
                },
                valueRange = 5f..60f,
                enabled = isUnlocked
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(14.dp))

            // GATED APP TIERS (GREEN, YELLOW, RED) & OPTIONAL RED DAILY QUOTA (0-120m)
            Text(
                text = strings.appManagementTitle,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))

            installedApps.forEachIndexed { index, app ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = app.label,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TierSelectorBadge(
                                label = "G",
                                active = app.tier == AppTier.GREEN,
                                color = TierGreen,
                                enabled = isUnlocked,
                                onClick = {
                                    repo.setAppTier(app.packageName, AppTier.GREEN)
                                    installedApps[index] = app.copy(tier = AppTier.GREEN)
                                }
                            )
                            TierSelectorBadge(
                                label = "Y",
                                active = app.tier == AppTier.YELLOW,
                                color = TierYellow,
                                enabled = isUnlocked,
                                onClick = {
                                    repo.setAppTier(app.packageName, AppTier.YELLOW)
                                    installedApps[index] = app.copy(tier = AppTier.YELLOW)
                                }
                            )
                            TierSelectorBadge(
                                label = "R",
                                active = app.tier == AppTier.RED,
                                color = TierRed,
                                enabled = isUnlocked,
                                onClick = {
                                    repo.setAppTier(app.packageName, AppTier.RED)
                                    installedApps[index] = app.copy(tier = AppTier.RED)
                                }
                            )
                        }
                    }

                    // Red Tier Optional Daily Quota Slider (0-120m)
                    if (app.tier == AppTier.RED) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val used = appDailyUsedMinutes[app.packageName] ?: 0
                        Text(
                            text = "${strings.dailyQuotaSliderLabel(app.dailyQuotaMinutes)} (Used: ${used}m)",
                            color = if (isUnlocked) TextSecondary else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Slider(
                            value = app.dailyQuotaMinutes.toFloat(),
                            onValueChange = { newVal ->
                                val q = (newVal.roundToInt() / 5) * 5
                                repo.setDailyQuota(app.packageName, q)
                                installedApps[index] = app.copy(dailyQuotaMinutes = q)
                            },
                            valueRange = 0f..120f,
                            enabled = isUnlocked,
                            colors = SliderDefaults.colors(
                                thumbColor = TierRed,
                                activeTrackColor = TierRed
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TierSelectorBadge(
        label: String,
        active: Boolean,
        color: Color,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) color else BgBlack)
                .border(1.dp, if (active) color else BorderSubtle, RoundedCornerShape(4.dp))
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (active) BgBlack else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
