package com.example.bardquotahook

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import dalvik.system.BaseDexClassLoader
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Clean, Dynamic, Zero-Background-Service Gemini Quota Engine (`QuotaCoreEngine` — v2.1.0).
 *
 * Architectural Guarantees:
 * 1. Dynamic Outgoing Session & Cookie/Auth Interception (Zero Hardcoded Tokens):
 *    - Dynamically intercepts active `Authorization`, `Cookie`, and `X-Goog-AuthUser` headers
 *      from Gemini's outgoing Cronet (`UrlRequest.Builder` / `BidirectionalStream.Builder`),
 *      gRPC (`io.grpc.Metadata` / `ClientCall`), and OkHttp (`Request.Builder`) requests on the fly.
 *    - Computes a stable per-account session fingerprint (`sess_<12hex>`) from stable session
 *      cookies (`__Secure-1PSID`, `__Secure-3PSID`, `SAPISID`, `SID`), `X-Goog-AuthUser`, or
 *      OAuth2 `Bearer` credentials—ignoring rotating timestamp cookies (`__Secure-1PSIDTS`) and
 *      never logging or persisting raw credentials to disk.
 *    - Seamlessly switches isolated per-account quota snapshots and clears stale values the
 *      instant the user switches Google accounts inside Gemini.
 *
 * 2. Zero-Background In-App Lifecycle:
 *    - Zero background services, zero foreground services, and zero periodic polling loops.
 *    - `QuotaHudView` only initializes and attaches when a Gemini `Activity` enters the foreground
 *      (`enterForeground`), and detaches + sleeps completely (`enterBackgroundSleep`) when Gemini
 *      goes to the background (`startedActivityCount == 0` or `TRIM_MEMORY_UI_HIDDEN`).
 *    - Uses a single on-demand `ThreadPoolExecutor` with `allowCoreThreadTimeOut(true)` (`5s` keep-alive)
 *      so worker threads spin down to **0** whenever idle or backgrounded.
 *    - Triggers lightweight quota refreshes strictly on `APP_OPEN`, `QUERY_SENT`, `ACCOUNT_SWITCH`,
 *      or `MANUAL_TAP`.
 */
object QuotaCoreEngine {

    interface HookBridge {
        val bridgeName: String
        val isModernApi102: Boolean get() = false
        fun log(msg: String)
        fun openRemotePreferences(group: String): SharedPreferences? = null

        fun hookExecutable(
            executable: Executable,
            before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
            after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
        ): (() -> Unit)?

        fun hookAllMethodsByName(
            clazz: Class<*>?,
            methodName: String,
            before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
            after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
        ): Int {
            if (clazz == null) return 0
            var count = 0
            try {
                for (m in clazz.declaredMethods) {
                    if (m.name == methodName && !Modifier.isAbstract(m.modifiers)) {
                        try {
                            m.isAccessible = true
                            if (hookExecutable(m, before, after) != null) count++
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return count
        }

        fun hookAllConstructors(
            clazz: Class<*>?,
            before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
            after: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null
        ): Int {
            if (clazz == null) return 0
            var count = 0
            try {
                for (c in clazz.declaredConstructors) {
                    try {
                        c.isAccessible = true
                        val hooked = hookExecutable(
                            executable = c,
                            before = before,
                            after = if (after != null) {
                                { thisObj, args, _ -> after(thisObj, args) }
                            } else {
                                null
                            }
                        )
                        if (hooked != null) count++
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            return count
        }
    }

    enum class RefreshReason(val tag: String) {
        APP_OPEN("app-open"),
        QUERY_SENT("query-sent"),
        ACCOUNT_SWITCH("account-switch"),
        MANUAL_TAP("manual-tap")
    }

    private const val LOG_TAG = "BardQuotaHook"
    private const val DEFAULT_SESSION_TAG = "sess_default"
    private const val MAX_STREAM_ACCUM_BYTES = 65_536
    private const val MAX_CHUNK_COPY_BYTES = 32_768
    private const val MAX_TRACKED_ACCOUNTS = 8

    val TARGET_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox"
    )

    // Stable Google session cookie names (deliberately excludes rotating __Secure-1PSIDTS / _ga)
    private val STABLE_COOKIE_KEYS = arrayOf(
        "__Secure-1PSID",
        "__Secure-3PSID",
        "SAPISID",
        "__Secure-3PAPISID",
        "SID",
        "LSID"
    )

    // Lifecycle & installation guards
    private val lifecycleInstalledGuard = AtomicBoolean(false)
    private val classLoaderWatcherInstalled = AtomicBoolean(false)
    private val callbacksRegistered = AtomicBoolean(false)
    private val observerRegistered = AtomicBoolean(false)
    private val dexRepoScanCompleted = AtomicBoolean(false)

    // Zero-Background Foreground Gate: false until a Gemini Activity enters foreground;
    // immediately returns to false when all Gemini Activities stop or UI is hidden.
    private val isForegroundActive = AtomicBoolean(false)
    private val startedActivityCount = AtomicInteger(0)
    private val activeStartedActivities = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    )

    private enum class IdentityStrength(val rank: Int) {
        NONE(0),
        BEARER_ONLY(1),
        INTENT(2),
        AUTH_USER(3),
        COOKIE_SID(4)
    }

    private data class LearnedEndpoint(
        val url: String,
        val method: String = "GET",
        val rpcHint: String? = null,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    // Dynamic Session & UI State
    private val activeSessionTag = AtomicReference(DEFAULT_SESSION_TAG)
    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())
    private val currentLang = AtomicReference(QuotaHudView.Lang.ZH)
    private val currentTheme = AtomicReference(QuotaHudView.ThemeMode.MIUIX)

    // Strong account-switch tracking (never churns across different OAuth scopes on the same account)
    @Volatile private var confirmedIdentityKey: String? = null
    @Volatile private var confirmedIdentityStrength: IdentityStrength = IdentityStrength.NONE
    @Volatile private var persistedSessionWasStrong: Boolean = false
    @Volatile private var cachedXsrfToken: String? = null
    @Volatile private var cachedFsidToken: String? = null
    @Volatile private var cachedBuildLabel: String? = null

    // Bounded LRU map of per-account quota snapshots so switching Google accounts A <-> B is instant
    private val sessionSnapshotsLock = Any()
    private val perSessionSnapshots = object : LinkedHashMap<String, QuotaHudView.Snapshot>(MAX_TRACKED_ACCOUNTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QuotaHudView.Snapshot>?): Boolean {
            return size > MAX_TRACKED_ACCOUNTS
        }
    }

    // Bounded in-memory learned quota endpoints discovered during foreground inspection
    private val learnedEndpointsLock = Any()
    private val learnedQuotaEndpoints = object : LinkedHashMap<String, LearnedEndpoint>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LearnedEndpoint>?): Boolean {
            return size > 6
        }
    }

    // Ephemeral foreground-only outgoing request session header contexts (cleared on background sleep)
    private data class EphemeralSessionHeaders(
        var url: String? = null,
        var method: String? = null,
        var authorization: String? = null,
        var cookie: String? = null,
        var authUser: String? = null,
        var pageId: String? = null
    ) {
        val hasCredentials: Boolean
            get() = !authorization.isNullOrBlank() || !cookie.isNullOrBlank() || !authUser.isNullOrBlank()
    }

    private data class BoundRequestContext(
        val urlOrMethod: String?,
        val httpMethod: String?,
        val sessionTag: String,
        val isQueryCandidate: Boolean,
        val isQuotaEndpointCandidate: Boolean,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    // Active dynamic session header state (ephemeral in-memory only while foregrounded; wiped on sleep)
    private data class ActiveDynamicSessionState(
        val sessionTag: String = DEFAULT_SESSION_TAG,
        val authorizationHeader: String? = null,
        val cookieHeader: String? = null,
        val authUserHeader: String? = null,
        val transport: String = "init",
        val updatedAtMs: Long = 0L
    )

    private val activeDynamicSession = AtomicReference(ActiveDynamicSessionState())

    private val builderHeaderMap = Collections.synchronizedMap(WeakHashMap<Any, EphemeralSessionHeaders>())
    private val requestContextMap = ConcurrentHashMap<Int, BoundRequestContext>()
    private val cronetRollingStreams = ConcurrentHashMap<Int, RollingStreamBuffer>()

    private val activeHudRef = AtomicReference<WeakReference<QuotaHudView>>(WeakReference(null))
    private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
    private val activeBridgeRef = AtomicReference<HookBridge?>(null)

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val liveViewModels = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val liveRepositories = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )

    private val hookedDynamicClasses = ConcurrentHashMap.newKeySet<String>()
    private val classCandidateCache = ConcurrentHashMap<Class<*>, Boolean>()

    private val lastRefreshMs = AtomicLong(0L)
    private val lastQueryEventMs = AtomicLong(0L)
    private val lastHttpFetchMs = AtomicLong(0L)
    private val rollingQueryTimestamps = Collections.synchronizedList(ArrayList<Long>(64))

    private fun recordUserQueryInRollingWindow(now: Long) {
        try {
            synchronized(rollingQueryTimestamps) {
                val cutoff = now - 5L * 3600L * 1000L
                val it = rollingQueryTimestamps.iterator()
                while (it.hasNext()) {
                    if (it.next() < cutoff) it.remove()
                }
                rollingQueryTimestamps.add(now)
            }
        } catch (_: Throwable) {
        }
    }

    // Single on-demand foreground executor: allowCoreThreadTimeOut(true) guarantees 0 threads when idle/backgrounded
    private val onDemandExecutor = ThreadPoolExecutor(
        1,
        2,
        5L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(32),
        { r ->
            Thread(r, "bard-quota-fg").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var mainHandler: Handler? = null
    @Volatile private var providerObserver: ContentObserver? = null
    @Volatile private var hostPackageName: String = ""
    @Volatile private var hostProcessName: String = ""
    @Volatile private var activeBridgeName: String = ""
    @Volatile private var isModernApi102Bridge: Boolean = false

    private class RollingStreamBuffer(
        val urlHint: String?,
        val sessionTag: String
    ) {
        private val out = ByteArrayOutputStream(4096)
        val createdAtMs: Long = System.currentTimeMillis()

        @Synchronized
        fun appendAndSnapshot(chunk: ByteArray, len: Int): ByteArray? {
            if (len <= 0) return null
            if (out.size() + len > MAX_STREAM_ACCUM_BYTES) {
                val existing = out.toByteArray()
                out.reset()
                val keep = minOf(existing.size, 8192)
                if (keep > 0) {
                    out.write(existing, existing.size - keep, keep)
                }
            }
            val writeLen = minOf(len, MAX_STREAM_ACCUM_BYTES - out.size())
            if (writeLen > 0) {
                out.write(chunk, 0, writeLen)
            }
            return if (out.size() >= 6) out.toByteArray() else null
        }
    }

    private data class StructuralWindow(
        val kind: QuotaTextParser.MetricKind,
        val metric: String,
        val reset: String,
        val confidence: Int
    )

    private val inHookGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private inline fun withReentrancyGuard(block: () -> Unit) {
        if (inHookGuard.get() == true) return
        inHookGuard.set(true)
        try {
            block()
        } catch (_: Throwable) {
        } finally {
            inHookGuard.set(false)
        }
    }

    fun isTargetHostPackage(packageName: String): Boolean {
        return packageName in TARGET_PACKAGES ||
            packageName.contains("bard", ignoreCase = true) ||
            packageName.contains("gemini", ignoreCase = true)
    }

    /**
     * Zero-Background Process Filter:
     * Rejects non-UI background daemons (`:interactor` hotword service, `:crash_report`,
     * `:primes_lifeboat`, `:background`, `:gms`, `:sandboxed_process`) so background processes
     * are never instrumented or kept awake.
     */
    fun isEligibleUiProcess(packageName: String, processName: String): Boolean {
        if (!isTargetHostPackage(packageName)) return false
        val proc = processName.lowercase()
        val blockedSuffixes = arrayOf(
            ":interactor",
            ":crash_report",
            ":primes_lifeboat",
            ":leakcanary",
            ":background",
            ":gms",
            ":pheno",
            ":download",
            ":sandboxed_process"
        )
        if (blockedSuffixes.any { proc.contains(it) }) return false
        if (packageName == "com.google.android.googlequicksearchbox") {
            return proc == packageName || proc.endsWith(":search")
        }
        return true
    }

    private fun safeLog(bridge: HookBridge?, msg: String) {
        try {
            bridge?.log("[BardQuota] $msg")
        } catch (_: Throwable) {
        }
        try {
            Log.i(LOG_TAG, msg)
        } catch (_: Throwable) {
        }
    }

    /**
     * Convenience entrypoint installing the complete Zero-Background Dynamic Session architecture.
     */
    fun install(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        if (!isEligibleUiProcess(packageName, processName)) return
        installForegroundHudLifecycle(packageName, processName, classLoader, bridge)
        installDynamicSessionAndNetworkHooks(classLoader, bridge)
        installForegroundMemoryHooks(classLoader, bridge)
    }

    // Legacy compatibility aliases delegating to the v2.1.0 zero-background stages
    fun installInAppHudAndExclusivity(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) = installForegroundHudLifecycle(packageName, processName, classLoader, bridge)

    fun installNativeGrpcAndCronetInterceptors(
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) = installDynamicSessionAndNetworkHooks(classLoader, bridge)

    fun installMemoryAndViewModelHooks(
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) = installForegroundMemoryHooks(classLoader, bridge)

    // =========================================================================
    // Reflection & Task Submission Helpers (100% try-catch Throwable)
    // =========================================================================

    private fun submitForegroundWork(block: () -> Unit) {
        if (!isForegroundActive.get()) return
        try {
            onDemandExecutor.execute {
                if (!isForegroundActive.get()) return@execute
                withReentrancyGuard(block)
            }
        } catch (_: Throwable) {
        }
    }

    private fun findClassSafe(name: String, classLoader: ClassLoader?): Class<*>? {
        if (name.isBlank()) return null
        return try {
            if (classLoader != null) {
                Class.forName(name, false, classLoader)
            } else {
                Class.forName(name)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookMethodSafe(
        bridge: HookBridge,
        clazz: Class<*>?,
        name: String,
        paramTypes: Array<Class<*>>,
        before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
        after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
    ) {
        if (clazz == null) return
        try {
            val method = clazz.getDeclaredMethod(name, *paramTypes)
            method.isAccessible = true
            bridge.hookExecutable(method, before, after)
        } catch (_: Throwable) {
        }
    }

    private fun hookConstructorSafe(
        bridge: HookBridge,
        clazz: Class<*>?,
        paramTypes: Array<Class<*>>,
        after: (thisObj: Any?, args: Array<Any?>) -> Unit
    ) {
        if (clazz == null) return
        try {
            val ctor: Constructor<*> = clazz.getDeclaredConstructor(*paramTypes)
            ctor.isAccessible = true
            bridge.hookExecutable(
                executable = ctor,
                before = null,
                after = { thisObj, args, _ -> after(thisObj, args) }
            )
        } catch (_: Throwable) {
        }
    }

    private fun markDynamicHookOnce(category: String, clazz: Class<*>): Boolean {
        val loaderId = try {
            System.identityHashCode(clazz.classLoader)
        } catch (_: Throwable) {
            0
        }
        return hookedDynamicClasses.add("$category:$loaderId:${clazz.name}")
    }

    // =========================================================================
    // Stage 1: Zero-Background Foreground HUD Lifecycle
    // =========================================================================

    fun installForegroundHudLifecycle(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        if (!isEligibleUiProcess(packageName, processName)) return
        activeBridgeRef.set(bridge)
        hostPackageName = packageName
        hostProcessName = processName
        activeBridgeName = bridge.bridgeName
        if (bridge.isModernApi102) {
            isModernApi102Bridge = true
        }

        if (!lifecycleInstalledGuard.compareAndSet(false, true)) return

        // Register Application lifecycle + UI-hidden memory trim callbacks when Application initializes,
        // WITHOUT waking any threads, observers, or HUD views while in the background.
        hookMethodSafe(
            bridge = bridge,
            clazz = Application::class.java,
            name = "onCreate",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val app = thisObj as? Application ?: return@cb
                if (appContext == null) {
                    appContext = app
                }
                registerZeroBackgroundCallbacksOnce(app)
                bindHooksForClassLoader(app.classLoader, bridge)
            }
        )

        // Pre-arm foreground gate in Activity.onCreate so initial startup ViewModels & RPCs
        // fired between onCreate and onResume are captured seamlessly on cold app launch.
        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onCreate",
            paramTypes = arrayOf(Bundle::class.java),
            before = cb@{ thisObj, _ ->
                val activity = thisObj as? Activity ?: return@cb
                val app = activity.application
                if (app != null) registerZeroBackgroundCallbacksOnce(app)
                if (shouldDisplayHudInActivity(activity)) {
                    isForegroundActive.set(true)
                    inspectActivityAccountIntent(activity)
                    bindHooksForClassLoader(activity.classLoader, bridge)
                }
            }
        )

        // Ensure Activity.onStart also records active started activities even if Application callbacks were registered late
        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onStart",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val activity = thisObj as? Activity ?: return@cb
                val app = activity.application
                if (app != null) registerZeroBackgroundCallbacksOnce(app)
                if (shouldDisplayHudInActivity(activity)) {
                    activeStartedActivities.add(activity)
                    startedActivityCount.set(activeStartedActivities.size)
                    enterForeground(activity, attachHudNow = true)
                }
            }
        )

        // Fallback direct Activity hooks in case Application.onCreate already ran prior to module load
        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onResume",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val activity = thisObj as? Activity ?: return@cb
                val app = activity.application
                if (app != null) registerZeroBackgroundCallbacksOnce(app)
                if (shouldDisplayHudInActivity(activity)) {
                    activeStartedActivities.add(activity)
                    startedActivityCount.set(activeStartedActivities.size)
                    enterForeground(activity, attachHudNow = true)
                }
            }
        )

        // Re-assert HUD Z-order & scan screen banners whenever an in-app bottom sheet/dialog opens
        hookMethodSafe(
            bridge = bridge,
            clazz = Dialog::class.java,
            name = "show",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                if (!isForegroundActive.get()) return@cb
                val dlg = thisObj as? Dialog ?: return@cb
                val sess = activeSessionTag.get()
                postToMain(delayMs = 350L, requireForeground = true) {
                    val decor = dlg.window?.decorView
                    if (decor != null) {
                        val sb = StringBuilder()
                        collectViewTexts(decor, sb, 0)
                        if (sb.isNotEmpty()) {
                            val text = sb.toString()
                            if (QuotaTextParser.fastHasQuotaKeyword(text)) {
                                submitForegroundWork {
                                    val snap = QuotaTextParser.parseScreenQuotaTexts(
                                        text = text,
                                        prev = getBaseSnapshotForSession(sess),
                                        source = "InApp-Dialog"
                                    )
                                    if (snap != null) publishSnapshotForSession(snap, sess)
                                }
                            }
                        }
                    }
                    scanForExhaustedBannerOnly(sess)
                    refreshHudOnUiThread()
                }
            }
        )

        // Hook in-app WebView if Gemini opens its quota/subscription details inside a WebView
        installWebViewQuotaHooks(bridge)

        if (classLoader != null) {
            bindHooksForClassLoader(classLoader, bridge)
        }
    }

    private fun registerZeroBackgroundCallbacksOnce(app: Application) {
        if (!callbacksRegistered.compareAndSet(false, true)) return
        if (appContext == null) appContext = app

        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (shouldDisplayHudInActivity(activity)) {
                        isForegroundActive.set(true)
                        inspectActivityAccountIntent(activity)
                    }
                }

                override fun onActivityStarted(activity: Activity) {
                    if (shouldDisplayHudInActivity(activity)) {
                        activeStartedActivities.add(activity)
                        startedActivityCount.set(activeStartedActivities.size)
                        enterForeground(activity, attachHudNow = true)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    if (shouldDisplayHudInActivity(activity)) {
                        activeStartedActivities.add(activity)
                        startedActivityCount.set(activeStartedActivities.size)
                        enterForeground(activity, attachHudNow = true)
                    }
                }

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {
                    if (shouldDisplayHudInActivity(activity)) {
                        if (activity.isChangingConfigurations) return
                        activeStartedActivities.remove(activity)
                        val remaining = activeStartedActivities.size
                        startedActivityCount.set(remaining)
                        if (remaining <= 0) {
                            startedActivityCount.set(0)
                            enterBackgroundSleep(stoppedActivity = activity)
                        }
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    activeStartedActivities.remove(activity)
                    startedActivityCount.set(activeStartedActivities.size)
                    val cur = activeActivityRef.get().get()
                    if (cur === activity) {
                        detachHudFromActivity(activity)
                        activeActivityRef.set(WeakReference(null))
                    }
                }
            })

            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() {
                    clearTransientBuffers()
                }
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && activeStartedActivities.isEmpty()) {
                        startedActivityCount.set(0)
                        enterBackgroundSleep(stoppedActivity = activeActivityRef.get().get())
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun shouldDisplayHudInActivity(activity: Activity): Boolean {
        if (hostPackageName == "com.google.android.apps.bard") return true
        val clsName = activity.javaClass.name.lowercase()
        if (clsName.contains("bard") ||
            clsName.contains("robin") ||
            clsName.contains("assistant") ||
            clsName.contains("gemini")
        ) {
            return true
        }
        val intentStr = try {
            activity.intent?.toString()?.lowercase() ?: ""
        } catch (_: Throwable) {
            ""
        }
        return intentStr.contains("bard") ||
            intentStr.contains("gemini") ||
            intentStr.contains("robin")
    }

    /**
     * Wakes the module ONLY when a Gemini `Activity` enters the foreground:
     * - Restores preferences & active session snapshot
     * - Registers foreground-only `ContentObserver`
     * - Attaches `QuotaHudView`
     * - Triggers a single lightweight `APP_OPEN` quota refresh
     */
    private fun enterForeground(activity: Activity, attachHudNow: Boolean) {
        val ctx = activity.applicationContext ?: activity
        if (appContext == null) {
            appContext = ctx
            loadSanitizedHostMirror(ctx)
        }

        val wasSleeping = !isForegroundActive.getAndSet(true)
        val prevActivity = activeActivityRef.get().get()
        activeActivityRef.set(WeakReference(activity))

        // Check if Activity intent carries an explicit switched Google account identity
        inspectActivityAccountIntent(activity)

        // Register preference observer strictly while foregrounded
        registerForegroundObserver(ctx)

        if (attachHudNow) {
            attachOrUpdateOverlay(activity)
        }

        if (wasSleeping || prevActivity !== activity) {
            safeLog(
                activeBridgeRef.get(),
                "Foreground wake in ${activity.javaClass.simpleName} [session=${activeSessionTag.get()}]"
            )
            syncForegroundStateWithProviderAsync(ctx)
            triggerLightweightQuotaRefresh(RefreshReason.APP_OPEN, delayMs = 350L)
            // Secondary cold-launch check: once cookies / ViewModels settle (~1.8s), verify if still empty
            postToMain(delayMs = 1_850L, requireForeground = true) {
                val cur = latestSnapshot.get().sanitized()
                if (!cur.hasLiveData || cur.source == "SessionEstimate") {
                    lastHttpFetchMs.set(0L)
                    triggerLightweightQuotaRefresh(RefreshReason.APP_OPEN, delayMs = 0L)
                } else {
                    refreshHudOnUiThread()
                }
            }
        }
    }

    /**
     * Puts the HUD and interception engine completely to sleep when Gemini leaves the foreground:
     * - Flips `isForegroundActive = false` (silences all hook callbacks in 1ns)
     * - Detaches `QuotaHudView` from the window
     * - Unregisters `ContentObserver` (zero background Binder wakeups)
     * - Cancels pending Handler tasks, wipes ephemeral credentials/buffers, and drains worker queue
     */
    private fun enterBackgroundSleep(stoppedActivity: Activity?) {
        val wasActive = isForegroundActive.getAndSet(false)
        if (!wasActive && activeHudRef.get().get() == null) return

        try {
            mainHandler?.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {
        }

        val ctx = appContext ?: stoppedActivity?.applicationContext
        if (ctx != null) {
            unregisterForegroundObserver(ctx)
        }

        val actToClean = stoppedActivity ?: activeActivityRef.get().get()
        if (actToClean != null) {
            detachHudFromActivity(actToClean)
        }
        activeHudRef.set(WeakReference(null))
        activeActivityRef.set(WeakReference(null))

        clearTransientBuffers()
        safeLog(activeBridgeRef.get(), "Entered zero-background sleep (0 active threads / HUD detached)")
    }

    fun forceSleepForHotReload() {
        activeStartedActivities.clear()
        startedActivityCount.set(0)
        enterBackgroundSleep(activeActivityRef.get().get())
    }

    private fun clearTransientBuffers() {
        try {
            cronetRollingStreams.clear()
            requestContextMap.clear()
            synchronized(builderHeaderMap) {
                builderHeaderMap.clear()
            }
            cachedXsrfToken = null
            cachedFsidToken = null
            cachedBuildLabel = null
            // Wipe raw in-memory header values while keeping only the non-reversible session tag
            val cur = activeDynamicSession.get()
            activeDynamicSession.set(
                ActiveDynamicSessionState(
                    sessionTag = cur.sessionTag,
                    authorizationHeader = null,
                    cookieHeader = null,
                    authUserHeader = null,
                    transport = cur.transport,
                    updatedAtMs = cur.updatedAtMs
                )
            )
            onDemandExecutor.queue.clear()
            onDemandExecutor.purge()
        } catch (_: Throwable) {
        }
    }

    private fun detachHudFromActivity(activity: Activity) {
        postToMain(requireForeground = false) {
            try {
                val decorGroup = activity.window?.decorView as? ViewGroup ?: return@postToMain
                val existing = decorGroup.findViewWithTag<View>(QuotaHudView.HUD_VIEW_TAG)
                if (existing != null) {
                    existing.visibility = View.GONE
                    decorGroup.removeView(existing)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // =========================================================================
    // Dynamic Session & Cookie / Authorization Interception (Account-Switch Safe)
    // =========================================================================

    private fun recordOutgoingBuilderHeader(
        builderObj: Any,
        rawName: String,
        rawValue: String,
        transport: String = "Cronet"
    ) {
        if (!isForegroundActive.get()) return
        val lower = rawName.trim().lowercase()
        val isRelevant = lower == "authorization" ||
            lower == "cookie" ||
            lower == "x-goog-authuser" ||
            lower == "x-goog-pageid"
        if (!isRelevant || rawValue.isBlank()) return

        val snapshotHeaders = synchronized(builderHeaderMap) {
            val entry = builderHeaderMap.getOrPut(builderObj) { EphemeralSessionHeaders() }
            if (isNonQuotaTelemetryUrl(entry.url)) return
            when (lower) {
                "authorization" -> entry.authorization = rawValue.trim()
                "cookie" -> entry.cookie = rawValue.trim()
                "x-goog-authuser" -> entry.authUser = rawValue.trim()
                "x-goog-pageid" -> entry.pageId = rawValue.trim()
            }
            entry.copy()
        }

        if (isNonQuotaTelemetryUrl(snapshotHeaders.url)) return
        onDynamicHeadersIntercepted(snapshotHeaders, transport = transport)
    }

    private data class ComputedIdentity(
        val sessionTag: String,
        val strength: IdentityStrength,
        val canonicalKey: String
    )

    private fun computeIdentityDescriptor(
        authorization: String?,
        cookie: String?,
        authUser: String?,
        pageId: String?
    ): ComputedIdentity? {
        val stableCookieToken = extractStableGoogleSessionCookie(cookie)
        val cleanAuthUser = authUser?.trim()?.takeIf { it.isNotEmpty() }
        val cleanPageId = pageId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        val cleanBearer = authorization?.trim()?.takeIf { it.isNotEmpty() }

        return when {
            stableCookieToken != null -> {
                val key = buildString {
                    append("cookie:").append(stableCookieToken)
                    if (cleanPageId != null) append("|p:").append(cleanPageId)
                }
                ComputedIdentity(sha256SessionTag(key), IdentityStrength.COOKIE_SID, key)
            }
            cleanAuthUser != null -> {
                val key = "authuser:$cleanAuthUser|p:${cleanPageId ?: "0"}"
                ComputedIdentity(sha256SessionTag(key), IdentityStrength.AUTH_USER, key)
            }
            cleanBearer != null -> {
                // OAuth2 Bearer tokens vary per API scope on the SAME Google account and rotate hourly;
                // never treat different Bearer strings on the same account as an account switch!
                val key = "bearer:active"
                ComputedIdentity(sha256SessionTag(key), IdentityStrength.BEARER_ONLY, key)
            }
            else -> null
        }
    }

    private fun onDynamicHeadersIntercepted(
        headers: EphemeralSessionHeaders,
        transport: String
    ): String {
        if (isNonQuotaTelemetryUrl(headers.url)) return activeSessionTag.get()

        val computed = computeIdentityDescriptor(
            authorization = headers.authorization,
            cookie = headers.cookie,
            authUser = headers.authUser,
            pageId = headers.pageId
        ) ?: return activeSessionTag.get()

        val prevDyn = activeDynamicSession.get()
        val now = System.currentTimeMillis()
        val hadNoCredentialsBefore = prevDyn.cookieHeader.isNullOrBlank() && prevDyn.authorizationHeader.isNullOrBlank()
        val gainedNewCookie = prevDyn.cookieHeader.isNullOrBlank() && !headers.cookie.isNullOrBlank()

        val prevStrongKey = confirmedIdentityKey
        val prevStrongStrength = confirmedIdentityStrength

        // Determine if this genuinely represents a different Google account
        val isGenuineAccountSwitch = when {
            computed.strength == IdentityStrength.COOKIE_SID &&
                prevStrongStrength == IdentityStrength.COOKIE_SID &&
                prevStrongKey != null &&
                prevStrongKey != computed.canonicalKey -> true
            computed.strength == IdentityStrength.AUTH_USER &&
                prevStrongStrength == IdentityStrength.AUTH_USER &&
                prevStrongKey != null &&
                prevStrongKey != computed.canonicalKey -> true
            else -> false
        }

        // Preserve existing complementary credentials (e.g. Cookie from Web/Cronet + Bearer from gRPC) on SAME account
        val mergedAuth = headers.authorization ?: if (isGenuineAccountSwitch) null else prevDyn.authorizationHeader
        val mergedCookie = mergeCookieStrings(
            if (isGenuineAccountSwitch) null else prevDyn.cookieHeader,
            headers.cookie
        )
        val mergedAuthUser = headers.authUser ?: if (isGenuineAccountSwitch) null else prevDyn.authUserHeader

        // Decide which sessionTag stays active without churning on weak Bearer/AuthUser emissions
        val targetTag: String = when {
            isGenuineAccountSwitch -> {
                confirmedIdentityKey = computed.canonicalKey
                confirmedIdentityStrength = computed.strength
                computed.sessionTag
            }
            computed.strength == IdentityStrength.COOKIE_SID -> {
                confirmedIdentityKey = computed.canonicalKey
                confirmedIdentityStrength = IdentityStrength.COOKIE_SID
                computed.sessionTag
            }
            prevStrongStrength >= IdentityStrength.INTENT -> {
                // Keep confirmed Cookie / Intent account tag even when per-scope Bearer or AuthUser arrives
                activeSessionTag.get()
            }
            persistedSessionWasStrong && activeSessionTag.get() != DEFAULT_SESSION_TAG -> {
                // Keep yesterday's confirmed session tag until a COOKIE_SID confirms whether it matches or switched
                activeSessionTag.get()
            }
            computed.strength >= prevStrongStrength -> {
                if (computed.strength >= IdentityStrength.AUTH_USER) {
                    confirmedIdentityKey = computed.canonicalKey
                    confirmedIdentityStrength = computed.strength
                }
                computed.sessionTag
            }
            else -> activeSessionTag.get()
        }

        activeDynamicSession.set(
            ActiveDynamicSessionState(
                sessionTag = targetTag,
                authorizationHeader = mergedAuth,
                cookieHeader = mergedCookie,
                authUserHeader = mergedAuthUser,
                transport = transport,
                updatedAtMs = now
            )
        )

        val tagChanged = targetTag != activeSessionTag.get()
        if (tagChanged) {
            activateSessionTag(
                newTag = targetTag,
                sourceReason = "$transport-Header",
                preserveUnconfirmedSnapshot = !isGenuineAccountSwitch &&
                    (!persistedSessionWasStrong || prevStrongStrength < IdentityStrength.COOKIE_SID)
            )
        } else if ((hadNoCredentialsBefore || gainedNewCookie) && isForegroundActive.get()) {
            val curSnap = latestSnapshot.get().sanitized()
            if (!curSnap.hasLiveData || curSnap.source == "SessionEstimate") {
                lastHttpFetchMs.set(0L)
                triggerLightweightQuotaRefresh(RefreshReason.APP_OPEN, delayMs = 150L)
            }
        }
        return targetTag
    }

    private fun extractStableGoogleSessionCookie(rawCookieHeader: String?): String? {
        if (rawCookieHeader.isNullOrBlank()) return null
        val cookiePairs = HashMap<String, String>(8)
        for (segment in rawCookieHeader.split(';')) {
            val eqIdx = segment.indexOf('=')
            if (eqIdx <= 0) continue
            val k = segment.substring(0, eqIdx).trim()
            val v = segment.substring(eqIdx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                cookiePairs[k] = v
            }
        }
        val sb = StringBuilder()
        for (stableKey in STABLE_COOKIE_KEYS) {
            val v = cookiePairs[stableKey]
            if (!v.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append('&')
                sb.append(stableKey).append('=').append(v)
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun sha256SessionTag(material: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder("sess_")
            for (i in 0 until 6) {
                val b = digest[i].toInt() and 0xFF
                if (b < 0x10) sb.append('0')
                sb.append(Integer.toHexString(b))
            }
            sb.toString()
        } catch (_: Throwable) {
            "sess_${Integer.toHexString(material.hashCode())}"
        }
    }

    private fun inspectActivityAccountIntent(activity: Activity) {
        try {
            val extras = activity.intent?.extras ?: return
            val candidateKeys = arrayOf(
                "authAccount",
                "account_name",
                "tiktok_account_id",
                "account_id",
                "selected_account_id"
            )
            for (key in candidateKeys) {
                if (!extras.containsKey(key)) continue
                @Suppress("DEPRECATION")
                val raw = extras.get(key)?.toString()?.trim()
                if (!raw.isNullOrEmpty() && raw != "-1") {
                    val intentCanon = "intent_acct:$key:$raw"
                    val intentTag = sha256SessionTag(intentCanon)
                    val prevKey = confirmedIdentityKey
                    val isDifferentConfirmedIntent = confirmedIdentityStrength == IdentityStrength.INTENT &&
                        prevKey != null &&
                        prevKey != intentCanon

                    if (confirmedIdentityStrength < IdentityStrength.COOKIE_SID &&
                        (confirmedIdentityStrength < IdentityStrength.INTENT || isDifferentConfirmedIntent)
                    ) {
                        confirmedIdentityKey = intentCanon
                        confirmedIdentityStrength = IdentityStrength.INTENT
                        val curDyn = activeDynamicSession.get()
                        activeDynamicSession.set(
                            curDyn.copy(
                                sessionTag = intentTag,
                                transport = "ActivityIntent",
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                        activateSessionTag(
                            newTag = intentTag,
                            sourceReason = "ActivityAccount",
                            preserveUnconfirmedSnapshot = !isDifferentConfirmedIntent
                        )
                    }
                    break
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Switches the active session cleanly whenever a new Google account session is detected:
     * 1. Archives outgoing account's `Snapshot` under `prevTag`.
     * 2. Restores incoming account's `Snapshot` (if cached in memory) OR preserves live startup
     *    data when promoting from an unconfirmed startup slot, OR resets cleanly to `--` ONLY
     *    when switching between two confirmed distinct Google accounts.
     * 3. Updates `QuotaHudView` & `QuotaSyncProvider` and triggers a foreground refresh.
     */
    private fun activateSessionTag(
        newTag: String,
        sourceReason: String,
        preserveUnconfirmedSnapshot: Boolean = false
    ) {
        if (newTag.isBlank() || newTag == DEFAULT_SESSION_TAG) return
        val prevTag = activeSessionTag.get()
        if (prevTag == newTag) return

        var isGenuineAccountSwap = false
        synchronized(sessionSnapshotsLock) {
            val curPrev = activeSessionTag.get()
            if (curPrev == newTag) return

            val prevSnap = latestSnapshot.get().sanitized()
            if (curPrev != DEFAULT_SESSION_TAG && prevSnap.hasLiveData) {
                perSessionSnapshots[curPrev] = prevSnap
            }

            activeSessionTag.set(newTag)
            if (confirmedIdentityStrength >= IdentityStrength.COOKIE_SID) {
                persistedSessionWasStrong = true
            }

            val cachedForNew = perSessionSnapshots[newTag]
            val nextSnap = when {
                cachedForNew != null -> cachedForNew
                (curPrev == DEFAULT_SESSION_TAG || preserveUnconfirmedSnapshot) && prevSnap.hasLiveData -> {
                    perSessionSnapshots[newTag] = prevSnap
                    prevSnap
                }
                else -> {
                    isGenuineAccountSwap = true
                    QuotaHudView.Snapshot(source = "AccountSwitch")
                }
            }

            if (isGenuineAccountSwap) {
                cachedXsrfToken = null
                cachedFsidToken = null
                cachedBuildLabel = null
                synchronized(learnedEndpointsLock) {
                    learnedQuotaEndpoints.clear()
                }
            }

            latestSnapshot.set(nextSnap)
            saveSanitizedHostMirror(
                nextSnap,
                currentLang.get(),
                currentTheme.get(),
                newTag,
                isStrongCookieTag = confirmedIdentityStrength >= IdentityStrength.COOKIE_SID
            )
        }

        safeLog(activeBridgeRef.get(), "Dynamic account session active -> $newTag ($sourceReason, swap=$isGenuineAccountSwap)")

        if (isForegroundActive.get()) {
            refreshHudOnUiThread()
            val ctx = appContext
            if (ctx != null) {
                val snapNow = latestSnapshot.get().sanitized()
                submitForegroundWork {
                    val state = QuotaSyncProvider.publishSessionQuota(
                        context = ctx,
                        snapshot = snapNow,
                        sessionId = newTag,
                        replaceSession = isGenuineAccountSwap
                    )
                    applyUnifiedState(state)
                }
            }
            if (isGenuineAccountSwap || !latestSnapshot.get().hasLiveData) {
                lastHttpFetchMs.set(0L)
                triggerLightweightQuotaRefresh(RefreshReason.ACCOUNT_SWITCH, delayMs = 250L)
            }
        }
    }

    /**
     * Fired when the user actually sends a query over Cronet or gRPC while Gemini is foregrounded.
     * Triggers a single debounced post-query refresh (zero polling loops).
     */
    private fun onUserQueryDispatched(transport: String) {
        if (!isForegroundActive.get()) return
        val now = System.currentTimeMillis()
        recordUserQueryInRollingWindow(now)
        if (now - lastQueryEventMs.get() < 2_000L) return
        lastQueryEventMs.set(now)

        triggerLightweightQuotaRefresh(RefreshReason.QUERY_SENT, delayMs = 1_100L)
    }

    // =========================================================================
    // Stage 2: Dynamic Network Header + ProtoBuf Stream Interception
    // =========================================================================

    fun installDynamicSessionAndNetworkHooks(
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        activeBridgeRef.set(bridge)

        if (classLoaderWatcherInstalled.compareAndSet(false, true)) {
            bridge.hookAllConstructors(
                clazz = BaseDexClassLoader::class.java,
                after = { thisObj, _ ->
                    val dynCl = thisObj as? ClassLoader
                    if (dynCl != null) {
                        bindHooksForClassLoader(dynCl, bridge)
                    }
                }
            )
            hookStructuredJsonPayloads(bridge)
        }

        if (classLoader != null) {
            bindHooksForClassLoader(classLoader, bridge)
        }
    }

    private fun bindHooksForClassLoader(classLoader: ClassLoader?, bridge: HookBridge) {
        if (classLoader == null) return
        val added = try {
            hookedClassLoaders.add(classLoader)
        } catch (_: Throwable) {
            false
        }
        if (!added) return

        installCronetSessionAndStreamHooks(classLoader, bridge)
        installGrpcSessionAndStreamHooks(classLoader, bridge)
        installOkHttpSessionAndBodyHooks(classLoader, bridge)
        installReactiveStateHooksInClassLoader(classLoader, bridge)
    }

    // -------------------------------------------------------------------------
    // Cronet Outgoing Headers (`Authorization`/`Cookie`) + Query & Stream Hooks
    // -------------------------------------------------------------------------

    private fun installCronetSessionAndStreamHooks(cl: ClassLoader, bridge: HookBridge) {
        val engineBuilders = arrayOf(
            "org.chromium.net.CronetEngine\$Builder",
            "org.chromium.net.ExperimentalCronetEngine\$Builder",
            "org.chromium.net.ICronetEngineBuilder"
        )
        for (bName in engineBuilders) {
            val builderCls = findClassSafe(bName, cl) ?: continue
            bridge.hookAllMethodsByName(
                clazz = builderCls,
                methodName = "build",
                after = { _, _, result ->
                    if (result != null) {
                        hookConcreteCronetEngineClass(result.javaClass, bridge)
                    }
                }
            )
        }

        val knownEngines = arrayOf(
            "org.chromium.net.impl.CronetUrlRequestContext",
            "org.chromium.net.impl.JavaCronetEngine",
            "org.chromium.net.CronetEngine",
            "org.chromium.net.ExperimentalCronetEngine"
        )
        for (eName in knownEngines) {
            val engineCls = findClassSafe(eName, cl) ?: continue
            hookConcreteCronetEngineClass(engineCls, bridge)
        }

        val knownReqBuilders = arrayOf(
            "org.chromium.net.UrlRequest\$Builder",
            "org.chromium.net.ExperimentalUrlRequest\$Builder",
            "org.chromium.net.BidirectionalStream\$Builder",
            "org.chromium.net.ExperimentalBidirectionalStream\$Builder",
            "org.chromium.net.impl.UrlRequestBuilderImpl",
            "org.chromium.net.impl.BidirectionalStreamBuilderImpl"
        )
        for (rbName in knownReqBuilders) {
            val rbCls = findClassSafe(rbName, cl) ?: continue
            hookConcreteCronetRequestBuilderClass(rbCls, bridge)
        }

        val knownReqImpls = arrayOf(
            "org.chromium.net.impl.CronetUrlRequest",
            "org.chromium.net.impl.CronetBidirectionalStream",
            "org.chromium.net.impl.JavaUrlRequest"
        )
        for (rName in knownReqImpls) {
            val reqCls = findClassSafe(rName, cl) ?: continue
            hookConcreteCronetRequestInstanceClass(reqCls, bridge)
        }
    }

    private fun hookConcreteCronetEngineClass(engineCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("cronet_engine", engineCls)) return

        val builderFactoryMethods = arrayOf(
            "newUrlRequestBuilder",
            "newBidirectionalStreamBuilder",
            "createRequest",
            "createBidirectionalStream"
        )
        for (mName in builderFactoryMethods) {
            bridge.hookAllMethodsByName(
                clazz = engineCls,
                methodName = mName,
                before = cb@{ _, args ->
                    if (!isForegroundActive.get()) return@cb
                    val url = args.firstOrNull { it is String } as? String
                    if (url != null && isStaticAssetUrl(url)) return@cb
                    for (arg in args) {
                        if (arg == null || arg is String) continue
                        if (isCronetCallbackCandidate(arg.javaClass)) {
                            hookConcreteCronetCallbackClass(arg.javaClass, bridge)
                        }
                    }
                },
                after = cb@{ _, args, result ->
                    if (result == null) return@cb
                    val url = args.firstOrNull { it is String } as? String
                    if (url != null && isStaticAssetUrl(url)) return@cb

                    if (mName.endsWith("Builder")) {
                        hookConcreteCronetRequestBuilderClass(result.javaClass, bridge)
                        if (isForegroundActive.get() && url != null) {
                            synchronized(builderHeaderMap) {
                                builderHeaderMap.getOrPut(result) { EphemeralSessionHeaders() }.url = url
                            }
                        }
                    } else {
                        hookConcreteCronetRequestInstanceClass(result.javaClass, bridge)
                    }
                }
            )
        }
    }

    private fun hookConcreteCronetRequestBuilderClass(builderCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("cronet_req_builder", builderCls)) return

        // Dynamically intercept outgoing Authorization / Cookie / X-Goog-AuthUser headers on the fly!
        bridge.hookAllMethodsByName(
            clazz = builderCls,
            methodName = "addHeader",
            before = cb@{ thisObj, args ->
                if (!isForegroundActive.get() || thisObj == null || args.size < 2) return@cb
                val k = args[0] as? String ?: return@cb
                val v = args[1] as? String ?: return@cb
                recordOutgoingBuilderHeader(thisObj, k, v, transport = "Cronet")
            }
        )

        bridge.hookAllMethodsByName(
            clazz = builderCls,
            methodName = "setHttpMethod",
            before = cb@{ thisObj, args ->
                if (!isForegroundActive.get() || thisObj == null || args.isEmpty()) return@cb
                val m = args[0] as? String ?: return@cb
                synchronized(builderHeaderMap) {
                    builderHeaderMap.getOrPut(thisObj) { EphemeralSessionHeaders() }.method = m.trim().uppercase()
                }
            }
        )

        bridge.hookAllMethodsByName(
            clazz = builderCls,
            methodName = "build",
            after = cb@{ thisObj, _, result ->
                if (result == null) return@cb
                hookConcreteCronetRequestInstanceClass(result.javaClass, bridge)
                if (!isForegroundActive.get() || thisObj == null) return@cb

                val captured = synchronized(builderHeaderMap) {
                    builderHeaderMap.remove(thisObj)
                }
                val url = captured?.url
                if (url != null && isNonQuotaTelemetryUrl(url)) return@cb

                val sessTag = if (captured != null && captured.hasCredentials) {
                    onDynamicHeadersIntercepted(captured, transport = "Cronet")
                } else {
                    activeSessionTag.get()
                }
                val httpMethod = captured?.method ?: "GET"
                val isQuotaCand = isReplayableQuotaEndpoint(url, httpMethod)
                if (url != null && isQuotaCand) {
                    recordLearnedQuotaEndpoint(url, httpMethod, rpcHint = "Cronet-Learned")
                }
                pruneRequestMapsIfNeeded()
                requestContextMap[System.identityHashCode(result)] = BoundRequestContext(
                    urlOrMethod = url,
                    httpMethod = httpMethod,
                    sessionTag = sessTag,
                    isQueryCandidate = isGeminiQueryEndpoint(url),
                    isQuotaEndpointCandidate = isQuotaCand
                )
            }
        )
    }

    private fun hookConcreteCronetRequestInstanceClass(reqCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("cronet_req_inst", reqCls)) return

        bridge.hookAllConstructors(
            clazz = reqCls,
            after = cb@{ thisObj, args ->
                var urlStr: String? = null
                for (arg in args) {
                    if (arg is String && (arg.startsWith("https://") || arg.startsWith("http://"))) {
                        urlStr = arg
                    } else if (arg != null && isCronetCallbackCandidate(arg.javaClass)) {
                        hookConcreteCronetCallbackClass(arg.javaClass, bridge)
                    }
                }
                if (isForegroundActive.get() && thisObj != null && urlStr != null && !isNonQuotaTelemetryUrl(urlStr)) {
                    val reqId = System.identityHashCode(thisObj)
                    val isQuotaCand = isReplayableQuotaEndpoint(urlStr, "GET")
                    if (isQuotaCand) {
                        recordLearnedQuotaEndpoint(urlStr, "GET", rpcHint = "Cronet-Learned")
                    }
                    pruneRequestMapsIfNeeded()
                    requestContextMap.putIfAbsent(
                        reqId,
                        BoundRequestContext(
                            urlOrMethod = urlStr,
                            httpMethod = null,
                            sessionTag = activeSessionTag.get(),
                            isQueryCandidate = isGeminiQueryEndpoint(urlStr),
                            isQuotaEndpointCandidate = isQuotaCand
                        )
                    )
                }
            }
        )

        // Detect when Gemini actually dispatches a query request in the foreground
        bridge.hookAllMethodsByName(
            clazz = reqCls,
            methodName = "start",
            after = cb@{ thisObj, _, _ ->
                if (!isForegroundActive.get() || thisObj == null) return@cb
                val ctx = requestContextMap[System.identityHashCode(thisObj)]
                if (ctx != null && ctx.isQueryCandidate) {
                    onUserQueryDispatched("Cronet")
                }
            }
        )
    }

    private fun isCronetCallbackCandidate(cls: Class<*>): Boolean {
        return try {
            var cur: Class<*>? = cls
            var depth = 0
            while (cur != null && cur != Any::class.java && depth < 6) {
                val n = cur.name
                if (n.contains("UrlRequest\$Callback") ||
                    n.contains("BidirectionalStream\$Callback") ||
                    n.startsWith("org.chromium.net.")
                ) {
                    return true
                }
                if (cur.declaredMethods.any { it.name == "onReadCompleted" }) {
                    return true
                }
                cur = cur.superclass
                depth++
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun hookConcreteCronetCallbackClass(callbackCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("cronet_cb", callbackCls)) return

        bridge.hookAllMethodsByName(
            clazz = callbackCls,
            methodName = "onReadCompleted",
            before = cb@{ _, args ->
                // Zero-Background Gate: 1ns exit if Gemini is not in foreground
                if (!isForegroundActive.get()) return@cb
                val reqObj = args.getOrNull(0) ?: return@cb
                val infoObj = args.getOrNull(1)
                val byteBuf = args.firstOrNull { it is ByteBuffer } as? ByteBuffer ?: return@cb

                val reqId = System.identityHashCode(reqObj)
                val boundCtx = requestContextMap[reqId]
                val urlHint = boundCtx?.urlOrMethod ?: extractCronetUrlSafe(infoObj)
                if (urlHint != null && isStaticAssetUrl(urlHint)) return@cb

                val copied = copyCronetByteBufferNonDestructive(byteBuf) ?: return@cb
                val reqSession = boundCtx?.sessionTag ?: activeSessionTag.get()
                submitCronetChunk(reqId, urlHint, reqSession, copied)
            }
        )

        val cleanupMethods = arrayOf("onSucceeded", "onFailed", "onCanceled")
        for (cName in cleanupMethods) {
            bridge.hookAllMethodsByName(
                clazz = callbackCls,
                methodName = cName,
                after = { _, args, _ ->
                    val reqObj = args.getOrNull(0)
                    if (reqObj != null) {
                        val id = System.identityHashCode(reqObj)
                        cronetRollingStreams.remove(id)
                        requestContextMap.remove(id)
                    }
                }
            )
        }
    }

    private fun copyCronetByteBufferNonDestructive(rawBuf: ByteBuffer): ByteArray? {
        return try {
            val dup = rawBuf.duplicate()
            val pos = dup.position()
            val rem = dup.remaining()
            val out: ByteArray
            if (pos > 0) {
                val n = minOf(pos, MAX_CHUNK_COPY_BYTES)
                dup.limit(pos)
                dup.position(pos - n)
                out = ByteArray(n)
                dup.get(out)
            } else if (rem > 0) {
                val n = minOf(rem, MAX_CHUNK_COPY_BYTES)
                out = ByteArray(n)
                dup.get(out)
            } else {
                return null
            }
            if (out.size >= 4) out else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractCronetUrlSafe(infoObj: Any?): String? {
        if (infoObj == null) return null
        return try {
            val m = infoObj.javaClass.getMethod("getUrl")
            m.invoke(infoObj) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun isGeminiQueryEndpoint(urlOrMethod: String?): Boolean {
        if (urlOrMethod.isNullOrBlank()) return false
        val u = urlOrMethod.lowercase()
        return u.contains("streamgenerate") ||
            u.contains("assistant.lamda") ||
            u.contains("aratea") ||
            u.contains("generativelanguage")
    }

    private fun isReplayableQuotaEndpoint(url: String?, method: String?): Boolean {
        if (url.isNullOrBlank() || isNonQuotaTelemetryUrl(url)) return false
        val u = url.lowercase()
        if (!u.contains("google.com")) return false
        if (u.contains("streamgenerate") || u.contains("generatecontent") ||
            u.contains("create") || u.contains("delete") || u.contains("feedback")
        ) {
            return false
        }
        val isGet = method.isNullOrBlank() || method.equals("GET", ignoreCase = true)
        if (u.contains("quota") || u.contains("usage") || u.contains("allowance") ||
            u.contains("eligibility") || u.contains("ratelimit") || u.contains("rate_limit")
        ) {
            return isGet || u.contains("batchexecute")
        }
        if (u.contains("batchexecute") && (u.contains("hooq4d") || u.contains("o4g7id") || u.contains("maziqc"))) {
            return true
        }
        return false
    }

    private fun isNonQuotaTelemetryUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        if (isStaticAssetUrl(u)) return true
        return u.contains("play.googleapis.com/log") ||
            u.contains("clearcut") ||
            u.contains("phenotype") ||
            u.contains("crashlytics") ||
            u.contains("firebaselogging") ||
            u.contains("app-measurement") ||
            u.contains("doubleclick") ||
            u.contains("googlesyndication") ||
            u.contains("clientservices.googleapis.com") ||
            u.contains("update.googleapis.com") ||
            u.contains("safebrowsing") ||
            u.contains("gvt1.com") ||
            u.contains("gvt2.com")
    }

    private fun recordLearnedQuotaEndpoint(url: String, method: String, rpcHint: String? = null) {
        if (!isReplayableQuotaEndpoint(url, method)) return
        synchronized(learnedEndpointsLock) {
            learnedQuotaEndpoints[url] = LearnedEndpoint(
                url = url,
                method = method.uppercase(),
                rpcHint = rpcHint,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    private fun isStaticAssetUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".png") ||
            u.endsWith(".jpg") ||
            u.endsWith(".jpeg") ||
            u.endsWith(".webp") ||
            u.endsWith(".gif") ||
            u.endsWith(".svg") ||
            u.endsWith(".woff2") ||
            u.endsWith(".ttf") ||
            u.endsWith(".mp3") ||
            u.endsWith(".mp4") ||
            u.contains("lh3.googleusercontent.com") ||
            u.contains("fonts.gstatic.com")
    }

    private fun pruneRequestMapsIfNeeded() {
        val now = System.currentTimeMillis()
        if (cronetRollingStreams.size >= 32) {
            val cutoff = now - 45_000L
            val it = cronetRollingStreams.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.createdAtMs < cutoff || cronetRollingStreams.size > 48) {
                    it.remove()
                }
            }
        }
        if (requestContextMap.size >= 64) {
            val cutoff = now - 45_000L
            val it = requestContextMap.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.createdAtMs < cutoff || requestContextMap.size > 96) {
                    it.remove()
                }
            }
        }
    }

    private fun submitCronetChunk(
        reqId: Int,
        urlHint: String?,
        sessionTag: String,
        chunk: ByteArray
    ) {
        submitForegroundWork {
            pruneRequestMapsIfNeeded()
            val holder = cronetRollingStreams.getOrPut(reqId) {
                RollingStreamBuffer(urlHint, sessionTag)
            }
            val combined = holder.appendAndSnapshot(chunk, chunk.size) ?: return@submitForegroundWork
            val baseSnap = getBaseSnapshotForSession(holder.sessionTag)
            val resolvedUrl = urlHint ?: holder.urlHint
            val snap = QuotaTextParser.scanProtoBufBytes(
                rawBytes = combined,
                length = combined.size,
                prev = baseSnap,
                source = "Cronet-PB",
                rpcMethodHint = resolvedUrl
            )
            if (snap != null) {
                val boundCtx = requestContextMap[reqId]
                if (resolvedUrl != null && isReplayableQuotaEndpoint(resolvedUrl, boundCtx?.httpMethod)) {
                    recordLearnedQuotaEndpoint(resolvedUrl, boundCtx?.httpMethod ?: "GET", rpcHint = "Cronet-PB")
                }
                publishSnapshotForSession(snap, holder.sessionTag)
            }
        }
    }

    // -------------------------------------------------------------------------
    // gRPC Dynamic Metadata (`authorization`/`cookie`) + Query & Stream Hooks
    // -------------------------------------------------------------------------

    private fun installGrpcSessionAndStreamHooks(cl: ClassLoader, bridge: HookBridge) {
        // Hook io.grpc.Metadata.put(Key, value) to capture OAuth2 Bearer tokens & cookies injected
        // dynamically on the fly by Google's CallCredentials (even on async token refreshes!).
        val metadataCls = findClassSafe("io.grpc.Metadata", cl)
        if (metadataCls != null && markDynamicHookOnce("grpc_metadata", metadataCls)) {
            bridge.hookAllMethodsByName(
                clazz = metadataCls,
                methodName = "put",
                before = cb@{ thisObj, args ->
                    if (!isForegroundActive.get() || thisObj == null || args.size < 2) return@cb
                    val keyObj = args[0] ?: return@cb
                    val valStr = (args[1] as? String) ?: return@cb
                    val keyName = extractGrpcMetadataKeyNameSafe(keyObj) ?: return@cb
                    recordOutgoingBuilderHeader(thisObj, keyName, valStr)
                }
            )
        }

        val interceptorsHelper = findClassSafe("io.grpc.ClientInterceptors", cl)
        if (interceptorsHelper != null) {
            val helperMethods = arrayOf("intercept", "interceptForward")
            for (mName in helperMethods) {
                bridge.hookAllMethodsByName(
                    clazz = interceptorsHelper,
                    methodName = mName,
                    before = { _, args ->
                        val ch = args.firstOrNull()
                        if (ch != null) hookConcreteGrpcChannelClass(ch.javaClass, bridge)
                        for (arg in args) {
                            if (arg is Iterable<*>) {
                                for (item in arg) {
                                    if (item != null) hookConcreteGrpcInterceptorClass(item.javaClass, bridge)
                                }
                            } else if (arg is Array<*>) {
                                for (item in arg) {
                                    if (item != null) hookConcreteGrpcInterceptorClass(item.javaClass, bridge)
                                }
                            }
                        }
                    },
                    after = { _, _, result ->
                        if (result != null) hookConcreteGrpcChannelClass(result.javaClass, bridge)
                    }
                )
            }
        }

        val channelCandidates = arrayOf(
            "io.grpc.Channel",
            "io.grpc.ManagedChannel",
            "io.grpc.internal.ManagedChannelImpl",
            "io.grpc.internal.ForwardingManagedChannel",
            "io.grpc.cronet.CronetChannelBuilder",
            "io.grpc.okhttp.OkHttpChannelBuilder"
        )
        for (cName in channelCandidates) {
            val cls = findClassSafe(cName, cl) ?: continue
            if (cName.endsWith("Builder")) {
                bridge.hookAllMethodsByName(
                    clazz = cls,
                    methodName = "build",
                    after = { _, _, result ->
                        if (result != null) hookConcreteGrpcChannelClass(result.javaClass, bridge)
                    }
                )
            } else {
                hookConcreteGrpcChannelClass(cls, bridge)
            }
        }
    }

    private fun extractGrpcMetadataKeyNameSafe(keyObj: Any): String? {
        return try {
            val m = keyObj.javaClass.getMethod("name")
            m.invoke(keyObj) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractSessionFromGrpcMetadataObject(metadataObj: Any?): String? {
        if (metadataObj == null) return null
        // Check if Metadata.put already recorded headers on this Metadata instance
        val recorded = synchronized(builderHeaderMap) {
            builderHeaderMap[metadataObj]?.copy()
        }
        if (recorded != null && recorded.hasCredentials) {
            return onDynamicHeadersIntercepted(recorded, transport = "gRPC")
        }

        // Reflective fallback for pre-populated io.grpc.Metadata internal namesAndValues array
        return try {
            val f = findFieldInHierarchy(metadataObj.javaClass, "namesAndValues") ?: return null
            val arr = f.get(metadataObj) as? Array<*> ?: return null
            val headers = EphemeralSessionHeaders()
            var i = 0
            while (i + 1 < arr.size) {
                val rawK = arr[i]
                val rawV = arr[i + 1]
                val k = when (rawK) {
                    is ByteArray -> String(rawK, StandardCharsets.US_ASCII)
                    is String -> rawK
                    else -> null
                }?.lowercase()
                val v = when (rawV) {
                    is ByteArray -> String(rawV, StandardCharsets.UTF_8)
                    is String -> rawV
                    else -> null
                }
                if (k != null && !v.isNullOrBlank()) {
                    when (k) {
                        "authorization" -> headers.authorization = v.trim()
                        "cookie" -> headers.cookie = v.trim()
                        "x-goog-authuser" -> headers.authUser = v.trim()
                        "x-goog-pageid" -> headers.pageId = v.trim()
                    }
                }
                i += 2
            }
            if (headers.hasCredentials) {
                onDynamicHeadersIntercepted(headers, transport = "gRPC")
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookConcreteGrpcInterceptorClass(interceptorCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_interceptor", interceptorCls)) return
        bridge.hookAllMethodsByName(
            clazz = interceptorCls,
            methodName = "interceptCall",
            after = { _, args, result ->
                if (result != null) {
                    if (isForegroundActive.get()) {
                        val fullMethod = extractGrpcMethodNameSafe(args.getOrNull(0))
                        pruneRequestMapsIfNeeded()
                        requestContextMap[System.identityHashCode(result)] = BoundRequestContext(
                            urlOrMethod = fullMethod,
                            httpMethod = "gRPC",
                            sessionTag = activeSessionTag.get(),
                            isQueryCandidate = isGeminiQueryEndpoint(fullMethod),
                            isQuotaEndpointCandidate = false
                        )
                    }
                    hookConcreteGrpcClientCallClass(result.javaClass, bridge)
                }
            }
        )
    }

    private fun hookConcreteGrpcChannelClass(channelCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_channel", channelCls)) return
        bridge.hookAllMethodsByName(
            clazz = channelCls,
            methodName = "newCall",
            after = { _, args, result ->
                if (result != null) {
                    if (isForegroundActive.get()) {
                        val fullMethod = extractGrpcMethodNameSafe(args.getOrNull(0))
                        pruneRequestMapsIfNeeded()
                        requestContextMap[System.identityHashCode(result)] = BoundRequestContext(
                            urlOrMethod = fullMethod,
                            httpMethod = "gRPC",
                            sessionTag = activeSessionTag.get(),
                            isQueryCandidate = isGeminiQueryEndpoint(fullMethod),
                            isQuotaEndpointCandidate = false
                        )
                    }
                    hookConcreteGrpcClientCallClass(result.javaClass, bridge)
                }
            }
        )
    }

    private fun hookConcreteGrpcClientCallClass(callCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_call", callCls)) return

        // ClientCall.start(Listener, Metadata): capture outgoing session Metadata + hook Listener
        bridge.hookAllMethodsByName(
            clazz = callCls,
            methodName = "start",
            before = cb@{ thisObj, args ->
                val listener = args.getOrNull(0) ?: return@cb
                val metadata = args.getOrNull(1)
                val callId = if (thisObj != null) System.identityHashCode(thisObj) else 0
                val existingCtx = if (callId != 0) requestContextMap[callId] else null

                if (isForegroundActive.get()) {
                    val dynamicSess = extractSessionFromGrpcMetadataObject(metadata)
                        ?: activeSessionTag.get()
                    val rpcName = existingCtx?.urlOrMethod
                    val updatedCtx = BoundRequestContext(
                        urlOrMethod = rpcName,
                        httpMethod = "gRPC",
                        sessionTag = dynamicSess,
                        isQueryCandidate = isGeminiQueryEndpoint(rpcName),
                        isQuotaEndpointCandidate = false
                    )
                    if (callId != 0) {
                        requestContextMap[callId] = updatedCtx
                    }
                    requestContextMap[System.identityHashCode(listener)] = updatedCtx
                }

                hookConcreteGrpcListenerClass(listener.javaClass, bridge)
            }
        )

        // ClientCall.sendMessage(ReqT): detect user query dispatch in foreground
        bridge.hookAllMethodsByName(
            clazz = callCls,
            methodName = "sendMessage",
            after = cb@{ thisObj, _, _ ->
                if (!isForegroundActive.get() || thisObj == null) return@cb
                val ctx = requestContextMap[System.identityHashCode(thisObj)]
                if (ctx == null || ctx.isQueryCandidate) {
                    onUserQueryDispatched("gRPC")
                }
            }
        )
    }

    private fun hookConcreteGrpcListenerClass(listenerCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_listener", listenerCls)) return

        bridge.hookAllMethodsByName(
            clazz = listenerCls,
            methodName = "onMessage",
            after = cb@{ thisObj, args, _ ->
                if (!isForegroundActive.get()) return@cb
                val msg = args.firstOrNull() ?: return@cb
                val listenerCtx = if (thisObj != null) {
                    requestContextMap[System.identityHashCode(thisObj)]
                } else {
                    null
                }
                inspectGrpcResponseMessageAsync(
                    message = msg,
                    rpcHint = listenerCtx?.urlOrMethod,
                    sessionTag = listenerCtx?.sessionTag ?: activeSessionTag.get()
                )
            }
        )

        bridge.hookAllMethodsByName(
            clazz = listenerCls,
            methodName = "onClose",
            after = { thisObj, _, _ ->
                if (thisObj != null) {
                    requestContextMap.remove(System.identityHashCode(thisObj))
                }
            }
        )
    }

    private fun extractGrpcMethodNameSafe(methodDescriptor: Any?): String? {
        if (methodDescriptor == null) return null
        return try {
            val m = methodDescriptor.javaClass.getMethod("getFullMethodName")
            m.invoke(methodDescriptor) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun inspectGrpcResponseMessageAsync(
        message: Any,
        rpcHint: String?,
        sessionTag: String
    ) {
        submitForegroundWork {
            val targetSession = if (sessionTag != DEFAULT_SESSION_TAG) sessionTag else activeSessionTag.get()
            val baseSnap = getBaseSnapshotForSession(targetSession)

            when (message) {
                is ByteArray -> {
                    val snap = QuotaTextParser.scanProtoBufBytes(
                        rawBytes = message,
                        length = message.size,
                        prev = baseSnap,
                        source = "gRPC-PB",
                        rpcMethodHint = rpcHint
                    )
                    if (snap != null) publishSnapshotForSession(snap, targetSession)
                }
                is ByteBuffer -> {
                    val bytes = copyCronetByteBufferNonDestructive(message)
                    if (bytes != null) {
                        val snap = QuotaTextParser.scanProtoBufBytes(
                            rawBytes = bytes,
                            length = bytes.size,
                            prev = baseSnap,
                            source = "gRPC-PB",
                            rpcMethodHint = rpcHint
                        )
                        if (snap != null) publishSnapshotForSession(snap, targetSession)
                    }
                }
                else -> {
                    val fieldSnap = inspectObjectGraphForQuota(message, "gRPC-Model", baseSnap)
                    if (fieldSnap != null) {
                        publishSnapshotForSession(fieldSnap, targetSession)
                        return@submitForegroundWork
                    }
                    val serialized = trySerializeProtoLite(message)
                    if (serialized != null && serialized.size in 6..MAX_STREAM_ACCUM_BYTES) {
                        val protoSnap = QuotaTextParser.scanProtoBufBytes(
                            rawBytes = serialized,
                            length = serialized.size,
                            prev = baseSnap,
                            source = "gRPC-PB",
                            rpcMethodHint = rpcHint
                        )
                        if (protoSnap != null) publishSnapshotForSession(protoSnap, targetSession)
                    }
                }
            }
        }
    }

    private fun trySerializeProtoLite(obj: Any): ByteArray? {
        return try {
            val m = obj.javaClass.getMethod("toByteArray")
            if (m.returnType == ByteArray::class.java && m.parameterCount == 0) {
                m.invoke(obj) as? ByteArray
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // OkHttp Outgoing Header Interception & Passive Response Hooks
    // -------------------------------------------------------------------------

    private fun installOkHttpSessionAndBodyHooks(cl: ClassLoader, bridge: HookBridge) {
        val reqBuilderCls = findClassSafe("okhttp3.Request\$Builder", cl)
        if (reqBuilderCls != null && markDynamicHookOnce("okhttp_req_builder", reqBuilderCls)) {
            bridge.hookAllMethodsByName(
                clazz = reqBuilderCls,
                methodName = "url",
                before = cb@{ thisObj, args ->
                    if (!isForegroundActive.get() || thisObj == null || args.isEmpty()) return@cb
                    val uStr = args[0]?.toString() ?: return@cb
                    if (uStr.startsWith("http")) {
                        synchronized(builderHeaderMap) {
                            builderHeaderMap.getOrPut(thisObj) { EphemeralSessionHeaders() }.url = uStr
                        }
                        if (isReplayableQuotaEndpoint(uStr, "GET")) {
                            recordLearnedQuotaEndpoint(uStr, "GET", rpcHint = "OkHttp-Learned")
                        }
                    }
                }
            )
            val headerMethods = arrayOf("header", "addHeader")
            for (mName in headerMethods) {
                bridge.hookAllMethodsByName(
                    clazz = reqBuilderCls,
                    methodName = mName,
                    before = cb@{ thisObj, args ->
                        if (!isForegroundActive.get() || thisObj == null || args.size < 2) return@cb
                        val k = args[0] as? String ?: return@cb
                        val v = args[1] as? String ?: return@cb
                        recordOutgoingBuilderHeader(thisObj, k, v, transport = "OkHttp")
                    }
                )
            }
        }

        val respBodyCls = findClassSafe("okhttp3.ResponseBody", cl) ?: return
        if (!markDynamicHookOnce("okhttp_body", respBodyCls)) return

        bridge.hookAllMethodsByName(
            clazz = respBodyCls,
            methodName = "bytes",
            after = cb@{ _, _, result ->
                if (!isForegroundActive.get()) return@cb
                val bytes = result as? ByteArray ?: return@cb
                if (bytes.size !in 6..MAX_STREAM_ACCUM_BYTES) return@cb
                val sess = activeSessionTag.get()
                submitForegroundWork {
                    val snap = QuotaTextParser.scanProtoBufBytes(
                        rawBytes = bytes,
                        length = bytes.size,
                        prev = getBaseSnapshotForSession(sess),
                        source = "OkHttp-PB"
                    )
                    if (snap != null) publishSnapshotForSession(snap, sess)
                }
            }
        )

        bridge.hookAllMethodsByName(
            clazz = respBodyCls,
            methodName = "string",
            after = cb@{ _, _, result ->
                if (!isForegroundActive.get()) return@cb
                val raw = result as? String ?: return@cb
                if (raw.length > MAX_STREAM_ACCUM_BYTES || !QuotaTextParser.fastHasQuotaKeyword(raw)) return@cb
                val sess = activeSessionTag.get()
                submitForegroundWork {
                    val snap = QuotaTextParser.parseInAppStructuredPayload(
                        payload = raw,
                        prev = getBaseSnapshotForSession(sess),
                        source = "OkHttp-JSON"
                    )
                    if (snap != null) publishSnapshotForSession(snap, sess)
                }
            }
        )
    }

    private fun hookStructuredJsonPayloads(bridge: HookBridge) {
        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONObject::class.java,
            paramTypes = arrayOf(String::class.java)
        ) ctor@{ thisObj, args ->
            if (!isForegroundActive.get()) return@ctor
            val raw = args.firstOrNull() as? String ?: return@ctor
            if (raw.length > MAX_STREAM_ACCUM_BYTES || !QuotaTextParser.fastHasQuotaKeyword(raw)) return@ctor
            withReentrancyGuard {
                val obj = thisObj as? JSONObject ?: return@withReentrancyGuard
                val sess = activeSessionTag.get()
                val snap = QuotaTextParser.inspectJsonObject(obj, getBaseSnapshotForSession(sess), "InApp-JSON")
                if (snap != null) publishSnapshotForSession(snap, sess)
            }
        }

        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONObject::class.java,
            paramTypes = arrayOf(JSONTokener::class.java)
        ) ctor@{ thisObj, _ ->
            if (!isForegroundActive.get()) return@ctor
            withReentrancyGuard {
                val obj = thisObj as? JSONObject ?: return@withReentrancyGuard
                val sess = activeSessionTag.get()
                val snap = QuotaTextParser.inspectJsonObject(obj, getBaseSnapshotForSession(sess), "InApp-JSON")
                if (snap != null) publishSnapshotForSession(snap, sess)
            }
        }
    }

    // =========================================================================
    // Stage 3: Foreground-Only ViewModel / StateFlow / LiveData Inspection
    // =========================================================================

    fun installForegroundMemoryHooks(
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        activeBridgeRef.set(bridge)
        if (classLoader != null) {
            bindHooksForClassLoader(classLoader, bridge)
        }
    }

    private fun installReactiveStateHooksInClassLoader(cl: ClassLoader, bridge: HookBridge) {
        val vmCls = findClassSafe("androidx.lifecycle.ViewModel", cl)
        if (vmCls != null && markDynamicHookOnce("androidx_vm", vmCls)) {
            bridge.hookAllConstructors(
                clazz = vmCls,
                after = { thisObj, _ ->
                    if (thisObj != null && isForegroundActive.get()) {
                        try {
                            liveViewModels.add(thisObj)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }

        val sfCls = findClassSafe("kotlinx.coroutines.flow.StateFlowImpl", cl)
        if (sfCls != null && markDynamicHookOnce("stateflow_impl", sfCls)) {
            val mutators = arrayOf("setValue", "updateState")
            for (mName in mutators) {
                bridge.hookAllMethodsByName(
                    clazz = sfCls,
                    methodName = mName,
                    after = cb@{ _, args, _ ->
                        if (!isForegroundActive.get()) return@cb
                        val emitted = if (args.size >= 2) args[1] else args.firstOrNull()
                        if (emitted != null && isFastQuotaModelCandidate(emitted)) {
                            inspectStateEmissionAsync(emitted, "StateFlow")
                        }
                    }
                )
            }
        }

        val liveDataNames = arrayOf(
            "androidx.lifecycle.LiveData",
            "androidx.lifecycle.MutableLiveData"
        )
        for (ldName in liveDataNames) {
            val ldCls = findClassSafe(ldName, cl) ?: continue
            if (!markDynamicHookOnce("livedata", ldCls)) continue
            val mutators = arrayOf("setValue", "postValue")
            for (mName in mutators) {
                bridge.hookAllMethodsByName(
                    clazz = ldCls,
                    methodName = mName,
                    after = cb@{ _, args, _ ->
                        if (!isForegroundActive.get()) return@cb
                        val emitted = args.firstOrNull() ?: return@cb
                        if (isFastQuotaModelCandidate(emitted)) {
                            inspectStateEmissionAsync(emitted, "LiveData")
                        }
                    }
                )
            }
        }
    }

    private fun isFastQuotaModelCandidate(obj: Any): Boolean {
        val cls = obj.javaClass
        if (cls.isPrimitive || cls.isArray || cls.isEnum) return false
        val cached = classCandidateCache[cls]
        if (cached != null) return cached

        val verdict = try {
            val name = cls.name
            if (name.startsWith("java.") ||
                name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.coroutines.") ||
                name.startsWith("android.") ||
                name.startsWith("androidx.compose.") ||
                name.startsWith("androidx.recyclerview.") ||
                name.startsWith("com.example.bardquotahook.")
            ) {
                false
            } else {
                val lowerName = name.lowercase()
                if (lowerName.contains("quota") ||
                    lowerName.contains("usage") ||
                    lowerName.contains("limit") ||
                    lowerName.contains("allowance") ||
                    lowerName.contains("ratelimit")
                ) {
                    true
                } else {
                    val fields = cls.declaredFields
                    var hasQuotaFieldName = false
                    var numericCount = 0
                    var objectOrTimeCount = 0
                    for (f in fields) {
                        if (Modifier.isStatic(f.modifiers)) continue
                        val fn = f.name.lowercase()
                        if (fn.contains("quota") ||
                            fn.contains("limit") ||
                            fn.contains("remaining") ||
                            fn.contains("weekly") ||
                            fn.contains("reset") ||
                            fn.contains("allowance")
                        ) {
                            hasQuotaFieldName = true
                            break
                        }
                        val ft = f.type
                        if (ft == Int::class.javaPrimitiveType ||
                            ft == Long::class.javaPrimitiveType ||
                            ft == Float::class.javaPrimitiveType ||
                            ft == Double::class.javaPrimitiveType ||
                            Number::class.java.isAssignableFrom(ft)
                        ) {
                            numericCount++
                        } else if (!ft.isPrimitive && ft != Boolean::class.javaPrimitiveType) {
                            objectOrTimeCount++
                        }
                    }
                    hasQuotaFieldName || (fields.size in 2..16 && numericCount >= 1 && (numericCount + objectOrTimeCount) >= 2)
                }
            }
        } catch (_: Throwable) {
            false
        }

        if (classCandidateCache.size > 2048) {
            classCandidateCache.clear()
        }
        classCandidateCache[cls] = verdict
        return verdict
    }

    private fun inspectStateEmissionAsync(stateObj: Any, source: String) {
        val sess = activeSessionTag.get()
        submitForegroundWork {
            val snap = inspectObjectGraphForQuota(stateObj, source, getBaseSnapshotForSession(sess))
            if (snap != null) {
                publishSnapshotForSession(snap, sess)
            }
        }
    }

    /**
     * Lightweight, strictly event-driven foreground refresh (`APP_OPEN`, `QUERY_SENT`,
     * `ACCOUNT_SWITCH`, `MANUAL_TAP`). Never runs on a recurring timer or in background.
     */
    private fun triggerLightweightQuotaRefresh(
        reason: RefreshReason,
        delayMs: Long = 0L
    ) {
        if (!isForegroundActive.get()) return
        val force = reason == RefreshReason.MANUAL_TAP || reason == RefreshReason.ACCOUNT_SWITCH
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs.get() < 1_500L) return
        lastRefreshMs.set(now)

        val executeRefresh = {
            if (isForegroundActive.get()) {
                val sess = activeSessionTag.get()
                submitForegroundWork {
                    if (dexRepoScanCompleted.compareAndSet(false, true)) {
                        appContext?.classLoader?.let { scanDexClassesForRepositoriesSafe(it) }
                    }
                    runForegroundViewModelAndRepoScan(sess)
                    fetchActiveQuotaOverHttp(sess, reason)
                }
                postToMain(delayMs = 250L, requireForeground = true) {
                    scanForExhaustedBannerOnly(sess)
                }
            }
        }

        if (delayMs > 0L) {
            postToMain(delayMs = delayMs, requireForeground = true) { executeRefresh() }
        } else {
            executeRefresh()
        }
    }

    @Suppress("DEPRECATION")
    private fun scanDexClassesForRepositoriesSafe(classLoader: ClassLoader) {
        try {
            var currentCl: ClassLoader? = classLoader
            var inspectedCount = 0

            while (currentCl != null && inspectedCount < 2500 && isForegroundActive.get()) {
                if (currentCl is BaseDexClassLoader) {
                    val pathList = findFieldInHierarchy(currentCl.javaClass, "pathList")?.get(currentCl)
                    val dexElements = pathList
                        ?.let { findFieldInHierarchy(it.javaClass, "dexElements")?.get(it) } as? Array<*>

                    if (dexElements != null) {
                        for (element in dexElements) {
                            if (element == null || !isForegroundActive.get()) break
                            val dexFile = findFieldInHierarchy(element.javaClass, "dexFile")?.get(element) ?: continue
                            val entriesMethod = try {
                                dexFile.javaClass.getMethod("entries")
                            } catch (_: Throwable) {
                                null
                            } ?: continue

                            @Suppress("UNCHECKED_CAST")
                            val entries = try {
                                entriesMethod.invoke(dexFile) as? Enumeration<String>
                            } catch (_: Throwable) {
                                null
                            } ?: continue

                            while (entries.hasMoreElements() && inspectedCount < 2500 && isForegroundActive.get()) {
                                val clsName = try {
                                    entries.nextElement()
                                } catch (_: Throwable) {
                                    null
                                } ?: continue

                                if (!clsName.startsWith("com.google.android.apps.bard") &&
                                    !clsName.startsWith("com.google.assistant") &&
                                    !clsName.startsWith("com.google.android.libraries.search")
                                ) {
                                    continue
                                }

                                val lower = clsName.lowercase()
                                if (!lower.contains("quota") &&
                                    !lower.contains("usage") &&
                                    !lower.contains("limit") &&
                                    !lower.contains("allowance")
                                ) {
                                    continue
                                }

                                inspectedCount++
                                try {
                                    val loaded = Class.forName(clsName, false, currentCl)
                                    for (f in loaded.declaredFields) {
                                        if (!Modifier.isStatic(f.modifiers)) continue
                                        try {
                                            f.isAccessible = true
                                            val staticVal = f.get(null) ?: continue
                                            if (staticVal.javaClass == loaded ||
                                                isReactiveHolder(staticVal) ||
                                                isFastQuotaModelCandidate(staticVal)
                                            ) {
                                                liveRepositories.add(staticVal)
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
                currentCl = try {
                    currentCl.parent
                } catch (_: Throwable) {
                    null
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun runForegroundViewModelAndRepoScan(sessionTag: String) {
        if (!isForegroundActive.get()) return
        val roots = ArrayList<Pair<Any, String>>(24)

        val activity = activeActivityRef.get().get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            collectViewModelsFromActivitySafe(activity, roots)
        }

        try {
            synchronized(liveViewModels) {
                for (vm in liveViewModels) {
                    if (roots.size < 32) roots.add(vm to "ViewModel")
                }
            }
        } catch (_: Throwable) {
        }

        try {
            synchronized(liveRepositories) {
                for (repo in liveRepositories) {
                    if (roots.size < 48) roots.add(repo to "Repository")
                }
            }
        } catch (_: Throwable) {
        }

        val ctx = appContext
        if (ctx != null) {
            roots.add(ctx to "AppGraph")
        }

        for ((rootObj, tag) in roots) {
            if (!isForegroundActive.get()) return
            val baseSnap = getBaseSnapshotForSession(sessionTag)
            val snap = inspectObjectGraphForQuota(rootObj, tag, baseSnap)
            if (snap != null) {
                publishSnapshotForSession(snap, sessionTag)
            }
        }
    }

    private fun collectViewModelsFromActivitySafe(
        activity: Activity,
        out: MutableList<Pair<Any, String>>
    ) {
        try {
            val getStoreMethod: Method? = findMethodInHierarchy(activity.javaClass, "getViewModelStore")
            val vmStore = getStoreMethod?.invoke(activity) ?: return
            for (f in vmStore.javaClass.declaredFields) {
                try {
                    f.isAccessible = true
                    val mapCandidate = f.get(vmStore) as? Map<*, *> ?: continue
                    for (vm in mapCandidate.values) {
                        if (vm != null) {
                            liveViewModels.add(vm)
                            out.add(vm to "ViewModel")
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun inspectObjectGraphForQuota(
        root: Any?,
        source: String,
        baseSnapshot: QuotaHudView.Snapshot
    ): QuotaHudView.Snapshot? {
        if (root == null) return null
        val visited = IdentityHashMap<Any, Boolean>(128)
        val namedFields = HashMap<String, String>(8)
        val structuralWindows = ArrayList<StructuralWindow>(4)
        val nowMs = System.currentTimeMillis()

        walkObjectGraphRecursive(
            node = root,
            depth = 0,
            visited = visited,
            nowMs = nowMs,
            namedOut = namedFields,
            windowsOut = structuralWindows
        )

        if (namedFields.isNotEmpty()) {
            val fromNamed = QuotaTextParser.mergeExtractedFields(namedFields, baseSnapshot, source)
            if (fromNamed != null) return fromNamed
        }

        if (structuralWindows.isNotEmpty()) {
            val best5h = structuralWindows
                .filter { it.kind == QuotaTextParser.MetricKind.FIVE_HOUR }
                .maxByOrNull { it.confidence }
            val bestWk = structuralWindows
                .filter { it.kind == QuotaTextParser.MetricKind.WEEKLY }
                .maxByOrNull { it.confidence }

            if ((best5h != null && bestWk != null) ||
                (best5h?.confidence ?: 0) >= 3 ||
                (bestWk?.confidence ?: 0) >= 3
            ) {
                val base = baseSnapshot.sanitized()
                val next = QuotaHudView.Snapshot(
                    fiveHourQuota = best5h?.metric ?: base.fiveHourQuota,
                    fiveHourReset = best5h?.reset ?: base.fiveHourReset,
                    weeklyLimit = bestWk?.metric ?: base.weeklyLimit,
                    weeklyReset = bestWk?.reset ?: base.weeklyReset,
                    source = source
                ).sanitized()
                if (next != base && next.hasLiveData) return next
            }
        }

        return null
    }

    private fun walkObjectGraphRecursive(
        node: Any?,
        depth: Int,
        visited: IdentityHashMap<Any, Boolean>,
        nowMs: Long,
        namedOut: MutableMap<String, String>,
        windowsOut: MutableList<StructuralWindow>
    ) {
        if (node == null || depth > 4 || visited.size >= 140 || windowsOut.size >= 8) return
        val cls = node.javaClass
        if (cls.isPrimitive ||
            node is String ||
            node is Boolean ||
            node is Number ||
            node is Enum<*> ||
            node is View ||
            node is Class<*> ||
            node is ClassLoader
        ) {
            return
        }

        if (visited.put(node, true) != null) return

        val clsName = cls.name
        val bridge = activeBridgeRef.get()

        // Dynamically bind isolated GMS Chimera CronetEngine or gRPC Channel singletons in DI memory
        if (bridge != null) {
            if (clsName.startsWith("org.chromium.net.")) {
                bindHooksForClassLoader(cls.classLoader, bridge)
                if ((clsName.contains("Cronet") && clsName.contains("Context")) || clsName.endsWith("Engine")) {
                    hookConcreteCronetEngineClass(cls, bridge)
                }
                return
            }
            if (clsName.startsWith("io.grpc.")) {
                bindHooksForClassLoader(cls.classLoader, bridge)
                if (clsName.contains("Channel")) {
                    hookConcreteGrpcChannelClass(cls, bridge)
                }
                return
            }
        }

        if (clsName.startsWith("android.") ||
            clsName.startsWith("androidx.compose.ui.") ||
            clsName.startsWith("com.example.bardquotahook.")
        ) {
            return
        }

        if (isReactiveHolder(node)) {
            val inner = unwrapReactiveValueSafe(node)
            if (inner != null) {
                walkObjectGraphRecursive(inner, depth + 1, visited, nowMs, namedOut, windowsOut)
            }
            return
        }

        if (node is Map<*, *>) {
            var count = 0
            try {
                for ((k, v) in node) {
                    if (count++ >= 16) break
                    if (k is String && v != null && (v is Number || v is String)) {
                        val normKey = normalizeFieldName(k)
                        if (normKey != null) {
                            namedOut[normKey] = v.toString()
                        }
                    } else if (v != null) {
                        walkObjectGraphRecursive(v, depth + 1, visited, nowMs, namedOut, windowsOut)
                    }
                }
            } catch (_: Throwable) {
            }
            return
        }

        if (node is Iterable<*>) {
            var count = 0
            try {
                for (elem in node) {
                    if (count++ >= 12) break
                    if (elem != null) {
                        walkObjectGraphRecursive(elem, depth + 1, visited, nowMs, namedOut, windowsOut)
                    }
                }
            } catch (_: Throwable) {
            }
            return
        }

        if (clsName.startsWith("java.") || clsName.startsWith("kotlin.")) return

        val fields = collectInstanceFieldsSafe(cls)
        if (fields.isEmpty()) return

        var objResetWindow: Pair<QuotaTextParser.MetricKind, String>? = null
        val objInts = ArrayList<Int>(6)
        val objDoubles = ArrayList<Double>(4)
        val hasDomainClassHint = clsName.contains("quota", ignoreCase = true) ||
            clsName.contains("usage", ignoreCase = true) ||
            clsName.contains("limit", ignoreCase = true) ||
            clsName.contains("allowance", ignoreCase = true)

        for (f in fields) {
            val rawVal = try {
                f.isAccessible = true
                f.get(node)
            } catch (_: Throwable) {
                null
            } ?: continue

            val mappedKey = normalizeFieldName(f.name)
            if (mappedKey != null && (rawVal is Number || rawVal is String)) {
                namedOut[mappedKey] = rawVal.toString()
            }

            when (rawVal) {
                is Long -> {
                    val ts = QuotaTextParser.classifyFutureEpochTimestamp(rawVal, nowMs)
                    if (ts != null && objResetWindow == null) {
                        objResetWindow = ts
                    } else if (rawVal in 0L..10_000L && objInts.size < 6) {
                        objInts.add(rawVal.toInt())
                    }
                }
                is Int -> {
                    val ts = QuotaTextParser.classifyFutureEpochTimestamp(rawVal.toLong(), nowMs)
                    if (ts != null && objResetWindow == null) {
                        objResetWindow = ts
                    } else if (rawVal in 0..10_000 && objInts.size < 6) {
                        objInts.add(rawVal)
                    }
                }
                is Float -> {
                    if (rawVal.isFinite() && objDoubles.size < 4) {
                        objDoubles.add(rawVal.toDouble())
                    }
                }
                is Double -> {
                    if (rawVal.isFinite() && objDoubles.size < 4) {
                        objDoubles.add(rawVal)
                    }
                }
                is String -> {
                    // Skip arbitrary chat strings
                }
                else -> {
                    val instantEpoch = tryExtractTimeInstantEpochSec(rawVal)
                    if (instantEpoch != null && objResetWindow == null) {
                        val ts = QuotaTextParser.classifyFutureEpochTimestamp(instantEpoch, nowMs)
                        if (ts != null) objResetWindow = ts
                    } else {
                        walkObjectGraphRecursive(rawVal, depth + 1, visited, nowMs, namedOut, windowsOut)
                    }
                }
            }
        }

        if (objResetWindow != null && fields.size in 2..14) {
            var metric = "--"
            var conf = if (hasDomainClassHint) 3 else 1

            if (objInts.size >= 2) {
                for (i in objInts.indices) {
                    for (j in objInts.indices) {
                        if (i == j) continue
                        val a = objInts[i]
                        val b = objInts[j]
                        if (a in 0..b && b in setOf(5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 500, 1000, 2000)) {
                            val pct = ((a.toDouble() / b.toDouble()) * 100.0).roundToInt()
                            metric = QuotaTextParser.sanitizeQuotaMetric("$pct%")
                            conf += 2
                            break
                        }
                    }
                    if (metric != "--") break
                }
            }

            if (metric == "--") {
                val frac = objDoubles.firstOrNull { it in 0.001..1.0 }
                if (frac != null) {
                    metric = QuotaTextParser.formatFractionAsPercent(frac)
                    conf += 1
                } else if (objInts.size == 1 && objInts[0] in 0..100) {
                    metric = QuotaTextParser.sanitizeQuotaMetric("${objInts[0]}%")
                    conf += 1
                }
            }

            if (metric != "--") {
                windowsOut.add(
                    StructuralWindow(
                        kind = objResetWindow.first,
                        metric = metric,
                        reset = objResetWindow.second,
                        confidence = conf
                    )
                )
            }
        }
    }

    private fun isReactiveHolder(obj: Any): Boolean {
        if (obj is AtomicReference<*>) return true
        val n = obj.javaClass.name
        return n.contains("StateFlow") ||
            n.contains("LiveData") ||
            n.contains("SnapshotMutableState")
    }

    private fun unwrapReactiveValueSafe(holder: Any): Any? {
        if (holder is AtomicReference<*>) return holder.get()
        return try {
            val m = findMethodInHierarchy(holder.javaClass, "getValue")
            m?.invoke(holder)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryExtractTimeInstantEpochSec(obj: Any): Long? {
        val n = obj.javaClass.name
        return try {
            if (n == "java.time.Instant" || n.endsWith(".Instant")) {
                val m = obj.javaClass.getMethod("getEpochSecond")
                m.invoke(obj) as? Long
            } else {
                val fields = obj.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                if (fields.size in 1..3) {
                    val secField = fields.firstOrNull {
                        it.type == Long::class.javaPrimitiveType &&
                            (it.name == "seconds_" || it.name == "seconds" || fields.size == 2)
                    }
                    if (secField != null) {
                        secField.isAccessible = true
                        val v = secField.getLong(obj)
                        if (v in 1_700_000_000L..2_250_000_000L) v else null
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeFieldName(rawName: String): String? {
        val clean = rawName.trim().trimEnd('_').lowercase()
        if (clean.length < 4) return null
        return when {
            (clean.contains("week") || clean.contains("7d")) && clean.contains("reset") -> "weekly_reset"
            clean.contains("reset") && (clean.contains("time") || clean.contains("sec") || clean.contains("ms") || clean.contains("five") || clean.contains("5h") || clean.contains("quota")) -> "five_hour_reset"
            clean.contains("fivehour") || clean.contains("five_hour") || clean.contains("shortterm") || clean.contains("short_term") || clean.contains("5h") -> "five_hour_quota"
            clean.contains("weekly") || clean.contains("week_limit") || clean.contains("week_quota") || clean.contains("week_used") -> "weekly_limit"
            clean == "remainingquota" || clean == "remaining_quota" || clean == "quotaremaining" || clean == "promptsremaining" -> "remaining_quota"
            clean == "quotalimit" || clean == "quota_limit" || clean == "maxquota" || clean == "max_quota" -> "quota_limit"
            else -> null
        }
    }

    private fun collectInstanceFieldsSafe(cls: Class<*>): List<Field> {
        val list = ArrayList<Field>(12)
        var cur: Class<*>? = cls
        var depth = 0
        while (cur != null && cur != Any::class.java && depth < 3 && list.size < 24) {
            try {
                for (f in cur.declaredFields) {
                    if (!Modifier.isStatic(f.modifiers) && !f.isSynthetic) {
                        list.add(f)
                        if (list.size >= 24) break
                    }
                }
            } catch (_: Throwable) {
            }
            cur = try {
                cur.superclass
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        return list
    }

    private fun findFieldInHierarchy(cls: Class<*>, name: String): Field? {
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            try {
                val f = cur.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: Throwable) {
                cur = try {
                    cur.superclass
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String): Method? {
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            try {
                val m = cur.getDeclaredMethod(name)
                m.isAccessible = true
                return m
            } catch (_: Throwable) {
                cur = try {
                    cur.superclass
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }

    private fun installWebViewQuotaHooks(bridge: HookBridge) {
        try {
            hookMethodSafe(
                bridge = bridge,
                clazz = WebView::class.java,
                name = "setWebViewClient",
                paramTypes = arrayOf(WebViewClient::class.java),
                before = cb@{ _, args ->
                    val client = args.firstOrNull() as? WebViewClient ?: return@cb
                    hookConcreteWebViewClientClass(client.javaClass, bridge)
                }
            )
            hookMethodSafe(
                bridge = bridge,
                clazz = WebView::class.java,
                name = "loadUrl",
                paramTypes = arrayOf(String::class.java),
                after = cb@{ thisObj, args, _ ->
                    val wv = thisObj as? WebView ?: return@cb
                    val url = args.firstOrNull() as? String ?: return@cb
                    onWebViewPageEvent(wv, url, delayEvaluateMs = 1_200L)
                }
            )
            hookMethodSafe(
                bridge = bridge,
                clazz = WebView::class.java,
                name = "loadUrl",
                paramTypes = arrayOf(String::class.java, Map::class.java),
                after = cb@{ thisObj, args, _ ->
                    val wv = thisObj as? WebView ?: return@cb
                    val url = args.firstOrNull() as? String ?: return@cb
                    onWebViewPageEvent(wv, url, delayEvaluateMs = 1_200L)
                }
            )
            hookConcreteWebViewClientClass(WebViewClient::class.java, bridge)
        } catch (_: Throwable) {
        }
    }

    private fun hookConcreteWebViewClientClass(clientCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("webview_client", clientCls)) return
        bridge.hookAllMethodsByName(
            clazz = clientCls,
            methodName = "onPageFinished",
            after = cb@{ _, args, _ ->
                val wv = args.getOrNull(0) as? WebView ?: return@cb
                val url = args.getOrNull(1) as? String ?: return@cb
                onWebViewPageEvent(wv, url, delayEvaluateMs = 350L)
            }
        )
    }

    private fun onWebViewPageEvent(webView: WebView, rawUrl: String, delayEvaluateMs: Long) {
        if (!isForegroundActive.get()) return
        val lower = rawUrl.lowercase()
        if (!lower.contains("google.com") || isNonQuotaTelemetryUrl(rawUrl)) return

        try {
            val wvCookie = CookieManager.getInstance().getCookie(rawUrl)
            if (!wvCookie.isNullOrBlank()) {
                onDynamicHeadersIntercepted(
                    EphemeralSessionHeaders(url = rawUrl, cookie = wvCookie),
                    transport = "WebView"
                )
            }
        } catch (_: Throwable) {
        }

        if (isReplayableQuotaEndpoint(rawUrl, "GET")) {
            recordLearnedQuotaEndpoint(rawUrl, "GET", rpcHint = "WebView-Learned")
        }

        val isQuotaSurface = lower.contains("gemini.google.com") ||
            lower.contains("bard.google.com") ||
            lower.contains("one.google.com") ||
            lower.contains("myaccount.google.com") ||
            lower.contains("quota") ||
            lower.contains("usage")
        if (!isQuotaSurface) return

        val sess = activeSessionTag.get()
        postToMain(delayMs = delayEvaluateMs, requireForeground = true) {
            if (!isForegroundActive.get()) return@postToMain
            val js = """
                (function(){
                  try{
                    var t=(document.body&&document.body.innerText)?document.body.innerText:'';
                    var s='';
                    var sc=document.scripts||[];
                    for(var i=0;i<sc.length&&s.length<160000;i++){
                      var tx=sc[i].textContent||'';
                      if(tx.indexOf('AF_initDataCallback')>=0||tx.indexOf('WIZ_global_data')>=0||tx.indexOf('SNlM0e')>=0){
                        s+=tx+'\n';
                      }
                    }
                    return JSON.stringify({t:t.slice(0,16000),s:s});
                  }catch(e){return '';}
                })();
            """.trimIndent()
            try {
                webView.evaluateJavascript(js) { rawResult ->
                    if (!isForegroundActive.get() || rawResult.isNullOrBlank() || rawResult == "null") return@evaluateJavascript
                    submitForegroundWork {
                        try {
                            val decoded = JSONTokener(rawResult).nextValue() as? String ?: return@submitForegroundWork
                            if (decoded.isBlank() || decoded[0] != '{') return@submitForegroundWork
                            val obj = JSONObject(decoded)
                            val domText = obj.optString("t", "")
                            val scriptText = obj.optString("s", "")
                            if (scriptText.isNotBlank()) {
                                extractWizTokensFromHtml(scriptText)
                                val parsedScript = QuotaTextParser.parseHtmlOrJsonPayload(
                                    payload = scriptText,
                                    prev = getBaseSnapshotForSession(sess),
                                    source = "WebView-Script"
                                )
                                if (parsedScript != null && parsedScript.hasLiveData) {
                                    publishSnapshotForSession(parsedScript, sess)
                                }
                            }
                            if (domText.isNotBlank() && QuotaTextParser.fastHasQuotaKeyword(domText)) {
                                val parsedDom = QuotaTextParser.parseScreenQuotaTexts(
                                    text = domText,
                                    prev = getBaseSnapshotForSession(sess),
                                    source = "WebView-DOM"
                                )
                                if (parsedDom != null && parsedDom.hasLiveData) {
                                    publishSnapshotForSession(parsedDom, sess)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun scanForExhaustedBannerOnly(sessionTag: String) {
        if (!isForegroundActive.get()) return
        val activity = activeActivityRef.get().get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView ?: return
        val sb = StringBuilder(2048)
        collectViewTexts(decor, sb, 0)
        if (sb.isNotEmpty()) {
            val text = sb.toString()
            if (QuotaTextParser.fastHasQuotaKeyword(text)) {
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseScreenQuotaTexts(
                        text = text,
                        prev = getBaseSnapshotForSession(sessionTag),
                        source = "InApp-Screen"
                    )
                    if (snap != null) publishSnapshotForSession(snap, sessionTag)
                }
            }
        }
    }

    private fun mergeCookieStrings(existing: String?, incoming: String?): String? {
        if (existing.isNullOrBlank()) return incoming?.trim()?.takeIf { it.isNotEmpty() }
        if (incoming.isNullOrBlank()) return existing.trim().takeIf { it.isNotEmpty() }
        val merged = LinkedHashMap<String, String>(24)
        for (src in arrayOf(existing, incoming)) {
            for (seg in src.split(';')) {
                val eq = seg.indexOf('=')
                if (eq <= 0) continue
                val k = seg.substring(0, eq).trim()
                val v = seg.substring(eq + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    merged[k] = v
                }
            }
        }
        if (merged.isEmpty()) return null
        val sb = StringBuilder(256)
        for ((k, v) in merged) {
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(k).append('=').append(v)
        }
        return sb.toString()
    }

    private fun collectCookiesForGoogle(existingCookie: String?): String? {
        var combined = existingCookie
        try {
            val cm = CookieManager.getInstance()
            val urls = arrayOf(
                "https://gemini.google.com/app",
                "https://gemini.google.com",
                "https://bard.google.com",
                "https://www.google.com",
                ".google.com"
            )
            for (u in urls) {
                val c = cm.getCookie(u)
                if (!c.isNullOrBlank()) {
                    combined = mergeCookieStrings(combined, c)
                }
            }
        } catch (_: Throwable) {
        }
        return combined
    }

    private fun extractWizTokensFromHtml(html: String) {
        try {
            if (html.isEmpty()) return
            val snMatch = Regex(""""SNlM0e"\s*:\s*"([^"]+)"""").find(html)
            if (snMatch != null) {
                val tok = snMatch.groupValues.getOrNull(1)
                if (!tok.isNullOrBlank()) cachedXsrfToken = tok
            }
            val fsidMatch = Regex(""""FdrFJe"\s*:\s*"([^"]+)"""").find(html)
            if (fsidMatch != null) {
                val fsid = fsidMatch.groupValues.getOrNull(1)
                if (!fsid.isNullOrBlank()) cachedFsidToken = fsid
            }
            val blMatch = Regex(""""cfb2h"\s*:\s*"([^"]+)"""").find(html)
            if (blMatch != null) {
                val bl = blMatch.groupValues.getOrNull(1)
                if (!bl.isNullOrBlank()) cachedBuildLabel = bl
            }
        } catch (_: Throwable) {
        }
    }

    private fun readBoundedHttpResponse(conn: HttpURLConnection, maxChars: Int): String? {
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val stream = conn.inputStream ?: return null
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val sb = StringBuilder(minOf(65_536, maxChars))
                val buf = CharArray(4096)
                var r: Int
                while (reader.read(buf).also { r = it } != -1 && sb.length < maxChars) {
                    val allow = minOf(r, maxChars - sb.length)
                    sb.append(buf, 0, allow)
                }
                sb.toString()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchActiveQuotaOverHttp(sessionTag: String, reason: RefreshReason) {
        if (!isForegroundActive.get()) return
        val now = System.currentTimeMillis()
        val isManual = reason == RefreshReason.MANUAL_TAP || reason == RefreshReason.ACCOUNT_SWITCH
        val minInterval = if (isManual) 2_000L else 10_000L
        if (now - lastHttpFetchMs.get() < minInterval) return
        lastHttpFetchMs.set(now)

        val dynState = activeDynamicSession.get()
        val authHeader = dynState.authorizationHeader
        val authUser = dynState.authUserHeader?.takeIf { it.isNotBlank() } ?: "0"
        val cookieHeader = collectCookiesForGoogle(dynState.cookieHeader)

        // If cookie gained via CookieManager confirms or upgrades session identity, sync it seamlessly
        if (!cookieHeader.isNullOrBlank() && dynState.cookieHeader.isNullOrBlank()) {
            onDynamicHeadersIntercepted(
                EphemeralSessionHeaders(
                    url = "https://gemini.google.com/app",
                    authorization = authHeader,
                    cookie = cookieHeader,
                    authUser = authUser
                ),
                transport = "CookieMgr"
            )
        }

        // If neither cookie nor OAuth Bearer token is available yet, apply rolling estimation if queries exist
        if (cookieHeader.isNullOrBlank() && authHeader.isNullOrBlank()) {
            applySessionQueryEstimationIfNeeded(sessionTag)
            return
        }

        var fetchedSnapshot: QuotaHudView.Snapshot? = null
        val mobileUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // 0. Replay learned GET quota endpoints captured from Gemini's own UI traffic
        val learnedCopy: List<LearnedEndpoint> = synchronized(learnedEndpointsLock) {
            ArrayList(learnedQuotaEndpoints.values)
        }
        for (ep in learnedCopy.asReversed()) {
            if (!isForegroundActive.get() || fetchedSnapshot != null) break
            if (!ep.method.equals("GET", ignoreCase = true)) continue
            try {
                val conn = (URL(ep.url).openConnection() as? HttpURLConnection) ?: continue
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("User-Agent", mobileUa)
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("X-Goog-AuthUser", authUser)
                if (!cookieHeader.isNullOrBlank()) conn.setRequestProperty("Cookie", cookieHeader)
                if (!authHeader.isNullOrBlank()) conn.setRequestProperty("Authorization", authHeader)

                val body = readBoundedHttpResponse(conn, 262_144)
                conn.disconnect()
                if (!body.isNullOrBlank()) {
                    extractWizTokensFromHtml(body)
                    val parsed = QuotaTextParser.parseHtmlOrJsonPayload(
                        payload = body,
                        prev = getBaseSnapshotForSession(sessionTag),
                        source = ep.rpcHint ?: "Learned-HTTP"
                    )
                    if (parsed != null && parsed.hasLiveData) {
                        fetchedSnapshot = parsed
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 1. Fetch Gemini Web app landing page up to 1.5 MB: captures SNlM0e/FdrFJe/cfb2h & full AF_initDataCallback blobs
        if (fetchedSnapshot == null && isForegroundActive.get()) {
            val primaryPath = if (authUser != "0") "https://gemini.google.com/u/$authUser/app?hl=zh-CN" else "https://gemini.google.com/app?hl=zh-CN"
            val landingCandidates = arrayOf(primaryPath, "https://gemini.google.com/app?hl=en")
            for (pageUrl in landingCandidates) {
                if (fetchedSnapshot != null || !isForegroundActive.get()) break
                try {
                    val conn = (URL(pageUrl).openConnection() as? HttpURLConnection) ?: continue
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 7_000
                    conn.readTimeout = 8_000
                    conn.setRequestProperty("User-Agent", mobileUa)
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.setRequestProperty("X-Goog-AuthUser", authUser)
                    if (!cookieHeader.isNullOrBlank()) conn.setRequestProperty("Cookie", cookieHeader)
                    if (!authHeader.isNullOrBlank()) conn.setRequestProperty("Authorization", authHeader)

                    val html = readBoundedHttpResponse(conn, 1_572_864)
                    conn.disconnect()
                    if (!html.isNullOrBlank()) {
                        extractWizTokensFromHtml(html)
                        val parsed = QuotaTextParser.parseHtmlOrJsonPayload(
                            payload = html,
                            prev = getBaseSnapshotForSession(sessionTag),
                            source = "Active-Web"
                        )
                        if (parsed != null && parsed.hasLiveData) {
                            fetchedSnapshot = parsed
                        }
                        if (cachedXsrfToken != null) break
                    }
                } catch (t: Throwable) {
                    safeLog(activeBridgeRef.get(), "Active Web fetch attempt: ${t.javaClass.simpleName} (${t.message})")
                }
            }
        }

        // 2. If not fetched from landing HTML, invoke BardChatUi batchexecute RPCs with extracted SNlM0e XSRF token
        if (fetchedSnapshot == null && !cookieHeader.isNullOrBlank() && isForegroundActive.get()) {
            val rpcGroups = arrayOf("hOoQ4d", "o4g7id", "MaZiqc")
            for (rpcId in rpcGroups) {
                if (fetchedSnapshot != null || !isForegroundActive.get()) break
                try {
                    val urlBuilder = StringBuilder("https://gemini.google.com")
                    if (authUser != "0") {
                        urlBuilder.append("/u/").append(URLEncoder.encode(authUser, "UTF-8"))
                    }
                    urlBuilder.append("/_/BardChatUi/data/batchexecute?rpcids=").append(rpcId)
                    cachedFsidToken?.let { urlBuilder.append("&f.sid=").append(URLEncoder.encode(it, "UTF-8")) }
                    cachedBuildLabel?.let { urlBuilder.append("&bl=").append(URLEncoder.encode(it, "UTF-8")) }
                    urlBuilder.append("&hl=zh-CN&source-path=%2Fapp&rt=c")

                    val conn = (URL(urlBuilder.toString()).openConnection() as? HttpURLConnection) ?: continue
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 6_000
                    conn.readTimeout = 6_000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    conn.setRequestProperty("User-Agent", mobileUa)
                    conn.setRequestProperty("Origin", "https://gemini.google.com")
                    conn.setRequestProperty("Referer", "https://gemini.google.com/app")
                    conn.setRequestProperty("X-Same-Domain", "1")
                    conn.setRequestProperty("X-Goog-AuthUser", authUser)
                    conn.setRequestProperty("Cookie", cookieHeader)
                    if (!authHeader.isNullOrBlank()) conn.setRequestProperty("Authorization", authHeader)

                    val reqJson = "[[[\"$rpcId\",\"[]\",null,\"generic\"]]]"
                    val formBody = StringBuilder("f.req=").append(URLEncoder.encode(reqJson, "UTF-8"))
                    val xsrf = cachedXsrfToken
                    if (!xsrf.isNullOrBlank()) {
                        formBody.append("&at=").append(URLEncoder.encode(xsrf, "UTF-8"))
                    }
                    formBody.append('&')

                    OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { out ->
                        out.write(formBody.toString())
                        out.flush()
                    }

                    val body = readBoundedHttpResponse(conn, 262_144)
                    conn.disconnect()
                    if (!body.isNullOrBlank()) {
                        val parsed = QuotaTextParser.parseHtmlOrJsonPayload(
                            payload = body,
                            prev = getBaseSnapshotForSession(sessionTag),
                            source = "Active-RPC-$rpcId"
                        )
                        if (parsed != null && parsed.hasLiveData) {
                            fetchedSnapshot = parsed
                        }
                    }
                } catch (t: Throwable) {
                    safeLog(activeBridgeRef.get(), "Active RPC ($rpcId) attempt: ${t.javaClass.simpleName} (${t.message})")
                }
            }
        }

        if (fetchedSnapshot != null && fetchedSnapshot.hasLiveData) {
            safeLog(
                activeBridgeRef.get(),
                "Active HTTP quota resolved -> 5h=${fetchedSnapshot.fiveHourQuota} (${fetchedSnapshot.fiveHourReset}), Weekly=${fetchedSnapshot.weeklyLimit} (${fetchedSnapshot.weeklyReset})"
            )
            publishSnapshotForSession(fetchedSnapshot, sessionTag)
        } else {
            applySessionQueryEstimationIfNeeded(sessionTag)
        }
    }

    private fun applySessionQueryEstimationIfNeeded(sessionTag: String) {
        val cur = getBaseSnapshotForSession(sessionTag)
        if (cur.hasLiveData && cur.source != "SessionEstimate") return

        val now = System.currentTimeMillis()
        val queryTimes: List<Long>
        synchronized(rollingQueryTimestamps) {
            val cutoff = now - 5L * 3600L * 1000L
            val it = rollingQueryTimestamps.iterator()
            while (it.hasNext()) {
                if (it.next() < cutoff) it.remove()
            }
            queryTimes = ArrayList(rollingQueryTimestamps)
        }

        if (queryTimes.isEmpty()) return

        // 5-hour Gemini window: estimate based on 50 prompts standard cap
        val count = queryTimes.size
        val oldest = queryTimes.minOrNull() ?: now
        val resetSec = maxOf(60L, (oldest + 5L * 3600L * 1000L - now) / 1000L)
        val resetToken = QuotaTextParser.formatDurationSeconds(resetSec)
        val pct = minOf(100, (count * 100) / 50)
        val metric = "$pct%"

        val estSnap = cur.copy(
            fiveHourQuota = metric,
            fiveHourReset = resetToken,
            source = "SessionEstimate"
        ).sanitized()

        if (estSnap.hasLiveData) {
            publishSnapshotForSession(estSnap, sessionTag)
        }
    }

    private fun collectViewTexts(v: View, out: StringBuilder, depth: Int) {
        if (depth > 20 || out.length > 12_288) return
        if (v.tag == QuotaHudView.HUD_VIEW_TAG) return
        if (v.visibility != View.VISIBLE) return
        val viewTagStr = v.tag as? String
        if (viewTagStr != null && viewTagStr.startsWith("bard_quota_hud")) return

        if (v is TextView) {
            val t = v.text
            if (!t.isNullOrBlank() && t.length <= 240) {
                out.append(t).append('\n')
            }
        } else if (v.javaClass.name.contains("ComposeView", ignoreCase = true) ||
            v.javaClass.name.contains("AndroidComposeView", ignoreCase = true)
        ) {
            collectComposeAccessibilityTexts(v, out)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                collectViewTexts(child, out, depth + 1)
            }
        }
    }

    private fun collectComposeAccessibilityTexts(rootView: View, out: StringBuilder) {
        try {
            val rootNode = rootView.createAccessibilityNodeInfo() ?: return
            val count = intArrayOf(0)
            walkAccessibilityNodeSafe(rootNode, out, 0, count)
        } catch (_: Throwable) {
        }
    }

    private fun walkAccessibilityNodeSafe(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        visitedCount: IntArray
    ) {
        try {
            if (depth > 18 || visitedCount[0] > 160 || out.length > 12_288) return
            visitedCount[0]++

            val resId = try {
                node.viewIdResourceName ?: ""
            } catch (_: Throwable) {
                ""
            }
            if (resId.contains("bard_quota_hud")) return

            val txt = node.text?.toString()?.trim()
            if (!txt.isNullOrEmpty() && txt.length <= 240) {
                if (!txt.contains("bard_quota_hud")) {
                    out.append(txt).append('\n')
                }
            }
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty() && desc != txt && desc.length <= 240) {
                out.append(desc).append('\n')
            }

            val childTotal = minOf(node.childCount, 32)
            for (i in 0 until childTotal) {
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                walkAccessibilityNodeSafe(child, out, depth + 1, visitedCount)
            }
        } catch (_: Throwable) {
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                try {
                    node.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // =========================================================================
    // Session-Scoped Quota Store & Foreground-Only HUD Rendering
    // =========================================================================

    private fun getBaseSnapshotForSession(sessionTag: String): QuotaHudView.Snapshot {
        synchronized(sessionSnapshotsLock) {
            if (sessionTag == activeSessionTag.get()) {
                return latestSnapshot.get().sanitized()
            }
            return perSessionSnapshots[sessionTag]?.sanitized() ?: QuotaHudView.Snapshot()
        }
    }

    private fun publishSnapshotForSession(snapshot: QuotaHudView.Snapshot, sessionTag: String) {
        val clean = snapshot.sanitized()
        if (!clean.hasLiveData) return

        var shouldUpdateActiveHud = false
        synchronized(sessionSnapshotsLock) {
            val prevForSession = if (sessionTag == activeSessionTag.get()) {
                latestSnapshot.get().sanitized()
            } else {
                perSessionSnapshots[sessionTag]?.sanitized() ?: QuotaHudView.Snapshot()
            }
            // Never let synthetic SessionEstimate overwrite resolved real quota metrics
            if (clean.source == "SessionEstimate" &&
                prevForSession.hasLiveData &&
                prevForSession.source != "SessionEstimate"
            ) {
                return
            }
            if (clean == prevForSession) return

            if (sessionTag != DEFAULT_SESSION_TAG) {
                perSessionSnapshots[sessionTag] = clean
            }
            if (sessionTag == activeSessionTag.get()) {
                latestSnapshot.set(clean)
                shouldUpdateActiveHud = true
            }
        }

        if (!shouldUpdateActiveHud) return

        saveSanitizedHostMirror(
            clean,
            currentLang.get(),
            currentTheme.get(),
            sessionTag,
            isStrongCookieTag = confirmedIdentityStrength >= IdentityStrength.COOKIE_SID
        )
        if (isForegroundActive.get()) {
            refreshHudOnUiThread()
            val ctx = appContext
            if (ctx != null) {
                submitForegroundWork {
                    val merged = QuotaSyncProvider.publishSessionQuota(
                        context = ctx,
                        snapshot = clean,
                        sessionId = sessionTag,
                        replaceSession = false
                    )
                    applyUnifiedState(merged)
                }
            }
        }
    }

    private fun loadSanitizedHostMirror(ctx: Context?) {
        if (ctx == null) return
        try {
            val prefs = ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
            val langCode = prefs.getString("ui_lang", null)
            val themeCode = prefs.getString("ui_theme", null)
            val savedSession = prefs.getString("session_id", null)
            val savedStrong = prefs.getBoolean("session_strong", false)
            if (langCode != null) currentLang.set(QuotaHudView.Lang.fromCode(langCode))
            if (themeCode != null) currentTheme.set(QuotaHudView.ThemeMode.fromCode(themeCode))
            if (!savedSession.isNullOrBlank()) {
                activeSessionTag.set(savedSession)
                persistedSessionWasStrong = savedStrong
            }

            val clean = QuotaHudView.Snapshot(
                fiveHourQuota = prefs.getString("five", "--") ?: "--",
                fiveHourReset = prefs.getString("five_reset", "--") ?: "--",
                weeklyLimit = prefs.getString("week", "--") ?: "--",
                weeklyReset = prefs.getString("week_reset", "--") ?: "--",
                source = prefs.getString("src", "InAppCache") ?: "InAppCache"
            ).sanitized()

            if (clean.hasLiveData) {
                synchronized(sessionSnapshotsLock) {
                    latestSnapshot.set(clean)
                    val s = activeSessionTag.get()
                    if (s != DEFAULT_SESSION_TAG) {
                        perSessionSnapshots[s] = clean
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveSanitizedHostMirror(
        snapshot: QuotaHudView.Snapshot,
        lang: QuotaHudView.Lang,
        theme: QuotaHudView.ThemeMode,
        sessionTag: String,
        isStrongCookieTag: Boolean = persistedSessionWasStrong
    ) {
        val ctx = appContext ?: return
        val clean = snapshot.sanitized()
        try {
            ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("five", clean.fiveHourQuota)
                .putString("five_reset", clean.fiveHourReset)
                .putString("week", clean.weeklyLimit)
                .putString("week_reset", clean.weeklyReset)
                .putString("src", clean.source)
                .putString("session_id", sessionTag)
                .putBoolean("session_strong", isStrongCookieTag)
                .putString("ui_lang", lang.code)
                .putString("ui_theme", theme.code)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun registerForegroundObserver(ctx: Context) {
        if (!observerRegistered.compareAndSet(false, true)) return
        providerObserver = QuotaSyncProvider.registerStateObserver(ctx) {
            if (isForegroundActive.get()) {
                submitForegroundWork {
                    val state = QuotaSyncProvider.readUnifiedState(ctx)
                    applyUnifiedState(state)
                }
            }
        }
    }

    private fun unregisterForegroundObserver(ctx: Context) {
        if (!observerRegistered.compareAndSet(true, false)) return
        QuotaSyncProvider.unregisterStateObserver(ctx, providerObserver)
        providerObserver = null
    }

    private fun syncForegroundStateWithProviderAsync(ctx: Context) {
        submitForegroundWork {
            val localSnap = latestSnapshot.get().sanitized()
            val sess = activeSessionTag.get()
            val state = QuotaSyncProvider.sendHeartbeatAndFetchState(
                context = ctx,
                pkg = hostPackageName,
                proc = hostProcessName,
                bridge = activeBridgeName,
                isApi102 = isModernApi102Bridge,
                localSnapshot = localSnap,
                sessionId = sess
            )
            applyUnifiedState(state)
        }
    }

    private fun applyUnifiedState(state: QuotaSyncProvider.UnifiedState?) {
        if (state == null || !isForegroundActive.get()) return
        var uiChanged = false

        if (currentLang.get() != state.lang) {
            currentLang.set(state.lang)
            uiChanged = true
        }
        if (currentTheme.get() != state.theme) {
            currentTheme.set(state.theme)
            uiChanged = true
        }

        // Only adopt snapshot from QuotaSyncProvider if it belongs to the same active session
        // (prevents overwriting a newly switched Google account with stale cached values).
        val curSess = activeSessionTag.get()
        val sameOrDefaultSession = state.sessionId.isBlank() ||
            curSess == DEFAULT_SESSION_TAG ||
            state.sessionId == curSess

        if (sameOrDefaultSession) {
            val cleanSnap = state.snapshot.sanitized()
            val curSnap = latestSnapshot.get().sanitized()
            val canUpgradeFromProvider = cleanSnap.hasLiveData &&
                curSnap != cleanSnap &&
                (!curSnap.hasLiveData || (curSnap.source == "SessionEstimate" && cleanSnap.source != "SessionEstimate"))
            if (canUpgradeFromProvider) {
                latestSnapshot.set(cleanSnap)
                uiChanged = true
            }
        }

        if (uiChanged) {
            saveSanitizedHostMirror(latestSnapshot.get(), currentLang.get(), currentTheme.get(), curSess)
            refreshHudOnUiThread()
        }
    }

    private fun postToMain(
        delayMs: Long = 0L,
        requireForeground: Boolean = true,
        action: () -> Unit
    ) {
        if (requireForeground && !isForegroundActive.get()) return
        try {
            val looper = Looper.getMainLooper() ?: return
            val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
            val runnable = Runnable {
                if (requireForeground && !isForegroundActive.get()) return@Runnable
                try {
                    action()
                } catch (_: Throwable) {
                }
            }
            if (delayMs > 0L) {
                handler.postDelayed(runnable, delayMs)
            } else if (Looper.myLooper() == looper) {
                runnable.run()
            } else {
                handler.post(runnable)
            }
        } catch (_: Throwable) {
        }
    }

    private fun refreshHudOnUiThread() {
        if (!isForegroundActive.get()) return
        postToMain(requireForeground = true) {
            val hud = activeHudRef.get().get()
            if (hud != null && hud.isAttachedToWindow) {
                hud.visibility = View.VISIBLE
                hud.alpha = 1f
                hud.applyPreferences(currentLang.get(), currentTheme.get())
                hud.updateSnapshot(latestSnapshot.get().sanitized())
                hud.bringToFront()
            } else {
                val act = activeActivityRef.get().get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    attachOrUpdateOverlay(act)
                }
            }
        }
    }

    private fun attachOrUpdateOverlay(activity: Activity) {
        if (!isForegroundActive.get()) return
        postToMain(requireForeground = true) {
            if (!isForegroundActive.get() || activity.isFinishing || activity.isDestroyed) return@postToMain
            val decorGroup = activity.window?.decorView as? ViewGroup ?: return@postToMain

            var hud = decorGroup.findViewWithTag<View>(QuotaHudView.HUD_VIEW_TAG) as? QuotaHudView
            if (hud == null) {
                hud = QuotaHudView(
                    context = activity,
                    onRefreshClick = {
                        triggerLightweightQuotaRefresh(RefreshReason.MANUAL_TAP, delayMs = 0L)
                    },
                    onOpenUsageClick = {
                        QuotaHudView.openModuleSettings(activity)
                    },
                    draggableInFrame = true
                )

                // Ensure compatibility with FrameLayout or generic ViewGroup layouts
                val topMarginPx = QuotaHudView.dp(activity, 52f)
                val rightMarginPx = QuotaHudView.dp(activity, 10f)

                val lp: ViewGroup.LayoutParams = if (decorGroup is FrameLayout) {
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply {
                        topMargin = topMarginPx
                        rightMargin = rightMarginPx
                    }
                } else {
                    ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = topMarginPx
                        rightMargin = rightMarginPx
                    }
                }

                try {
                    decorGroup.addView(hud, lp)
                } catch (_: Throwable) {
                    try {
                        decorGroup.addView(hud)
                    } catch (_: Throwable) {
                    }
                }
            } else if (hud.parent !== decorGroup) {
                // If view was detached or attached elsewhere, re-add cleanly
                try {
                    (hud.parent as? ViewGroup)?.removeView(hud)
                    decorGroup.addView(hud)
                } catch (_: Throwable) {
                }
            }

            hud.visibility = View.VISIBLE
            hud.alpha = 1f
            hud.applyPreferences(currentLang.get(), currentTheme.get())
            hud.updateSnapshot(latestSnapshot.get().sanitized())
            hud.bringToFront()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                hud.translationZ = QuotaHudView.dp(activity, 12f).toFloat()
            }
            activeHudRef.set(WeakReference(hud))
        }
    }
}
