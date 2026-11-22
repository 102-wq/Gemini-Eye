package com.example.bardquotahook

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Base64
import dalvik.system.BaseDexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
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
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale
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
            val seenSignatures = HashSet<String>(8)
            var cur: Class<*>? = clazz
            var depth = 0
            try {
                while (cur != null && cur != Any::class.java && depth < 8) {
                    try {
                        for (m in cur.declaredMethods) {
                            if (m.name != methodName || Modifier.isAbstract(m.modifiers)) continue
                            val sig = buildString(48) {
                                append(m.name).append('(')
                                val pTypes = m.parameterTypes
                                for (i in pTypes.indices) {
                                    if (i > 0) append(',')
                                    append(pTypes[i].name)
                                }
                                append(')')
                            }
                            if (!seenSignatures.add(sig)) continue
                            if (!QuotaCoreEngine.markExecutableOnce(m)) continue
                            try {
                                m.isAccessible = true
                                if (hookExecutable(m, before, after) != null) count++
                            } catch (_: Throwable) {
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
    private const val MAX_STREAM_ACCUM_BYTES = 262_144
    private const val MAX_CHUNK_COPY_BYTES = 65_536
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
    private val hasCompletedLiveFetchThisWake = AtomicBoolean(false)
    private val localStorageScannedThisWake = AtomicBoolean(false)

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
        val contentType: String? = null,
        val bodyBase64: String? = null,
        val extraHeaders: Map<String, String> = emptyMap(),
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    // Dynamic Session & UI State
    private val activeSessionTag = AtomicReference(DEFAULT_SESSION_TAG)
    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())
    private val currentLang = AtomicReference(QuotaSyncProvider.defaultLangForDevice())
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
    // In-memory cache of last verified non-zero quota per session for Protobuf zero-debounce fallback
    private val cachedNonZeroSnapshots = object : LinkedHashMap<String, QuotaHudView.Snapshot>(MAX_TRACKED_ACCOUNTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QuotaHudView.Snapshot>?): Boolean {
            return size > MAX_TRACKED_ACCOUNTS
        }
    }
    private val cachedNonZeroTimestamps = HashMap<String, Long>()
    private val pbZeroDebounceHits = HashMap<String, Int>()
    private val lastPbZeroWarnAtMs = HashMap<String, Long>()

    // Bounded learned quota endpoints discovered during foreground inspection (persisted without credentials)
    private val learnedEndpointsLock = Any()
    private val learnedQuotaEndpoints = object : LinkedHashMap<String, LearnedEndpoint>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LearnedEndpoint>?): Boolean {
            return size > 12
        }
    }

    // Ephemeral foreground-only outgoing request session header contexts (cleared on background sleep)
    private data class EphemeralSessionHeaders(
        var url: String? = null,
        var method: String? = null,
        var authorization: String? = null,
        var cookie: String? = null,
        var authUser: String? = null,
        var pageId: String? = null,
        var contentType: String? = null,
        var bodyBytes: ByteArray? = null,
        var isBidirectional: Boolean = false,
        val extraHeaders: MutableMap<String, String> = LinkedHashMap()
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
        val contentType: String? = null,
        val bodyBytes: ByteArray? = null,
        val isBidirectional: Boolean = false,
        val extraHeaders: Map<String, String> = emptyMap(),
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private data class GrpcReplayTarget(
        val fullMethod: String,
        val channelRef: WeakReference<Any>,
        val methodDescRef: WeakReference<Any>,
        val callOptionsRef: WeakReference<Any>?,
        val requestBytes: ByteArray?,
        val authorityHost: String?,
        val updatedAtMs: Long = System.currentTimeMillis()
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

    // Scoped in-memory Bearer token cache keyed by host/service (never persisted to disk; wiped on sleep/switch)
    private val perHostAuthorizations = ConcurrentHashMap<String, String>()

    private val builderHeaderMap = Collections.synchronizedMap(WeakHashMap<Any, EphemeralSessionHeaders>())
    private val uploadProviderToBuilder = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val requestContextMap = ConcurrentHashMap<Int, BoundRequestContext>()
    private val cronetRollingStreams = ConcurrentHashMap<Int, RollingStreamBuffer>()
    private val grpcCallMetaMap = ConcurrentHashMap<Int, Triple<WeakReference<Any>?, WeakReference<Any>?, WeakReference<Any>?>>()
    private val grpcCallToListenerId = ConcurrentHashMap<Int, Int>()
    private val learnedGrpcTargets = ConcurrentHashMap<String, GrpcReplayTarget>()

    private val activeCronetEngines = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
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
    private val hookedExecutables = ConcurrentHashMap.newKeySet<Executable>()
    private val cronetPreReadPos = ConcurrentHashMap<Int, Int>()
    private val classCandidateCache = ConcurrentHashMap<Class<*>, Boolean>()

    internal fun markExecutableOnce(executable: Executable): Boolean {
        return hookedExecutables.add(executable)
    }

    private data class OutboundProtocolFrameTrace(
        val timestampMs: Long,
        val transport: String,
        val target: String,
        val teHeader: String,
        val grpc5bHex: String,
        val grpc5bDetail: String,
        val extraSummary: String
    )

    private val outboundFramesLock = Any()
    private val recentOutboundFrames = ArrayList<OutboundProtocolFrameTrace>(16)
    private val lastHandledForceScanSeq = AtomicLong(0L)
    @Volatile private var activeSettingsTraceRef: WeakReference<TextView> = WeakReference(null)
    @Volatile private var activeSettingsQuotaRef: WeakReference<TextView> = WeakReference(null)

    private val lastRefreshMs = AtomicLong(0L)
    private val lastFullProtocolScanMs = AtomicLong(0L)
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
        val sessionTag: String,
        val isQuotaCandidate: Boolean = false
    ) {
        private val out = ByteArrayOutputStream(4096)
        val createdAtMs: Long = System.currentTimeMillis()
        @Volatile var resolvedQuotaSnapshot: Boolean = false

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

        @Synchronized
        fun snapshotAllBytes(): ByteArray = out.toByteArray()
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

    private fun warnLog(bridge: HookBridge?, msg: String) {
        try {
            bridge?.log("[BardQuota][WARN] $msg")
        } catch (_: Throwable) {
        }
        try {
            Log.w(LOG_TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun formatTimestampMs(ms: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
        } catch (_: Throwable) {
            ms.toString()
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

        // Foreground Full-Protocol Snapshot Scan Listener: when Gemini window gains active focus
        // while still missing live quota (e.g., user stayed on chat home without opening usage page),
        // force a bounded full-protocol sweep across DataStore, ViewModel/Repo, WebViews & H2/gRPC replay.
        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onWindowFocusChanged",
            paramTypes = arrayOf(Boolean::class.javaPrimitiveType!!),
            after = cb@{ thisObj, args, _ ->
                val hasFocus = args.firstOrNull() as? Boolean ?: false
                if (!hasFocus || !isForegroundActive.get()) return@cb
                val activity = thisObj as? Activity ?: return@cb
                if (!shouldDisplayHudInActivity(activity)) return@cb
                val curSnap = latestSnapshot.get().sanitized()
                val sess = activeSessionTag.get()
                postToMain(delayMs = 450L, requireForeground = true) {
                    if (!isForegroundActive.get()) return@postToMain
                    scanVisibleUiForQuotaTexts(sess, allowFullScreenParse = true)
                    val refreshed = latestSnapshot.get().sanitized()
                    if (!hasCompletedLiveFetchThisWake.get() || !refreshed.hasLiveData || refreshed.source == "SessionEstimate") {
                        triggerForegroundFullProtocolSnapshotScan(
                            reason = RefreshReason.APP_OPEN,
                            bypassThrottle = false
                        )
                    }
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
                    scanVisibleUiForQuotaTexts(sess, allowFullScreenParse = true)
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
                    clearTransientBuffers(wipeCredentials = true)
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
            triggerLightweightQuotaRefresh(RefreshReason.APP_OPEN, delayMs = 350L, bypassThrottle = true)
            // Secondary cold-launch check: once cookies / ViewModels settle (~1.85s), verify live fetch ran
            postToMain(delayMs = 1_850L, requireForeground = true) {
                val cur = latestSnapshot.get().sanitized()
                if (!hasCompletedLiveFetchThisWake.get() || !cur.hasLiveData || cur.source == "SessionEstimate") {
                    lastHttpFetchMs.set(0L)
                    triggerLightweightQuotaRefresh(RefreshReason.APP_OPEN, delayMs = 0L, bypassThrottle = true)
                } else {
                    refreshHudOnUiThread()
                }
            }
            // Final bounded cold-launch settle check (~3.8s) in case GMS Dynamite Cronet/cookies initialized slowly
            postToMain(delayMs = 3_800L, requireForeground = true) {
                val cur = latestSnapshot.get().sanitized()
                if (!hasCompletedLiveFetchThisWake.get() || !cur.hasLiveData || cur.source == "SessionEstimate") {
                    lastHttpFetchMs.set(0L)
                    triggerLightweightQuotaRefresh(RefreshReason.APP_OPEN, delayMs = 0L, bypassThrottle = true)
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
        hasCompletedLiveFetchThisWake.set(false)
        localStorageScannedThisWake.set(false)
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

    fun forceSleepForHotReload(resetHookTracking: Boolean = false) {
        activeStartedActivities.clear()
        startedActivityCount.set(0)
        enterBackgroundSleep(activeActivityRef.get().get())
        clearTransientBuffers(wipeCredentials = true)
        if (resetHookTracking) {
            hookedExecutables.clear()
            hookedDynamicClasses.clear()
            synchronized(hookedClassLoaders) {
                hookedClassLoaders.clear()
            }
        }
    }

    private fun clearTransientBuffers(wipeCredentials: Boolean = false) {
        try {
            cronetRollingStreams.clear()
            cronetPreReadPos.clear()
            requestContextMap.clear()
            grpcCallMetaMap.clear()
            grpcCallToListenerId.clear()
            synchronized(builderHeaderMap) {
                builderHeaderMap.clear()
            }
            synchronized(uploadProviderToBuilder) {
                uploadProviderToBuilder.clear()
            }
            if (wipeCredentials) {
                cachedXsrfToken = null
                cachedFsidToken = null
                cachedBuildLabel = null
                perHostAuthorizations.clear()
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
            }
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

    private fun isSafeTechnicalHeader(lowerName: String): Boolean {
        return lowerName == "content-type" ||
            lowerName == "accept" ||
            lowerName == "te" ||
            lowerName.startsWith("grpc-") ||
            lowerName == "x-goog-api-key" ||
            lowerName == "x-android-package" ||
            lowerName == "x-android-cert" ||
            lowerName == "x-client-data" ||
            lowerName == "x-same-domain" ||
            lowerName == "x-goog-fieldmask"
    }

    private fun describeGrpc5bFrameHeader(bytes: ByteArray?): Pair<String, String> {
        if (bytes == null || bytes.isEmpty()) {
            return Pair("-- (no body)", "empty/header-only")
        }
        val b0 = bytes[0].toInt() and 0xFF
        if (bytes.size >= 5 && (b0 == 0x00 || b0 == 0x01)) {
            val frameLen = ((bytes[1].toInt() and 0xFF) shl 24) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 8) or
                (bytes[4].toInt() and 0xFF)
            if (frameLen in 0..2_097_152) {
                val hex = String.format(
                    Locale.US,
                    "%02X %02X %02X %02X %02X",
                    b0,
                    bytes[1].toInt() and 0xFF,
                    bytes[2].toInt() and 0xFF,
                    bytes[3].toInt() and 0xFF,
                    bytes[4].toInt() and 0xFF
                )
                val comp = if (b0 == 0x01) "GZIP(0x01)" else "Identity(0x00)"
                return Pair("[$hex]", "Comp=$comp, FrameLen=${frameLen}B, Total=${bytes.size}B")
            }
        }
        val len = bytes.size
        val syntheticHex = String.format(
            Locale.US,
            "00 %02X %02X %02X %02X",
            (len ushr 24) and 0xFF,
            (len ushr 16) and 0xFF,
            (len ushr 8) and 0xFF,
            len and 0xFF
        )
        return Pair("[$syntheticHex*]", "Unframed/RawPayload=${len}B (Derived 5B Prefix)")
    }

    private fun recordOutboundProtocolFrameTrace(
        transport: String,
        target: String?,
        teHeader: String?,
        bodyBytes: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val cleanTarget = target?.takeIf { it.isNotBlank() } ?: return
        if (isNonQuotaTelemetryUrl(cleanTarget)) return
        val teVal = teHeader?.takeIf { it.isNotBlank() }
            ?: extraHeaders.entries.firstOrNull { it.key.equals("te", ignoreCase = true) }?.value
            ?: if (transport.contains("gRPC", ignoreCase = true) ||
                cleanTarget.contains("/\$rpc/") ||
                cleanTarget.startsWith("https://grpc/")
            ) {
                "trailers"
            } else {
                "trailers (H2)"
            }
        val ct = extraHeaders.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
            ?: if (transport.contains("gRPC", ignoreCase = true)) "application/grpc" else ""
        val (hex5b, desc5b) = describeGrpc5bFrameHeader(bodyBytes)
        val extra = if (ct.isNotBlank()) "ct=$ct" else transport
        val entry = OutboundProtocolFrameTrace(
            timestampMs = System.currentTimeMillis(),
            transport = transport,
            target = cleanTarget,
            teHeader = teVal,
            grpc5bHex = hex5b,
            grpc5bDetail = desc5b,
            extraSummary = extra
        )
        synchronized(outboundFramesLock) {
            if (recentOutboundFrames.size >= 16) {
                recentOutboundFrames.removeAt(0)
            }
            recentOutboundFrames.add(entry)
        }
    }

    private fun recordOutgoingBuilderHeader(
        builderObj: Any,
        rawName: String,
        rawValue: String,
        transport: String = "Cronet"
    ) {
        if (!isForegroundActive.get()) return
        val lower = rawName.trim().lowercase()
        val isCredentialHeader = lower == "authorization" ||
            lower == "cookie" ||
            lower == "x-goog-authuser" ||
            lower == "x-goog-pageid"
        val isTechHeader = !isCredentialHeader && isSafeTechnicalHeader(lower)
        if ((!isCredentialHeader && !isTechHeader) || rawValue.isBlank()) return

        val snapshotHeaders = synchronized(builderHeaderMap) {
            val entry = builderHeaderMap.getOrPut(builderObj) { EphemeralSessionHeaders() }
            if (isNonQuotaTelemetryUrl(entry.url)) return
            when {
                lower == "authorization" -> entry.authorization = rawValue.trim()
                lower == "cookie" -> entry.cookie = rawValue.trim()
                lower == "x-goog-authuser" -> entry.authUser = rawValue.trim()
                lower == "x-goog-pageid" -> entry.pageId = rawValue.trim()
                lower == "content-type" -> entry.contentType = rawValue.trim()
                isTechHeader -> entry.extraHeaders[rawName.trim()] = rawValue.trim()
            }
            if (!isCredentialHeader) return
            entry.copy(extraHeaders = LinkedHashMap(entry.extraHeaders))
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
        @Suppress("UNUSED_PARAMETER") pageId: String?
    ): ComputedIdentity? {
        // Anchor ONLY on the single highest-priority stable cookie token (`__Secure-1PSID` > etc.)
        // and omit optional per-endpoint `X-Goog-PageId` so different Google subdomains on the
        // same account never produce different session hashes and falsely wipe quota to "--".
        val stableCookieToken = extractStableGoogleSessionCookie(cookie)
        val cleanAuthUser = authUser?.trim()?.takeIf { it.isNotEmpty() }
        val cleanBearer = authorization?.trim()?.takeIf { it.isNotEmpty() }

        return when {
            stableCookieToken != null -> {
                val key = "cookie:$stableCookieToken"
                ComputedIdentity(sha256SessionTag(key), IdentityStrength.COOKIE_SID, key)
            }
            cleanAuthUser != null -> {
                val key = "authuser:$cleanAuthUser"
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

    private fun extractAnchorCookieName(canonicalKey: String?): String? {
        if (canonicalKey == null || !canonicalKey.startsWith("cookie:")) return null
        val eq = canonicalKey.indexOf('=', 7)
        return if (eq > 7) canonicalKey.substring(7, eq) else null
    }

    private fun extractHostKey(urlOrAuthority: String?): String? {
        if (urlOrAuthority.isNullOrBlank()) return null
        return try {
            val trimmed = urlOrAuthority.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                URL(trimmed).host.lowercase().takeIf { it.isNotBlank() }
            } else if (trimmed.startsWith("grpc://", ignoreCase = true)) {
                trimmed.removePrefix("grpc://").substringBefore('/').lowercase().takeIf { it.isNotBlank() }
            } else {
                trimmed.substringBefore('/').substringBefore(':').lowercase().takeIf { it.contains('.') }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveAuthForUrl(url: String?, fallback: String?): String? {
        val host = extractHostKey(url)
        if (host != null) {
            perHostAuthorizations[host]?.let { return it }
            // Prefer a sibling Google APIs / robin token before generic telemetry tokens
            for ((k, v) in perHostAuthorizations) {
                if ((host.endsWith(".googleapis.com") && k.endsWith(".googleapis.com")) ||
                    (host.endsWith(".google.com") && k.endsWith(".google.com"))
                ) {
                    if (k.contains("robin") || k.contains("bard") || k.contains("gemini")) return v
                }
            }
        }
        return fallback
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

        // Only treat as a genuine Google account switch when the SAME anchor key type changes value
        // (e.g., two different `__Secure-1PSID` values or two different `X-Goog-AuthUser` indices).
        val prevAnchor = extractAnchorCookieName(prevStrongKey)
        val nextAnchor = extractAnchorCookieName(computed.canonicalKey)
        val sameCookieAnchorType = prevAnchor != null && prevAnchor == nextAnchor

        val isGenuineAccountSwitch = when {
            computed.strength == IdentityStrength.COOKIE_SID &&
                prevStrongStrength == IdentityStrength.COOKIE_SID &&
                prevStrongKey != null &&
                sameCookieAnchorType &&
                prevStrongKey != computed.canonicalKey -> true
            computed.strength == IdentityStrength.AUTH_USER &&
                prevStrongStrength == IdentityStrength.AUTH_USER &&
                prevStrongKey != null &&
                prevStrongKey != computed.canonicalKey -> true
            else -> false
        }

        if (isGenuineAccountSwitch) {
            perHostAuthorizations.clear()
        }

        var gainedNewHostAuth = false
        val incomingAuth = headers.authorization?.trim()?.takeIf { it.isNotEmpty() }
        val hostKey = extractHostKey(headers.url)
        if (incomingAuth != null && hostKey != null) {
            if (perHostAuthorizations.size > 24 && !perHostAuthorizations.containsKey(hostKey)) {
                perHostAuthorizations.clear()
            }
            val prevHostAuth = perHostAuthorizations.put(hostKey, incomingAuth)
            if (prevHostAuth == null) gainedNewHostAuth = true
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
        } else if ((hadNoCredentialsBefore || gainedNewCookie || gainedNewHostAuth || !hasCompletedLiveFetchThisWake.get()) && isForegroundActive.get()) {
            val curSnap = latestSnapshot.get().sanitized()
            if (!hasCompletedLiveFetchThisWake.get() || !curSnap.hasLiveData || curSnap.source == "SessionEstimate") {
                if (hadNoCredentialsBefore || gainedNewCookie || gainedNewHostAuth) {
                    lastHttpFetchMs.set(0L)
                }
                triggerLightweightQuotaRefresh(
                    reason = RefreshReason.APP_OPEN,
                    delayMs = 150L,
                    bypassThrottle = true
                )
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
        // Return the single highest-priority stable session key so requests carrying subsets
        // of Google cookies never hash into different accounts.
        for (stableKey in STABLE_COOKIE_KEYS) {
            val v = cookiePairs[stableKey]
            if (!v.isNullOrEmpty()) {
                return "$stableKey=$v"
            }
        }
        return null
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
                if (hasNonZeroQuotaData(prevSnap)) {
                    cachedNonZeroSnapshots[curPrev] = prevSnap.copy(isStaleOffline = false)
                    cachedNonZeroTimestamps[curPrev] = System.currentTimeMillis()
                }
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
                    if (hasNonZeroQuotaData(prevSnap)) {
                        cachedNonZeroSnapshots[newTag] = prevSnap.copy(isStaleOffline = false)
                        cachedNonZeroTimestamps[newTag] = System.currentTimeMillis()
                    }
                    prevSnap
                }
                else -> {
                    isGenuineAccountSwap = true
                    QuotaHudView.Snapshot(source = "AccountSwitch")
                }
            }

            if (isGenuineAccountSwap) {
                hasCompletedLiveFetchThisWake.set(false)
                localStorageScannedThisWake.set(false)
                cachedXsrfToken = null
                cachedFsidToken = null
                cachedBuildLabel = null
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
            if (isGenuineAccountSwap || !latestSnapshot.get().hasLiveData || !hasCompletedLiveFetchThisWake.get()) {
                lastHttpFetchMs.set(0L)
                triggerLightweightQuotaRefresh(
                    reason = if (isGenuineAccountSwap) RefreshReason.ACCOUNT_SWITCH else RefreshReason.APP_OPEN,
                    delayMs = 180L,
                    bypassThrottle = true
                )
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
            // Capture GMS Dynamite / Google Play Services ClassLoaders created via createPackageContext
            bridge.hookAllMethodsByName(
                clazz = android.content.ContextWrapper::class.java,
                methodName = "createPackageContext",
                after = { _, _, result ->
                    val dynCl = (result as? Context)?.classLoader
                    if (dynCl != null) {
                        bindHooksForClassLoader(dynCl, bridge)
                    }
                }
            )
            findClassSafe("android.app.ContextImpl", null)?.let { ctxImplCls ->
                bridge.hookAllMethodsByName(
                    clazz = ctxImplCls,
                    methodName = "createPackageContext",
                    after = { _, _, result ->
                        val dynCl = (result as? Context)?.classLoader
                        if (dynCl != null) {
                            bindHooksForClassLoader(dynCl, bridge)
                        }
                    }
                )
            }
            hookStructuredJsonPayloads(bridge)
            installHttpUrlConnectionHeaderHooks(bridge)
        }

        if (classLoader != null) {
            bindHooksForClassLoader(classLoader, bridge)
        }
        try {
            val ctxCl = Thread.currentThread().contextClassLoader
            if (ctxCl != null && ctxCl !== classLoader) {
                bindHooksForClassLoader(ctxCl, bridge)
            }
        } catch (_: Throwable) {
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

    private fun installHttpUrlConnectionHeaderHooks(bridge: HookBridge) {
        val connCandidates = arrayOf(
            "java.net.HttpURLConnection",
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
            "com.android.okhttp.internal.huc.HttpsURLConnectionImpl",
            "org.chromium.net.urlconnection.CronetHttpURLConnection"
        )
        for (cName in connCandidates) {
            val cls = findClassSafe(cName, null) ?: findClassSafe(cName, appContext?.classLoader) ?: continue
            if (!markDynamicHookOnce("huc", cls)) continue
            for (mName in arrayOf("setRequestProperty", "addRequestProperty")) {
                bridge.hookAllMethodsByName(
                    clazz = cls,
                    methodName = mName,
                    before = cb@{ thisObj, args ->
                        if (!isForegroundActive.get() || thisObj == null || args.size < 2) return@cb
                        val k = args[0] as? String ?: return@cb
                        val v = args[1] as? String ?: return@cb
                        val url = try {
                            (thisObj as? HttpURLConnection)?.url?.toString()
                        } catch (_: Throwable) {
                            null
                        }
                        if (url != null) {
                            synchronized(builderHeaderMap) {
                                builderHeaderMap.getOrPut(thisObj) { EphemeralSessionHeaders() }.url = url
                            }
                        }
                        recordOutgoingBuilderHeader(thisObj, k, v, transport = "HttpURLConn")
                    }
                )
            }
        }
    }

    private fun installCronetSessionAndStreamHooks(cl: ClassLoader, bridge: HookBridge) {
        findClassSafe("org.chromium.net.CronetProvider", cl)?.let { provCls ->
            bridge.hookAllMethodsByName(
                clazz = provCls,
                methodName = "createBuilder",
                after = { _, _, result ->
                    if (result != null) {
                        bindHooksForClassLoader(result.javaClass.classLoader, bridge)
                        hookConcreteCronetEngineBuilderClass(result.javaClass, bridge)
                    }
                }
            )
            bridge.hookAllMethodsByName(
                clazz = provCls,
                methodName = "getAllProviders",
                after = { _, _, result ->
                    val list = result as? Iterable<*>
                    if (list != null) {
                        for (prov in list) {
                            if (prov != null) {
                                bindHooksForClassLoader(prov.javaClass.classLoader, bridge)
                            }
                        }
                    }
                }
            )
        }

        val engineBuilders = arrayOf(
            "org.chromium.net.CronetEngine\$Builder",
            "org.chromium.net.ExperimentalCronetEngine\$Builder",
            "org.chromium.net.ICronetEngineBuilder"
        )
        for (bName in engineBuilders) {
            val builderCls = findClassSafe(bName, cl) ?: continue
            hookConcreteCronetEngineBuilderClass(builderCls, bridge)
        }

        val knownEngines = arrayOf(
            "org.chromium.net.impl.CronetEngineBase",
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

    private fun hookConcreteCronetEngineBuilderClass(builderCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("cronet_engine_builder", builderCls)) return
        bridge.hookAllMethodsByName(
            clazz = builderCls,
            methodName = "build",
            after = { _, _, result ->
                if (result != null) {
                    try {
                        activeCronetEngines.add(result)
                    } catch (_: Throwable) {
                    }
                    hookConcreteCronetEngineClass(result.javaClass, bridge)
                }
            }
        )
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
            val isBidi = mName.contains("Bidirectional", ignoreCase = true)
            bridge.hookAllMethodsByName(
                clazz = engineCls,
                methodName = mName,
                before = cb@{ thisObj, args ->
                    if (thisObj != null) {
                        try {
                            activeCronetEngines.add(thisObj)
                        } catch (_: Throwable) {
                        }
                    }
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
                                val entry = builderHeaderMap.getOrPut(result) { EphemeralSessionHeaders() }
                                entry.url = url
                                if (isBidi) {
                                    entry.isBidirectional = true
                                    if (entry.method.isNullOrBlank()) entry.method = "POST"
                                    if (entry.contentType.isNullOrBlank()) entry.contentType = "application/grpc"
                                }
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
            methodName = "setUploadDataProvider",
            before = cb@{ thisObj, args ->
                if (!isForegroundActive.get() || thisObj == null || args.isEmpty()) return@cb
                val provider = args[0] ?: return@cb
                synchronized(uploadProviderToBuilder) {
                    uploadProviderToBuilder[provider] = thisObj
                }
                hookConcreteUploadDataProviderClass(provider.javaClass, bridge)
                val bytes = extractSmallUploadBytesSafe(provider) ?: return@cb
                synchronized(builderHeaderMap) {
                    val entry = builderHeaderMap.getOrPut(thisObj) { EphemeralSessionHeaders() }
                    entry.bodyBytes = bytes
                    if (entry.method.isNullOrBlank()) entry.method = "POST"
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
                val isBidi = captured?.isBidirectional == true ||
                    result.javaClass.name.contains("Bidirectional", ignoreCase = true)
                val httpMethod = captured?.method ?: if (isBidi || captured?.bodyBytes != null) "POST" else "GET"
                val contentType = captured?.contentType ?: if (isBidi) "application/grpc" else null
                val isQuotaCand = isReplayableQuotaEndpoint(url, httpMethod)
                if (url != null && isQuotaCand) {
                    recordLearnedQuotaEndpoint(
                        url = url,
                        method = httpMethod,
                        rpcHint = if (isBidi) "Cronet-gRPC" else "Cronet-Learned",
                        contentType = contentType,
                        bodyBytes = captured?.bodyBytes,
                        extraHeaders = captured?.extraHeaders ?: emptyMap()
                    )
                }
                if (url != null && (isBidi || contentType?.contains("grpc", ignoreCase = true) == true || captured?.extraHeaders?.keys?.any { it.equals("te", ignoreCase = true) } == true)) {
                    recordOutboundProtocolFrameTrace(
                        transport = if (isBidi) "Cronet-H2-gRPC" else "Cronet-H2",
                        target = url,
                        teHeader = captured?.extraHeaders?.entries?.firstOrNull { it.key.equals("te", ignoreCase = true) }?.value ?: if (isBidi) "trailers" else null,
                        bodyBytes = captured?.bodyBytes,
                        extraHeaders = captured?.extraHeaders ?: emptyMap()
                    )
                }
                pruneRequestMapsIfNeeded()
                requestContextMap[System.identityHashCode(result)] = BoundRequestContext(
                    urlOrMethod = url,
                    httpMethod = httpMethod,
                    sessionTag = sessTag,
                    isQueryCandidate = isGeminiQueryEndpoint(url),
                    isQuotaEndpointCandidate = isQuotaCand,
                    contentType = contentType,
                    bodyBytes = captured?.bodyBytes,
                    isBidirectional = isBidi,
                    extraHeaders = captured?.extraHeaders?.toMap() ?: emptyMap()
                )
            }
        )
    }

    private fun hookConcreteUploadDataProviderClass(providerCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("cronet_upload_provider", providerCls)) return
        bridge.hookAllMethodsByName(
            clazz = providerCls,
            methodName = "read",
            after = cb@{ thisObj, args, _ ->
                if (!isForegroundActive.get() || thisObj == null) return@cb
                val buf = args.getOrNull(1) as? ByteBuffer ?: return@cb
                val copied = copyCronetByteBufferNonDestructive(buf) ?: return@cb
                if (copied.size > 16_384) return@cb
                val builderObj = synchronized(uploadProviderToBuilder) {
                    uploadProviderToBuilder[thisObj]
                } ?: return@cb
                synchronized(builderHeaderMap) {
                    val entry = builderHeaderMap[builderObj]
                    if (entry != null && entry.bodyBytes == null && !isGeminiQueryEndpoint(entry.url)) {
                        entry.bodyBytes = copied
                        if (entry.method.isNullOrBlank()) entry.method = "POST"
                    }
                }
            }
        )
    }

    private fun extractSmallUploadBytesSafe(provider: Any): ByteArray? {
        return try {
            for (f in collectInstanceFieldsSafe(provider.javaClass)) {
                f.isAccessible = true
                when (val v = f.get(provider)) {
                    is ByteArray -> {
                        if (v.size in 1..16_384) return v.copyOf()
                    }
                    is ByteBuffer -> {
                        val dup = v.duplicate()
                        if (dup.position() > 0 && dup.remaining() == 0) dup.flip()
                        val rem = dup.remaining()
                        if (rem in 1..16_384) {
                            val out = ByteArray(rem)
                            dup.get(out)
                            return out
                        }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
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
                    val isBidi = reqCls.name.contains("Bidirectional", ignoreCase = true)
                    val defaultMethod = if (isBidi) "POST" else "GET"
                    val reqId = System.identityHashCode(thisObj)
                    val isQuotaCand = isReplayableQuotaEndpoint(urlStr, defaultMethod)
                    if (isQuotaCand) {
                        recordLearnedQuotaEndpoint(
                            url = urlStr,
                            method = defaultMethod,
                            rpcHint = if (isBidi) "Cronet-gRPC" else "Cronet-Learned",
                            contentType = if (isBidi) "application/grpc" else null
                        )
                    }
                    pruneRequestMapsIfNeeded()
                    requestContextMap.putIfAbsent(
                        reqId,
                        BoundRequestContext(
                            urlOrMethod = urlStr,
                            httpMethod = if (isBidi) "POST" else null,
                            sessionTag = activeSessionTag.get(),
                            isQueryCandidate = isGeminiQueryEndpoint(urlStr),
                            isQuotaEndpointCandidate = isQuotaCand,
                            contentType = if (isBidi) "application/grpc" else null,
                            isBidirectional = isBidi
                        )
                    )
                }
            }
        )

        // Capture BidirectionalStream.write(ByteBuffer, boolean) gRPC request frames before transmission
        bridge.hookAllMethodsByName(
            clazz = reqCls,
            methodName = "write",
            before = cb@{ thisObj, args ->
                if (!isForegroundActive.get() || thisObj == null) return@cb
                val buf = args.firstOrNull { it is ByteBuffer } as? ByteBuffer ?: return@cb
                val reqId = System.identityHashCode(thisObj)
                val ctx = requestContextMap[reqId] ?: return@cb
                if (ctx.isQueryCandidate || ctx.bodyBytes != null) return@cb
                val dup = buf.duplicate()
                val rem = dup.remaining()
                if (rem in 5..16_384) {
                    val bytes = ByteArray(rem)
                    dup.get(bytes)
                    val updated = ctx.copy(
                        httpMethod = "POST",
                        contentType = ctx.contentType ?: "application/grpc",
                        bodyBytes = bytes,
                        isBidirectional = true
                    )
                    requestContextMap[reqId] = updated
                    recordOutboundProtocolFrameTrace(
                        transport = "Cronet-Bidi-gRPC",
                        target = updated.urlOrMethod ?: "BidirectionalStream",
                        teHeader = updated.extraHeaders.entries.firstOrNull { it.key.equals("te", ignoreCase = true) }?.value ?: "trailers",
                        bodyBytes = bytes,
                        extraHeaders = updated.extraHeaders
                    )
                    if (updated.isQuotaEndpointCandidate && updated.urlOrMethod != null) {
                        recordLearnedQuotaEndpoint(
                            url = updated.urlOrMethod,
                            method = "POST",
                            rpcHint = "Cronet-gRPC",
                            contentType = updated.contentType,
                            bodyBytes = bytes,
                            extraHeaders = updated.extraHeaders
                        )
                    }
                }
            }
        )

        // Track ByteBuffer pre-read cursor position so buffer reuse/compact() yields exact new byte deltas
        bridge.hookAllMethodsByName(
            clazz = reqCls,
            methodName = "read",
            before = cb@{ _, args ->
                if (!isForegroundActive.get()) return@cb
                val buf = args.firstOrNull { it is ByteBuffer } as? ByteBuffer ?: return@cb
                cronetPreReadPos[System.identityHashCode(buf)] = buf.position()
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

                val copied = copyCronetOnReadDelta(byteBuf) ?: return@cb
                val reqSession = boundCtx?.sessionTag ?: activeSessionTag.get()
                submitCronetChunk(reqId, urlHint, reqSession, copied)
            }
        )

        val cleanupMethods = arrayOf("onSucceeded", "onFailed", "onCanceled")
        for (cName in cleanupMethods) {
            bridge.hookAllMethodsByName(
                clazz = callbackCls,
                methodName = cName,
                after = cb@{ _, args, _ ->
                    val reqObj = args.getOrNull(0) ?: return@cb
                    val id = System.identityHashCode(reqObj)
                    val holder = cronetRollingStreams.remove(id)
                    val boundCtx = requestContextMap.remove(id)
                    if (isForegroundActive.get() && holder != null && !holder.resolvedQuotaSnapshot) {
                        val url = holder.urlHint ?: boundCtx?.urlOrMethod
                        val strictQuotaUrl = isStrictKnownQuotaEndpoint(url, boundCtx?.httpMethod)
                        if (strictQuotaUrl) {
                            val bytes = holder.snapshotAllBytes()
                            val sess = holder.sessionTag.ifBlank { activeSessionTag.get() }
                            submitForegroundWork {
                                if (cName == "onFailed") {
                                    QuotaHealthMonitor.recordEmptyResponse(sess, "Cronet-PB", "stream onFailed")
                                } else if (bytes.isNotEmpty() && QuotaTextParser.isLikelyCorruptedQuotaProto(bytes, bytes.size, url)) {
                                    QuotaHealthMonitor.recordProtobufParseFailure(sess, "Cronet-PB", "malformed/corrupt proto stream")
                                } else {
                                    QuotaHealthMonitor.recordEmptyResponse(sess, "Cronet-PB", "empty quota response")
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun copyCronetOnReadDelta(rawBuf: ByteBuffer): ByteArray? {
        val prePos = cronetPreReadPos.remove(System.identityHashCode(rawBuf))
        if (prePos != null && prePos >= 0) {
            try {
                val dup = rawBuf.duplicate()
                val curPos = dup.position()
                if (curPos > prePos && prePos <= dup.capacity()) {
                    val len = minOf(curPos - prePos, MAX_CHUNK_COPY_BYTES)
                    dup.limit(curPos)
                    dup.position(curPos - len)
                    val out = ByteArray(len)
                    dup.get(out)
                    return if (out.size >= 4) out else null
                }
            } catch (_: Throwable) {
            }
        }
        return copyCronetByteBufferNonDestructive(rawBuf)
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

    private fun isGoogleApiHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val host = URL(url).host.lowercase()
            host == "google.com" ||
                host.endsWith(".google.com") ||
                host == "googleapis.com" ||
                host.endsWith(".googleapis.com")
        } catch (_: Throwable) {
            val u = url.lowercase()
            u.contains(".google.com") || u.contains(".googleapis.com")
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

    /**
     * Strict quota endpoint check used for `QuotaHealthMonitor` empty-streak counting so normal
     * non-quota `robin` settings/account/config RPCs never falsely wipe cached HUD snapshots.
     */
    private fun isStrictKnownQuotaEndpoint(url: String?, method: String?): Boolean {
        if (url.isNullOrBlank() || isNonQuotaTelemetryUrl(url)) return false
        synchronized(learnedEndpointsLock) {
            if (learnedQuotaEndpoints.containsKey(url)) return true
        }
        val u = url.lowercase()
        val m = method?.trim()?.uppercase()
        if (m != null && m.isNotEmpty() && m != "GET" && m != "POST" && m != "GRPC") return false
        return u.contains("quota") ||
            u.contains("allowance") ||
            u.contains("ratelimit") ||
            u.contains("rate_limit") ||
            (u.contains("batchexecute") && (u.contains("hooq4d") || u.contains("o4g7id") || u.contains("maziqc")))
    }

    private fun isReplayableQuotaEndpoint(url: String?, method: String?): Boolean {
        if (url.isNullOrBlank() || isNonQuotaTelemetryUrl(url)) return false
        val u = url.lowercase()
        val isVirtualGrpc = u.startsWith("https://grpc/") || u.contains("google.internal")
        if (!isVirtualGrpc && !isGoogleApiHost(url)) return false
        if (u.contains("streamgenerate") || u.contains("generatecontent") ||
            u.contains("create") || u.contains("delete") || u.contains("feedback") ||
            u.contains("upload") || u.contains("report")
        ) {
            return false
        }
        val m = method?.trim()?.uppercase()
        val isGetPostOrGrpc = m.isNullOrEmpty() || m == "GET" || m == "POST" || m == "GRPC"
        if (!isGetPostOrGrpc) return false

        if (u.contains("quota") || u.contains("usage") || u.contains("allowance") ||
            u.contains("eligibility") || u.contains("ratelimit") || u.contains("rate_limit") ||
            u.contains("subscription") || u.contains("entitlement")
        ) {
            return true
        }
        if (u.contains("batchexecute") && (u.contains("hooq4d") || u.contains("o4g7id") || u.contains("maziqc"))) {
            return true
        }
        if ((u.contains("robinandroid-pa.googleapis.com") || u.contains("google.internal.apps.robin") || u.contains("robin")) &&
            (u.contains("settings") || u.contains("account") || u.contains("user") ||
                u.contains("feature") || u.contains("config") || u.contains("limit"))
        ) {
            return true
        }
        return false
    }

    private fun isSafeAutoLearnUrl(url: String?, method: String?): Boolean {
        if (url.isNullOrBlank() || isNonQuotaTelemetryUrl(url)) return false
        val u = url.lowercase()
        if (!u.startsWith("https://grpc/") && !u.contains("google.internal") && !isGoogleApiHost(url)) return false
        if (u.contains("streamgenerate") || u.contains("generatecontent") ||
            u.contains("feedback") || u.contains("upload")
        ) {
            return false
        }
        val m = method?.trim()?.uppercase()
        return m.isNullOrEmpty() || m == "GET" || m == "POST" || m == "GRPC"
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

    private fun recordLearnedQuotaEndpoint(
        url: String,
        method: String,
        rpcHint: String? = null,
        contentType: String? = null,
        bodyBytes: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        forceFromParsedResponse: Boolean = false
    ) {
        val allowed = if (forceFromParsedResponse) {
            isSafeAutoLearnUrl(url, method)
        } else {
            isReplayableQuotaEndpoint(url, method)
        }
        if (!allowed) return

        val bodyB64 = bodyBytes?.takeIf { it.isNotEmpty() && it.size <= 16_384 }?.let {
            try {
                Base64.encodeToString(it, Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            }
        }
        val safeHeaders = LinkedHashMap<String, String>()
        for ((k, v) in extraHeaders) {
            if (isSafeTechnicalHeader(k.lowercase()) && v.isNotBlank()) {
                safeHeaders[k] = v
            }
        }

        var changed = false
        synchronized(learnedEndpointsLock) {
            val prev = learnedQuotaEndpoints[url]
            if (prev != null && rpcHint == "WebView-DOM" && prev.rpcHint != null && prev.rpcHint != "WebView-DOM") {
                return
            }
            val normMethod = method.trim().uppercase().ifBlank { "GET" }
            val next = LearnedEndpoint(
                url = url,
                method = normMethod,
                rpcHint = rpcHint ?: prev?.rpcHint,
                contentType = contentType ?: prev?.contentType,
                bodyBase64 = bodyB64 ?: prev?.bodyBase64,
                extraHeaders = if (safeHeaders.isNotEmpty()) safeHeaders else (prev?.extraHeaders ?: emptyMap()),
                updatedAtMs = System.currentTimeMillis()
            )
            changed = prev == null ||
                prev.method != next.method ||
                prev.bodyBase64 != next.bodyBase64 ||
                prev.contentType != next.contentType ||
                prev.extraHeaders != next.extraHeaders
            learnedQuotaEndpoints[url] = next
            if (changed) {
                persistLearnedEndpointsLocked()
            }
        }
    }

    private fun persistLearnedEndpointsLocked() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (ep in learnedQuotaEndpoints.values) {
                val obj = JSONObject()
                obj.put("url", ep.url)
                obj.put("method", ep.method)
                if (!ep.rpcHint.isNullOrBlank()) obj.put("rpcHint", ep.rpcHint)
                if (!ep.contentType.isNullOrBlank()) obj.put("contentType", ep.contentType)
                if (!ep.bodyBase64.isNullOrBlank()) obj.put("bodyBase64", ep.bodyBase64)
                obj.put("updatedAtMs", ep.updatedAtMs)
                if (ep.extraHeaders.isNotEmpty()) {
                    val hObj = JSONObject()
                    for ((k, v) in ep.extraHeaders) {
                        if (isSafeTechnicalHeader(k.lowercase())) {
                            hObj.put(k, v)
                        }
                    }
                    obj.put("extraHeaders", hObj)
                }
                arr.put(obj)
            }
            ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("learned_endpoints_json", arr.toString())
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadLearnedEndpoints(prefs: SharedPreferences) {
        try {
            val raw = prefs.getString("learned_endpoints_json", null) ?: return
            if (raw.isBlank()) return
            val arr = JSONArray(raw)
            synchronized(learnedEndpointsLock) {
                for (i in 0 until minOf(arr.length(), 12)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("url", "")
                    val method = obj.optString("method", "GET")
                    if (!isSafeAutoLearnUrl(url, method)) continue
                    val headersMap = LinkedHashMap<String, String>()
                    val hObj = obj.optJSONObject("extraHeaders")
                    if (hObj != null) {
                        val keys = hObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = hObj.optString(k, "")
                            if (isSafeTechnicalHeader(k.lowercase()) && v.isNotBlank()) {
                                headersMap[k] = v
                            }
                        }
                    }
                    learnedQuotaEndpoints[url] = LearnedEndpoint(
                        url = url,
                        method = method.uppercase(),
                        rpcHint = obj.optString("rpcHint", "").takeIf { it.isNotBlank() },
                        contentType = obj.optString("contentType", "").takeIf { it.isNotBlank() },
                        bodyBase64 = obj.optString("bodyBase64", "").takeIf { it.isNotBlank() },
                        extraHeaders = headersMap,
                        updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                    )
                }
            }
        } catch (_: Throwable) {
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
        if (grpcCallMetaMap.size > 64) {
            val it = grpcCallMetaMap.entries.iterator()
            var drop = grpcCallMetaMap.size - 32
            while (it.hasNext() && drop-- > 0) {
                it.next()
                it.remove()
            }
        }
        if (grpcCallToListenerId.size > 64) {
            val it = grpcCallToListenerId.entries.iterator()
            var drop = grpcCallToListenerId.size - 32
            while (it.hasNext() && drop-- > 0) {
                it.next()
                it.remove()
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
            val boundCtx = requestContextMap[reqId]
            val resolvedUrl = urlHint ?: boundCtx?.urlOrMethod
            val quotaCand = boundCtx?.isQuotaEndpointCandidate == true ||
                isReplayableQuotaEndpoint(resolvedUrl, boundCtx?.httpMethod)
            val holder = cronetRollingStreams.getOrPut(reqId) {
                RollingStreamBuffer(resolvedUrl, sessionTag, quotaCand)
            }
            val combined = holder.appendAndSnapshot(chunk, chunk.size) ?: return@submitForegroundWork
            val baseSnap = getBaseSnapshotForSession(holder.sessionTag)
            val snap = try {
                QuotaTextParser.scanProtoBufBytes(
                    rawBytes = combined,
                    length = combined.size,
                    prev = baseSnap,
                    source = "Cronet-PB",
                    rpcMethodHint = holder.urlHint ?: resolvedUrl
                )
            } catch (t: Throwable) {
                QuotaHealthMonitor.recordProtobufParseFailure(
                    holder.sessionTag,
                    "Cronet-PB",
                    "${t.javaClass.simpleName}: ${t.message ?: "decode exception"}"
                )
                null
            }
            if (snap != null) {
                holder.resolvedQuotaSnapshot = true
                val latestCtx = requestContextMap[reqId] ?: boundCtx
                if (resolvedUrl != null && isSafeAutoLearnUrl(resolvedUrl, latestCtx?.httpMethod)) {
                    recordLearnedQuotaEndpoint(
                        url = resolvedUrl,
                        method = latestCtx?.httpMethod ?: if (latestCtx?.isBidirectional == true) "POST" else "GET",
                        rpcHint = if (latestCtx?.isBidirectional == true) "Cronet-gRPC" else "Cronet-PB",
                        contentType = latestCtx?.contentType,
                        bodyBytes = latestCtx?.bodyBytes,
                        extraHeaders = latestCtx?.extraHeaders ?: emptyMap(),
                        forceFromParsedResponse = true
                    )
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

    private fun extractSessionFromGrpcMetadataObject(metadataObj: Any?, authorityUrlOrHost: String? = null): String? {
        if (metadataObj == null) return null
        // Check if Metadata.put already recorded headers on this Metadata instance
        val recorded = synchronized(builderHeaderMap) {
            builderHeaderMap[metadataObj]?.copy()
        }
        if (recorded != null && recorded.hasCredentials) {
            if (recorded.url.isNullOrBlank() && !authorityUrlOrHost.isNullOrBlank()) {
                recorded.url = authorityUrlOrHost
            }
            return onDynamicHeadersIntercepted(recorded, transport = "gRPC")
        }

        // Reflective fallback for pre-populated io.grpc.Metadata internal namesAndValues array
        return try {
            val f = findFieldInHierarchy(metadataObj.javaClass, "namesAndValues") ?: return null
            val arr = f.get(metadataObj) as? Array<*> ?: return null
            val headers = EphemeralSessionHeaders(url = authorityUrlOrHost)
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
                        val methodDesc = args.getOrNull(0)
                        val ch = args.getOrNull(2)
                        val fullMethod = extractGrpcMethodNameSafe(methodDesc)
                        val authority = extractAuthorityFromChannelSafe(ch)
                        val virtualUrl = fullMethod?.let { "https://${authority ?: "grpc"}/$it" }
                        val isQuotaCand = !isGeminiQueryEndpoint(fullMethod) &&
                            !isNonQuotaTelemetryUrl(fullMethod) &&
                            isReplayableQuotaEndpoint(virtualUrl, "GRPC")
                        pruneRequestMapsIfNeeded()
                        val callId = System.identityHashCode(result)
                        requestContextMap[callId] = BoundRequestContext(
                            urlOrMethod = fullMethod,
                            httpMethod = "gRPC",
                            sessionTag = activeSessionTag.get(),
                            isQueryCandidate = isGeminiQueryEndpoint(fullMethod),
                            isQuotaEndpointCandidate = isQuotaCand
                        )
                        if (methodDesc != null) {
                            grpcCallMetaMap[callId] = Triple(
                                WeakReference(ch),
                                WeakReference(methodDesc),
                                WeakReference(args.getOrNull(1))
                            )
                        }
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
            after = { thisObj, args, result ->
                if (result != null) {
                    if (isForegroundActive.get()) {
                        val methodDesc = args.getOrNull(0)
                        val fullMethod = extractGrpcMethodNameSafe(methodDesc)
                        val authority = extractAuthorityFromChannelSafe(thisObj)
                        val virtualUrl = fullMethod?.let { "https://${authority ?: "grpc"}/$it" }
                        val isQuotaCand = !isGeminiQueryEndpoint(fullMethod) &&
                            !isNonQuotaTelemetryUrl(fullMethod) &&
                            isReplayableQuotaEndpoint(virtualUrl, "GRPC")
                        pruneRequestMapsIfNeeded()
                        val callId = System.identityHashCode(result)
                        requestContextMap[callId] = BoundRequestContext(
                            urlOrMethod = fullMethod,
                            httpMethod = "gRPC",
                            sessionTag = activeSessionTag.get(),
                            isQueryCandidate = isGeminiQueryEndpoint(fullMethod),
                            isQuotaEndpointCandidate = isQuotaCand
                        )
                        if (thisObj != null && methodDesc != null) {
                            grpcCallMetaMap[callId] = Triple(
                                WeakReference(thisObj),
                                WeakReference(methodDesc),
                                WeakReference(args.getOrNull(1))
                            )
                        }
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
                    val authorityHost = if (callId != 0) {
                        extractAuthorityFromChannelSafe(grpcCallMetaMap[callId]?.first?.get())
                    } else {
                        null
                    }
                    val authorityUrl = authorityHost?.let { "https://$it" }
                    val dynamicSess = extractSessionFromGrpcMetadataObject(metadata, authorityUrl)
                        ?: activeSessionTag.get()
                    val rpcName = existingCtx?.urlOrMethod
                    val virtualUrl = rpcName?.let { "https://${authorityHost ?: "grpc"}/$it" }
                    val isQuotaCand = existingCtx?.isQuotaEndpointCandidate == true ||
                        (!isGeminiQueryEndpoint(rpcName) &&
                            !isNonQuotaTelemetryUrl(rpcName) &&
                            isReplayableQuotaEndpoint(virtualUrl, "GRPC"))
                    val updatedCtx = BoundRequestContext(
                        urlOrMethod = rpcName,
                        httpMethod = "gRPC",
                        sessionTag = dynamicSess,
                        isQueryCandidate = isGeminiQueryEndpoint(rpcName),
                        isQuotaEndpointCandidate = isQuotaCand,
                        bodyBytes = existingCtx?.bodyBytes
                    )
                    val listenerId = System.identityHashCode(listener)
                    if (callId != 0) {
                        requestContextMap[callId] = updatedCtx
                        requestContextMap[listenerId] = updatedCtx
                        grpcCallToListenerId[callId] = listenerId
                        grpcCallMetaMap[callId]?.let { grpcCallMetaMap[listenerId] = it }
                    } else {
                        requestContextMap[listenerId] = updatedCtx
                    }
                }

                hookConcreteGrpcListenerClass(listener.javaClass, bridge)
            }
        )

        // ClientCall.sendMessage(ReqT): capture non-query gRPC request payload & detect query dispatch
        bridge.hookAllMethodsByName(
            clazz = callCls,
            methodName = "sendMessage",
            after = cb@{ thisObj, args, _ ->
                if (!isForegroundActive.get() || thisObj == null) return@cb
                val callId = System.identityHashCode(thisObj)
                val ctx = requestContextMap[callId]
                if (ctx == null || ctx.isQueryCandidate) {
                    onUserQueryDispatched("gRPC")
                    return@cb
                }
                if (isNonQuotaTelemetryUrl(ctx.urlOrMethod)) return@cb
                val reqMsg = args.firstOrNull()
                if (reqMsg != null && ctx.bodyBytes == null) {
                    val meta = grpcCallMetaMap[callId]
                    val reqBytes = when (reqMsg) {
                        is ByteArray -> if (reqMsg.size in 1..16_384) reqMsg.copyOf() else null
                        is ByteBuffer -> copyCronetByteBufferNonDestructive(reqMsg)?.takeIf { it.size <= 16_384 }
                        else -> trySerializeProtoLite(reqMsg)?.takeIf { it.size in 1..16_384 }
                            ?: tryStreamGrpcRequestViaDescriptor(meta?.second?.get(), reqMsg)
                    }
                    if (reqBytes != null) {
                        val updated = ctx.copy(bodyBytes = reqBytes)
                        requestContextMap[callId] = updated
                        val listenerId = grpcCallToListenerId[callId]
                        if (listenerId != null) {
                            requestContextMap[listenerId] = updated
                        }
                        recordOutboundProtocolFrameTrace(
                            transport = "gRPC-RPC",
                            target = updated.urlOrMethod ?: "gRPC.sendMessage",
                            teHeader = "trailers",
                            bodyBytes = reqBytes
                        )
                        if (meta?.first?.get() != null && meta.second?.get() != null && updated.urlOrMethod != null) {
                            learnedGrpcTargets[updated.urlOrMethod] = GrpcReplayTarget(
                                fullMethod = updated.urlOrMethod,
                                channelRef = meta.first!!,
                                methodDescRef = meta.second!!,
                                callOptionsRef = meta.third,
                                requestBytes = reqBytes,
                                authorityHost = extractAuthorityFromChannelSafe(meta.first?.get())
                            )
                        }
                    }
                }
            }
        )
    }

    private fun tryStreamGrpcRequestViaDescriptor(methodDesc: Any?, reqMsg: Any): ByteArray? {
        if (methodDesc == null) return null
        return try {
            val streamMethod = methodDesc.javaClass.methods.firstOrNull {
                it.name == "streamRequest" && it.parameterCount == 1
            } ?: return null
            val input = streamMethod.invoke(methodDesc, reqMsg) as? java.io.InputStream ?: return null
            input.use { ins ->
                val out = ByteArrayOutputStream(256)
                val buf = ByteArray(1024)
                var total = 0
                var r: Int
                while (ins.read(buf).also { r = it } != -1) {
                    total += r
                    if (total > 16_384) return null
                    out.write(buf, 0, r)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractAuthorityFromChannelSafe(channel: Any?): String? {
        if (channel == null) return null
        return try {
            channel.javaClass.getMethod("authority").invoke(channel) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookConcreteGrpcListenerClass(listenerCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_listener", listenerCls)) return

        bridge.hookAllMethodsByName(
            clazz = listenerCls,
            methodName = "onMessage",
            after = cb@{ thisObj, args, _ ->
                if (!isForegroundActive.get()) return@cb
                val msg = args.firstOrNull() ?: return@cb
                val listenerId = if (thisObj != null) System.identityHashCode(thisObj) else 0
                val listenerCtx = if (listenerId != 0) requestContextMap[listenerId] else null
                inspectGrpcResponseMessageAsync(
                    message = msg,
                    rpcHint = listenerCtx?.urlOrMethod,
                    sessionTag = listenerCtx?.sessionTag ?: activeSessionTag.get(),
                    listenerId = listenerId
                )
            }
        )

        bridge.hookAllMethodsByName(
            clazz = listenerCls,
            methodName = "onClose",
            after = { thisObj, _, _ ->
                if (thisObj != null) {
                    val id = System.identityHashCode(thisObj)
                    requestContextMap.remove(id)
                    grpcCallMetaMap.remove(id)
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

    private fun recordLearnedGrpcSuccess(rpcHint: String?, listenerId: Int) {
        if (rpcHint.isNullOrBlank() || isGeminiQueryEndpoint(rpcHint) || isNonQuotaTelemetryUrl(rpcHint)) return
        val ctx = if (listenerId != 0) requestContextMap[listenerId] else null
        val meta = if (listenerId != 0) grpcCallMetaMap[listenerId] else null
        val chRef = meta?.first
        val mdRef = meta?.second
        val prevTarget = learnedGrpcTargets[rpcHint]
        val resolvedBodyBytes = ctx?.bodyBytes ?: prevTarget?.requestBytes
        val authority = extractAuthorityFromChannelSafe(chRef?.get())
            ?: prevTarget?.authorityHost
            ?: perHostAuthorizations.keys.firstOrNull {
                it.contains("robin") || it.contains("bard") || it.contains("gemini")
            }

        if (chRef?.get() != null && mdRef?.get() != null) {
            if (learnedGrpcTargets.size > 8 && !learnedGrpcTargets.containsKey(rpcHint)) {
                learnedGrpcTargets.clear()
            }
            learnedGrpcTargets[rpcHint] = GrpcReplayTarget(
                fullMethod = rpcHint,
                channelRef = chRef,
                methodDescRef = mdRef,
                callOptionsRef = meta?.third,
                requestBytes = resolvedBodyBytes,
                authorityHost = authority
            )
        }
        if (!authority.isNullOrBlank() && resolvedBodyBytes != null) {
            val rpcUrl = "https://$authority/$rpcHint"
            val framed = ByteArray(5 + resolvedBodyBytes.size)
            val len = resolvedBodyBytes.size
            framed[0] = 0
            framed[1] = ((len ushr 24) and 0xFF).toByte()
            framed[2] = ((len ushr 16) and 0xFF).toByte()
            framed[3] = ((len ushr 8) and 0xFF).toByte()
            framed[4] = (len and 0xFF).toByte()
            System.arraycopy(resolvedBodyBytes, 0, framed, 5, len)
            recordLearnedQuotaEndpoint(
                url = rpcUrl,
                method = "POST",
                rpcHint = "gRPC-RPC",
                contentType = "application/grpc",
                bodyBytes = framed,
                forceFromParsedResponse = true
            )
        }
    }

    private fun inspectGrpcResponseMessageAsync(
        message: Any,
        rpcHint: String?,
        sessionTag: String,
        listenerId: Int = 0
    ) {
        submitForegroundWork {
            val targetSession = if (sessionTag != DEFAULT_SESSION_TAG) sessionTag else activeSessionTag.get()
            val baseSnap = getBaseSnapshotForSession(targetSession)

            val isQuotaRpc = rpcHint?.let { QuotaTextParser.hasQuotaContextHint(it) } == true
            when (message) {
                is ByteArray -> {
                    val snap = try {
                        QuotaTextParser.scanProtoBufBytes(
                            rawBytes = message,
                            length = message.size,
                            prev = baseSnap,
                            source = "gRPC-PB",
                            rpcMethodHint = rpcHint
                        )
                    } catch (t: Throwable) {
                        QuotaHealthMonitor.recordProtobufParseFailure(
                            targetSession,
                            "gRPC-PB",
                            "${t.javaClass.simpleName}: ${t.message ?: "byteArray decode error"}"
                        )
                        null
                    }
                    if (snap != null) {
                        recordLearnedGrpcSuccess(rpcHint, listenerId)
                        publishSnapshotForSession(snap, targetSession)
                    } else if (QuotaTextParser.isLikelyCorruptedQuotaProto(message, message.size, rpcHint)) {
                        QuotaHealthMonitor.recordProtobufParseFailure(targetSession, "gRPC-PB", "corrupt quota ByteArray")
                    } else if (isQuotaRpc) {
                        QuotaHealthMonitor.recordEmptyResponse(targetSession, "gRPC-PB", "empty quota RPC message")
                    }
                }
                is ByteBuffer -> {
                    val bytes = copyCronetByteBufferNonDestructive(message)
                    if (bytes != null) {
                        val snap = try {
                            QuotaTextParser.scanProtoBufBytes(
                                rawBytes = bytes,
                                length = bytes.size,
                                prev = baseSnap,
                                source = "gRPC-PB",
                                rpcMethodHint = rpcHint
                            )
                        } catch (t: Throwable) {
                            QuotaHealthMonitor.recordProtobufParseFailure(
                                targetSession,
                                "gRPC-PB",
                                "${t.javaClass.simpleName}: ${t.message ?: "byteBuffer decode error"}"
                            )
                            null
                        }
                        if (snap != null) {
                            recordLearnedGrpcSuccess(rpcHint, listenerId)
                            publishSnapshotForSession(snap, targetSession)
                        } else if (QuotaTextParser.isLikelyCorruptedQuotaProto(bytes, bytes.size, rpcHint)) {
                            QuotaHealthMonitor.recordProtobufParseFailure(targetSession, "gRPC-PB", "corrupt quota ByteBuffer")
                        } else if (isQuotaRpc) {
                            QuotaHealthMonitor.recordEmptyResponse(targetSession, "gRPC-PB", "empty quota ByteBuffer")
                        }
                    }
                }
                else -> {
                    val fieldSnap = inspectObjectGraphForQuota(message, "gRPC-Model", baseSnap)
                    if (fieldSnap != null) {
                        recordLearnedGrpcSuccess(rpcHint, listenerId)
                        publishSnapshotForSession(fieldSnap, targetSession)
                        return@submitForegroundWork
                    }
                    val serialized = trySerializeProtoLite(message)
                    if (serialized != null && serialized.size in 6..MAX_STREAM_ACCUM_BYTES) {
                        val protoSnap = try {
                            QuotaTextParser.scanProtoBufBytes(
                                rawBytes = serialized,
                                length = serialized.size,
                                prev = baseSnap,
                                source = "gRPC-PB",
                                rpcMethodHint = rpcHint
                            )
                        } catch (t: Throwable) {
                            QuotaHealthMonitor.recordProtobufParseFailure(
                                targetSession,
                                "gRPC-PB",
                                "${t.javaClass.simpleName}: ${t.message ?: "protoLite decode error"}"
                            )
                            null
                        }
                        if (protoSnap != null) {
                            recordLearnedGrpcSuccess(rpcHint, listenerId)
                            publishSnapshotForSession(protoSnap, targetSession)
                        } else if (QuotaTextParser.isLikelyCorruptedQuotaProto(serialized, serialized.size, rpcHint)) {
                            QuotaHealthMonitor.recordProtobufParseFailure(targetSession, "gRPC-PB", "corrupt serialized ProtoLite")
                        } else if (isQuotaRpc) {
                            QuotaHealthMonitor.recordEmptyResponse(targetSession, "gRPC-PB", "empty gRPC model response")
                        }
                    } else if (isQuotaRpc) {
                        QuotaHealthMonitor.recordEmptyResponse(targetSession, "gRPC-PB", "empty gRPC response")
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
            bridge.hookAllMethodsByName(
                clazz = reqBuilderCls,
                methodName = "post",
                before = cb@{ thisObj, args ->
                    if (!isForegroundActive.get() || thisObj == null || args.isEmpty()) return@cb
                    val reqBody = args[0] ?: return@cb
                    val (ct, bytes) = extractOkHttpRequestBodySafe(reqBody)
                    synchronized(builderHeaderMap) {
                        val entry = builderHeaderMap.getOrPut(thisObj) { EphemeralSessionHeaders() }
                        entry.method = "POST"
                        if (ct != null) entry.contentType = ct
                        if (bytes != null) entry.bodyBytes = bytes
                    }
                }
            )
            bridge.hookAllMethodsByName(
                clazz = reqBuilderCls,
                methodName = "method",
                before = cb@{ thisObj, args ->
                    if (!isForegroundActive.get() || thisObj == null || args.isEmpty()) return@cb
                    val m = args[0] as? String ?: return@cb
                    val reqBody = args.getOrNull(1)
                    val (ct, bytes) = if (reqBody != null) extractOkHttpRequestBodySafe(reqBody) else Pair(null, null)
                    synchronized(builderHeaderMap) {
                        val entry = builderHeaderMap.getOrPut(thisObj) { EphemeralSessionHeaders() }
                        entry.method = m.trim().uppercase()
                        if (ct != null) entry.contentType = ct
                        if (bytes != null) entry.bodyBytes = bytes
                    }
                }
            )
            bridge.hookAllMethodsByName(
                clazz = reqBuilderCls,
                methodName = "build",
                after = cb@{ thisObj, _, _ ->
                    if (!isForegroundActive.get() || thisObj == null) return@cb
                    val captured = synchronized(builderHeaderMap) {
                        builderHeaderMap[thisObj]?.copy(extraHeaders = LinkedHashMap(builderHeaderMap[thisObj]?.extraHeaders ?: emptyMap()))
                    } ?: return@cb
                    val uStr = captured.url ?: return@cb
                    val httpMethod = captured.method ?: if (captured.bodyBytes != null) "POST" else "GET"
                    if (isReplayableQuotaEndpoint(uStr, httpMethod)) {
                        recordLearnedQuotaEndpoint(
                            url = uStr,
                            method = httpMethod,
                            rpcHint = "OkHttp-Learned",
                            contentType = captured.contentType,
                            bodyBytes = captured.bodyBytes,
                            extraHeaders = captured.extraHeaders
                        )
                    }
                }
            )
        }

        val respCls = findClassSafe("okhttp3.Response", cl)
        if (respCls != null && markDynamicHookOnce("okhttp_response", respCls)) {
            bridge.hookAllMethodsByName(
                clazz = respCls,
                methodName = "body",
                after = cb@{ thisObj, _, result ->
                    if (!isForegroundActive.get() || thisObj == null || result == null) return@cb
                    val ctx = extractOkHttpResponseContextSafe(thisObj) ?: return@cb
                    pruneRequestMapsIfNeeded()
                    requestContextMap[System.identityHashCode(result)] = ctx
                }
            )
        }

        val respBodyCls = findClassSafe("okhttp3.ResponseBody", cl) ?: return
        if (!markDynamicHookOnce("okhttp_body", respBodyCls)) return

        bridge.hookAllMethodsByName(
            clazz = respBodyCls,
            methodName = "bytes",
            after = cb@{ thisObj, _, result ->
                if (!isForegroundActive.get()) return@cb
                val bytes = result as? ByteArray ?: return@cb
                if (bytes.size !in 6..MAX_STREAM_ACCUM_BYTES) return@cb
                val boundCtx = thisObj?.let { requestContextMap.remove(System.identityHashCode(it)) }
                val sess = boundCtx?.sessionTag ?: activeSessionTag.get()
                submitForegroundWork {
                    val snap = try {
                        QuotaTextParser.scanProtoBufBytes(
                            rawBytes = bytes,
                            length = bytes.size,
                            prev = getBaseSnapshotForSession(sess),
                            source = "OkHttp-PB",
                            rpcMethodHint = boundCtx?.urlOrMethod
                        )
                    } catch (t: Throwable) {
                        QuotaHealthMonitor.recordProtobufParseFailure(
                            sess,
                            "OkHttp-PB",
                            "${t.javaClass.simpleName}: ${t.message ?: "bytes decode error"}"
                        )
                        null
                    }
                    if (snap != null) {
                        val reqUrl = boundCtx?.urlOrMethod
                        if (reqUrl != null) {
                            recordLearnedQuotaEndpoint(
                                url = reqUrl,
                                method = boundCtx.httpMethod ?: "GET",
                                rpcHint = "OkHttp-PB",
                                contentType = boundCtx.contentType,
                                bodyBytes = boundCtx.bodyBytes,
                                extraHeaders = boundCtx.extraHeaders,
                                forceFromParsedResponse = true
                            )
                        }
                        publishSnapshotForSession(snap, sess)
                    } else if (QuotaTextParser.isLikelyCorruptedQuotaProto(bytes, bytes.size, boundCtx?.urlOrMethod)) {
                        QuotaHealthMonitor.recordProtobufParseFailure(sess, "OkHttp-PB", "corrupt quota ResponseBody")
                    }
                }
            }
        )

        bridge.hookAllMethodsByName(
            clazz = respBodyCls,
            methodName = "string",
            after = cb@{ thisObj, _, result ->
                if (!isForegroundActive.get()) return@cb
                val raw = result as? String ?: return@cb
                if (raw.length > MAX_STREAM_ACCUM_BYTES || !QuotaTextParser.fastHasQuotaKeyword(raw)) return@cb
                val boundCtx = thisObj?.let { requestContextMap.remove(System.identityHashCode(it)) }
                val sess = boundCtx?.sessionTag ?: activeSessionTag.get()
                submitForegroundWork {
                    val snap = QuotaTextParser.parseInAppStructuredPayload(
                        payload = raw,
                        prev = getBaseSnapshotForSession(sess),
                        source = "OkHttp-JSON"
                    )
                    if (snap != null) {
                        val reqUrl = boundCtx?.urlOrMethod
                        if (reqUrl != null) {
                            recordLearnedQuotaEndpoint(
                                url = reqUrl,
                                method = boundCtx.httpMethod ?: "GET",
                                rpcHint = "OkHttp-JSON",
                                contentType = boundCtx.contentType,
                                bodyBytes = boundCtx.bodyBytes,
                                extraHeaders = boundCtx.extraHeaders,
                                forceFromParsedResponse = true
                            )
                        }
                        publishSnapshotForSession(snap, sess)
                    }
                }
            }
        )
    }

    private fun extractOkHttpRequestBodySafe(reqBody: Any): Pair<String?, ByteArray?> {
        var contentType: String? = null
        var bodyBytes: ByteArray? = null
        try {
            contentType = try {
                reqBody.javaClass.getMethod("contentType").invoke(reqBody)?.toString()
            } catch (_: Throwable) {
                null
            }
            if (reqBody.javaClass.name.contains("FormBody")) {
                try {
                    val sizeVal = (reqBody.javaClass.getMethod("size").invoke(reqBody) as? Int) ?: 0
                    if (sizeVal in 1..64) {
                        val encNameM = reqBody.javaClass.getMethod("encodedName", Int::class.javaPrimitiveType)
                        val encValM = reqBody.javaClass.getMethod("encodedValue", Int::class.javaPrimitiveType)
                        val formSb = StringBuilder()
                        for (idx in 0 until sizeVal) {
                            val k = encNameM.invoke(reqBody, idx) as? String ?: continue
                            val v = encValM.invoke(reqBody, idx) as? String ?: ""
                            if (formSb.isNotEmpty()) formSb.append('&')
                            formSb.append(k).append('=').append(v)
                        }
                        if (formSb.isNotEmpty() && formSb.length <= 16_384) {
                            bodyBytes = formSb.toString().toByteArray(StandardCharsets.UTF_8)
                            if (contentType.isNullOrBlank()) {
                                contentType = "application/x-www-form-urlencoded;charset=utf-8"
                            }
                            return Pair(contentType, bodyBytes)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            val encodedNamesList = ArrayList<String>()
            val encodedValuesList = ArrayList<String>()
            for (f in collectInstanceFieldsSafe(reqBody.javaClass)) {
                f.isAccessible = true
                when (val v = f.get(reqBody)) {
                    is ByteArray -> if (v.size in 1..16_384) {
                        bodyBytes = v.copyOf()
                        break
                    }
                    is List<*> -> {
                        val fn = f.name.lowercase()
                        if (fn.contains("name")) {
                            v.filterIsInstance<String>().forEach { encodedNamesList.add(it) }
                        } else if (fn.contains("value")) {
                            v.filterIsInstance<String>().forEach { encodedValuesList.add(it) }
                        }
                    }
                    else -> {
                        if (v != null && v.javaClass.name.contains("ByteString")) {
                            val b = try {
                                v.javaClass.getMethod("toByteArray").invoke(v) as? ByteArray
                            } catch (_: Throwable) {
                                null
                            }
                            if (b != null && b.size in 1..16_384) {
                                bodyBytes = b
                                break
                            }
                        }
                    }
                }
            }
            if (bodyBytes == null && encodedNamesList.isNotEmpty() && encodedNamesList.size == encodedValuesList.size) {
                val formSb = StringBuilder()
                for (i in encodedNamesList.indices) {
                    if (formSb.isNotEmpty()) formSb.append('&')
                    formSb.append(encodedNamesList[i]).append('=').append(encodedValuesList[i])
                }
                if (formSb.isNotEmpty() && formSb.length <= 16_384) {
                    bodyBytes = formSb.toString().toByteArray(StandardCharsets.UTF_8)
                    if (contentType.isNullOrBlank()) {
                        contentType = "application/x-www-form-urlencoded;charset=utf-8"
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return Pair(contentType, bodyBytes)
    }

    private fun extractOkHttpResponseContextSafe(respObj: Any): BoundRequestContext? {
        return try {
            val req = respObj.javaClass.getMethod("request").invoke(respObj) ?: return null
            val url = req.javaClass.getMethod("url").invoke(req)?.toString() ?: return null
            if (!isGoogleApiHost(url) || isNonQuotaTelemetryUrl(url)) return null
            val method = (req.javaClass.getMethod("method").invoke(req) as? String)?.uppercase() ?: "GET"
            val reqBody = try {
                req.javaClass.getMethod("body").invoke(req)
            } catch (_: Throwable) {
                null
            }
            val (ct, bytes) = if (reqBody != null) extractOkHttpRequestBodySafe(reqBody) else Pair(null, null)
            val safeHeaders = LinkedHashMap<String, String>()
            try {
                val headersObj = req.javaClass.getMethod("headers").invoke(req)
                if (headersObj != null) {
                    val size = (headersObj.javaClass.getMethod("size").invoke(headersObj) as? Int) ?: 0
                    val nameM = headersObj.javaClass.getMethod("name", Int::class.javaPrimitiveType)
                    val valM = headersObj.javaClass.getMethod("value", Int::class.javaPrimitiveType)
                    for (i in 0 until minOf(size, 24)) {
                        val k = nameM.invoke(headersObj, i) as? String ?: continue
                        val v = valM.invoke(headersObj, i) as? String ?: continue
                        if (isSafeTechnicalHeader(k.lowercase()) && v.isNotBlank()) {
                            safeHeaders[k] = v
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            BoundRequestContext(
                urlOrMethod = url,
                httpMethod = method,
                sessionTag = activeSessionTag.get(),
                isQueryCandidate = isGeminiQueryEndpoint(url),
                isQuotaEndpointCandidate = isReplayableQuotaEndpoint(url, method),
                contentType = ct,
                bodyBytes = bytes,
                extraHeaders = safeHeaders
            )
        } catch (_: Throwable) {
            null
        }
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
        delayMs: Long = 0L,
        bypassThrottle: Boolean = false
    ) {
        if (!isForegroundActive.get()) return
        val force = bypassThrottle || reason == RefreshReason.MANUAL_TAP || reason == RefreshReason.ACCOUNT_SWITCH
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs.get() < 1_500L) return

        val executeRefresh = {
            if (isForegroundActive.get()) {
                if (reason == RefreshReason.MANUAL_TAP || reason == RefreshReason.ACCOUNT_SWITCH) {
                    triggerForegroundFullProtocolSnapshotScan(reason, bypassThrottle = true)
                } else {
                    lastRefreshMs.set(System.currentTimeMillis())
                    val sess = activeSessionTag.get()
                    submitForegroundWork {
                        if (dexRepoScanCompleted.compareAndSet(false, true)) {
                            appContext?.classLoader?.let { scanDexClassesForRepositoriesSafe(it) }
                        }
                        runForegroundViewModelAndRepoScan(sess)
                        if (!hasCompletedLiveFetchThisWake.get() && localStorageScannedThisWake.compareAndSet(false, true)) {
                            scanLocalHostStorageForCachedQuota(sess)
                        }
                        fetchActiveQuotaOverHttp(sess, reason)
                    }
                    postToMain(delayMs = 250L, requireForeground = true) {
                        scanVisibleUiForQuotaTexts(sess, allowFullScreenParse = true)
                    }
                }
            }
        }

        if (delayMs > 0L) {
            postToMain(delayMs = delayMs, requireForeground = true) { executeRefresh() }
        } else {
            executeRefresh()
        }
    }

    /**
     * Foreground-only Full-Protocol Snapshot Scan:
     * Executes a single bounded sweep across:
     * 1. Local DataStore / ProtoBuf storage
     * 2. Active ViewModel / StateFlow / Repository DI object graph
     * 3. Any attached WebViews in the foreground Activity hierarchy
     * 4. Multi-stack HTTP/2 + gRPC + `batchexecute` endpoint replay
     */
    private fun triggerForegroundFullProtocolSnapshotScan(
        reason: RefreshReason,
        bypassThrottle: Boolean = false
    ) {
        if (!isForegroundActive.get()) return
        val now = System.currentTimeMillis()
        if (!bypassThrottle && now - lastFullProtocolScanMs.get() < 3_500L) return
        lastFullProtocolScanMs.set(now)
        lastRefreshMs.set(now)

        val sess = activeSessionTag.get()
        if (!hasCompletedLiveFetchThisWake.get() || bypassThrottle) {
            lastHttpFetchMs.set(0L)
        }
        safeLog(
            activeBridgeRef.get(),
            "FullProtocolScan [${reason.tag}] started for $sess (DataStore + RepoGraph + WebViews + H2/gRPC)"
        )

        postToMain(delayMs = 50L, requireForeground = true) {
            activeActivityRef.get().get()?.let { act ->
                scanAttachedWebViewsForQuotaAsync(act)
            }
            scanVisibleUiForQuotaTexts(sess, allowFullScreenParse = true)
        }

        submitForegroundWork {
            if (dexRepoScanCompleted.compareAndSet(false, true)) {
                appContext?.classLoader?.let { scanDexClassesForRepositoriesSafe(it) }
            }
            scanLocalHostStorageForCachedQuota(sess)
            runForegroundViewModelAndRepoScan(sess)
            fetchActiveQuotaOverHttp(sess, reason)
        }
    }

    private fun buildOutboundProtocolDiagnosticReport(reasonTag: String, sess: String): String {
        val traces: List<OutboundProtocolFrameTrace> = synchronized(outboundFramesLock) {
            ArrayList(recentOutboundFrames)
        }
        val learnedCount: Int
        val learnedSummary = StringBuilder()
        synchronized(learnedEndpointsLock) {
            learnedCount = learnedQuotaEndpoints.size
            for (ep in learnedQuotaEndpoints.values) {
                if (learnedSummary.isNotEmpty()) learnedSummary.append('\n')
                val rawBody = ep.bodyBase64?.let {
                    try {
                        Base64.decode(it, Base64.DEFAULT)
                    } catch (_: Throwable) {
                        null
                    }
                }
                val (hex5b, desc5b) = describeGrpc5bFrameHeader(rawBody)
                val te = ep.extraHeaders.entries.firstOrNull { it.key.equals("te", ignoreCase = true) }?.value
                    ?: if (ep.url.contains("/\$rpc/") || ep.contentType?.contains("grpc", ignoreCase = true) == true) "trailers" else "trailers (H2)"
                learnedSummary.append("  • [Learned:${ep.rpcHint ?: ep.method}] ${ep.url}\n")
                learnedSummary.append("    HTTP/2 TE: $te | gRPC-RPC 5B帧头: $hex5b ($desc5b)")
            }
        }
        val grpcCount = learnedGrpcTargets.size
        val grpcSummary = StringBuilder()
        for ((method, target) in learnedGrpcTargets) {
            if (grpcSummary.isNotEmpty()) grpcSummary.append('\n')
            val (hex5b, desc5b) = describeGrpc5bFrameHeader(target.requestBytes)
            grpcSummary.append("  • [gRPC-RPC] ${target.authorityHost ?: "grpc"}/$method\n")
            grpcSummary.append("    HTTP/2 TE: trailers | gRPC-RPC 5B帧头: $hex5b ($desc5b)")
        }

        val sb = StringBuilder(1024)
        sb.append("[runForegroundFullProtocolDiagnostic · $reasonTag] Session=$sess\n")
        sb.append("CronetEngines=${synchronized(activeCronetEngines) { activeCronetEngines.size }}, LearnedEndpoints=$learnedCount, gRPCTargets=$grpcCount, CapturedOutbound=${traces.size}\n")
        sb.append("--- 出站 HTTP/2 TE: trailers & gRPC-RPC 5B 帧头明细 ---\n")
        if (traces.isEmpty() && learnedSummary.isEmpty() && grpcSummary.isEmpty()) {
            sb.append("  (当前进程暂无出站 H2/gRPC 记录；正在强扫 DataStore/Repo/WebView 并主动探测)\n")
        } else {
            for ((idx, tr) in traces.asReversed().withIndex()) {
                sb.append("  #${idx + 1} [${tr.transport}] ${tr.target}\n")
                sb.append("     HTTP/2 TE: ${tr.teHeader} | gRPC-RPC 5B帧头: ${tr.grpc5bHex} (${tr.grpc5bDetail})\n")
            }
            if (grpcSummary.isNotEmpty()) {
                sb.append(grpcSummary).append('\n')
            }
            if (learnedSummary.isNotEmpty()) {
                sb.append(learnedSummary).append('\n')
            }
        }
        return sb.toString().trim()
    }

    /**
     * Manual & diagnostic entrypoint triggered directly from the HUD Settings popup.
     * Bypasses all cooldowns, dumps all outbound HTTP/2 `TE: trailers` and `gRPC-RPC` 5-byte
     * frame headers, updates the Dev Console + Settings trace panel, and forces a full sweep.
     */
    fun runForegroundFullProtocolDiagnostic(reasonTag: String = "MANUAL_FORCE_SCAN"): String {
        val sess = activeSessionTag.get()
        lastFullProtocolScanMs.set(0L)
        lastRefreshMs.set(0L)
        lastHttpFetchMs.set(0L)
        hasCompletedLiveFetchThisWake.set(false)

        val report = buildOutboundProtocolDiagnosticReport(reasonTag, sess)
        for (line in report.lineSequence()) {
            safeLog(activeBridgeRef.get(), line)
        }

        val ctx = appContext
        if (ctx != null) {
            submitForegroundWork {
                QuotaSyncProvider.recordFullProtocolScanEvent(
                    context = ctx,
                    sessionId = sess,
                    source = "Diagnostic-$reasonTag",
                    summary = "H2/gRPC-5B",
                    detailZh = report,
                    detailEn = report
                )
            }
        }

        if (isForegroundActive.get()) {
            triggerForegroundFullProtocolSnapshotScan(
                reason = RefreshReason.MANUAL_TAP,
                bypassThrottle = true
            )
        }
        return report
    }

    private fun scanAttachedWebViewsForQuotaAsync(activity: Activity) {
        if (!isForegroundActive.get()) return
        val decor = try {
            activity.window?.decorView
        } catch (_: Throwable) {
            null
        } ?: return

        val found = ArrayList<WebView>(4)
        fun walkViews(v: View, depth: Int) {
            if (depth > 18 || found.size >= 4) return
            if (v is WebView) {
                found.add(v)
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i) ?: continue
                    walkViews(child, depth + 1)
                    if (found.size >= 4) break
                }
            }
        }
        try {
            walkViews(decor, 0)
            for (wv in found) {
                val url = try {
                    wv.url
                } catch (_: Throwable) {
                    null
                }
                if (!url.isNullOrBlank()) {
                    onWebViewPageEvent(wv, url, delayEvaluateMs = 50L)
                }
            }
        } catch (_: Throwable) {
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

        // ProGuard-obfuscated ProtoLite fallback across discovered ViewModel/StateFlow/Repository objects
        for (obj in visited.keys) {
            val pb = trySerializeProtoLite(obj)
            if (pb != null && pb.size in 6..MAX_STREAM_ACCUM_BYTES) {
                val pbSnap = try {
                    QuotaTextParser.scanProtoBufBytes(
                        rawBytes = pb,
                        length = pb.size,
                        prev = baseSnapshot,
                        source = "$source-PB",
                        rpcMethodHint = obj.javaClass.name
                    )
                } catch (_: Throwable) {
                    null
                }
                if (pbSnap != null && pbSnap.hasLiveData) return pbSnap
            }
        }

        return null
    }

    private fun scanLocalHostStorageForCachedQuota(sessionTag: String) {
        if (!isForegroundActive.get()) return
        val ctx = appContext ?: return
        val hintKeys = arrayOf("quota", "usage", "allowance", "robin", "bard", "tier", "limit", "eligibility", "entitlement")

        try {
            val dsDir = File(ctx.filesDir, "datastore")
            if (dsDir.isDirectory) {
                val files = dsDir.listFiles() ?: emptyArray()
                var checked = 0
                for (f in files) {
                    if (!isForegroundActive.get() || checked >= 12) break
                    if (!f.isFile || f.length() !in 12L..65_536L) continue
                    val fn = f.name.lowercase()
                    if (!fn.endsWith(".pb") && !fn.endsWith(".preferences_pb")) continue
                    if (hintKeys.none { fn.contains(it) }) continue
                    checked++
                    val bytes = try {
                        f.readBytes()
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val snap = try {
                        QuotaTextParser.scanProtoBufBytes(
                            rawBytes = bytes,
                            length = bytes.size,
                            prev = getBaseSnapshotForSession(sessionTag),
                            source = "Local-DataStore",
                            rpcMethodHint = fn
                        )
                    } catch (_: Throwable) {
                        null
                    }
                    if (snap != null && snap.hasLiveData) {
                        publishSnapshotForSession(snap, sessionTag)
                        break
                    }
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.isDirectory) {
                val files = prefsDir.listFiles() ?: emptyArray()
                var checked = 0
                for (f in files) {
                    if (!isForegroundActive.get() || checked >= 12) break
                    if (!f.isFile || f.length() !in 16L..65_536L) continue
                    val fn = f.name.lowercase()
                    if (!fn.endsWith(".xml") || fn.contains(QuotaTextParser.PREFS_NAME)) continue
                    if (hintKeys.none { fn.contains(it) }) continue
                    checked++
                    val text = try {
                        f.readText(StandardCharsets.UTF_8)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    if (!QuotaTextParser.fastHasQuotaKeyword(text)) continue
                    val snap = QuotaTextParser.parseInAppStructuredPayload(
                        payload = text,
                        prev = getBaseSnapshotForSession(sessionTag),
                        source = "Local-Prefs"
                    ) ?: QuotaTextParser.parseScreenQuotaTexts(
                        text = text,
                        prev = getBaseSnapshotForSession(sessionTag),
                        source = "Local-Prefs"
                    )
                    if (snap != null && snap.hasLiveData) {
                        publishSnapshotForSession(snap, sessionTag)
                        break
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun walkObjectGraphRecursive(
        node: Any?,
        depth: Int,
        visited: IdentityHashMap<Any, Boolean>,
        nowMs: Long,
        namedOut: MutableMap<String, String>,
        windowsOut: MutableList<StructuralWindow>
    ) {
        if (node == null || depth > 5 || visited.size >= 240 || windowsOut.size >= 8) return
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
                    try {
                        activeCronetEngines.add(node)
                    } catch (_: Throwable) {
                    }
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

        // Skip bulky Android framework Context / UI / resource internals so DI graph traversal reaches Hilt/Dagger fields
        if (clsName.startsWith("android.content.") ||
            clsName.startsWith("android.view.") ||
            clsName.startsWith("android.graphics.") ||
            clsName.startsWith("android.widget.") ||
            clsName.startsWith("android.os.") && node !is Bundle ||
            (clsName.startsWith("android.app.") && node !is Application) ||
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
                is ByteArray -> {
                    if (rawVal.size in 12..MAX_STREAM_ACCUM_BYTES) {
                        try {
                            val byteSnap = QuotaTextParser.scanProtoBufBytes(
                                rawBytes = rawVal,
                                length = rawVal.size,
                                prev = QuotaHudView.Snapshot(),
                                source = "MemBytes",
                                rpcMethodHint = clsName
                            )
                            if (byteSnap != null && byteSnap.hasLiveData) {
                                if (byteSnap.fiveHourQuota != "--") {
                                    windowsOut.add(StructuralWindow(QuotaTextParser.MetricKind.FIVE_HOUR, byteSnap.fiveHourQuota, byteSnap.fiveHourReset, 4))
                                }
                                if (byteSnap.weeklyLimit != "--") {
                                    windowsOut.add(StructuralWindow(QuotaTextParser.MetricKind.WEEKLY, byteSnap.weeklyLimit, byteSnap.weeklyReset, 4))
                                }
                            }
                        } catch (_: Throwable) {
                        }
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
                        if (a in 1..b && b in setOf(5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 500, 1000, 2000)) {
                            val pct = ((a.toDouble() / b.toDouble()) * 100.0).roundToInt().coerceIn(1, 100)
                            metric = QuotaTextParser.sanitizeQuotaMetric("$pct%")
                            conf += 2
                            break
                        }
                    }
                    if (metric != "--") break
                }
            }

            if (metric == "--") {
                val frac = objDoubles.firstOrNull { it in 0.005..1.0 }
                if (frac != null) {
                    metric = QuotaTextParser.formatFractionAsPercent(frac)
                    conf += 1
                } else if (objInts.size == 1 && objInts[0] in 1..100) {
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
            methodName = "shouldInterceptRequest",
            before = cb@{ _, args ->
                if (!isForegroundActive.get()) return@cb
                val reqObj = args.getOrNull(1) ?: return@cb
                if (reqObj is String) {
                    if (isGoogleApiHost(reqObj) && !isNonQuotaTelemetryUrl(reqObj) && isReplayableQuotaEndpoint(reqObj, "GET")) {
                        recordLearnedQuotaEndpoint(reqObj, "GET", rpcHint = "WebView-Intercept")
                    }
                    return@cb
                }
                try {
                    val u = reqObj.javaClass.getMethod("getUrl").invoke(reqObj)?.toString() ?: return@cb
                    if (!isGoogleApiHost(u) || isNonQuotaTelemetryUrl(u)) return@cb
                    val m = (reqObj.javaClass.getMethod("getMethod").invoke(reqObj) as? String)?.uppercase() ?: "GET"
                    @Suppress("UNCHECKED_CAST")
                    val hdrs = reqObj.javaClass.getMethod("getRequestHeaders").invoke(reqObj) as? Map<String, String>
                    if (hdrs != null) {
                        val eph = EphemeralSessionHeaders(url = u, method = m)
                        for ((k, v) in hdrs) {
                            when (k.lowercase()) {
                                "authorization" -> eph.authorization = v.trim()
                                "cookie" -> eph.cookie = v.trim()
                                "x-goog-authuser" -> eph.authUser = v.trim()
                                "content-type" -> eph.contentType = v.trim()
                                else -> if (isSafeTechnicalHeader(k.lowercase())) eph.extraHeaders[k] = v.trim()
                            }
                        }
                        if (eph.hasCredentials) {
                            onDynamicHeadersIntercepted(eph, transport = "WebViewReq")
                        }
                        if (isReplayableQuotaEndpoint(u, m)) {
                            recordLearnedQuotaEndpoint(u, m, rpcHint = "WebView-Intercept", contentType = eph.contentType, extraHeaders = eph.extraHeaders)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        )
        bridge.hookAllMethodsByName(
            clazz = clientCls,
            methodName = "onPageFinished",
            after = cb@{ _, args, _ ->
                val wv = args.getOrNull(0) as? WebView ?: return@cb
                val url = args.getOrNull(1) as? String ?: return@cb
                onWebViewPageEvent(wv, url, delayEvaluateMs = 350L)
                onWebViewPageEvent(wv, url, delayEvaluateMs = 1_450L)
                onWebViewPageEvent(wv, url, delayEvaluateMs = 3_000L)
            }
        )
    }

    private fun onWebViewPageEvent(webView: WebView, rawUrl: String, delayEvaluateMs: Long) {
        if (!isForegroundActive.get()) return
        val lower = rawUrl.lowercase()
        if (!isGoogleApiHost(rawUrl) || isNonQuotaTelemetryUrl(rawUrl)) return

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
                    if(!window.__bqhSpyInstalled){
                      window.__bqhSpyInstalled=true;
                      window.__bqhHits=[];
                      var pushHit=function(u,m,ct,rq,rs){
                        try{
                          if(!u||u.indexOf('google')<0||u.indexOf('/log')>=0||u.indexOf('play.googleapis')>=0)return;
                          if(!rs||rs.length<6||rs.length>65536)return;
                          if(window.__bqhHits.length>=16)window.__bqhHits.shift();
                          window.__bqhHits.push({u:String(u),m:String(m||'GET'),ct:String(ct||''),rq:String(rq||'').slice(0,12288),rs:String(rs)});
                        }catch(e){}
                      };
                      var origFetch=window.fetch;
                      if(origFetch){
                        window.fetch=function(input,init){
                          var u=(typeof input==='string')?input:(input&&input.url?input.url:'');
                          var m=(init&&init.method)?init.method:(input&&input.method?input.method:'GET');
                          var ct='';
                          try{var h=init&&init.headers;if(h&&h.get)ct=h.get('content-type')||'';else if(h)ct=h['Content-Type']||h['content-type']||'';}catch(e){}
                          var rq=(init&&typeof init.body==='string')?init.body:'';
                          return origFetch.apply(this,arguments).then(function(resp){
                            try{if(resp&&resp.clone)resp.clone().text().then(function(tx){pushHit(u,m,ct,rq,tx);}).catch(function(){});}catch(e){}
                            return resp;
                          });
                        };
                      }
                      var origOpen=XMLHttpRequest.prototype.open;
                      var origSetHdr=XMLHttpRequest.prototype.setRequestHeader;
                      var origSend=XMLHttpRequest.prototype.send;
                      XMLHttpRequest.prototype.open=function(m,u){this.__bqhM=m;this.__bqhU=u;return origOpen.apply(this,arguments);};
                      XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{if(k&&k.toLowerCase()==='content-type')this.__bqhCt=v;}catch(e){}return origSetHdr.apply(this,arguments);};
                      XMLHttpRequest.prototype.send=function(body){
                        var self=this;
                        try{
                          this.__bqhRq=(typeof body==='string')?body:'';
                          this.addEventListener('load',function(){try{if(self.responseType===''||self.responseType==='text')pushHit(self.__bqhU,self.__bqhM,self.__bqhCt,self.__bqhRq,self.responseText);}catch(e){}});
                        }catch(e){}
                        return origSend.apply(this,arguments);
                      };
                    }
                    var t=(document.body&&document.body.innerText)?document.body.innerText:'';
                    var s='';
                    var sc=document.scripts||[];
                    for(var i=0;i<sc.length&&s.length<160000;i++){
                      var tx=sc[i].textContent||'';
                      if(tx.indexOf('AF_initDataCallback')>=0||tx.indexOf('WIZ_global_data')>=0||tx.indexOf('SNlM0e')>=0){
                        s+=tx+'\n';
                      }
                    }
                    var hits=window.__bqhHits?window.__bqhHits.splice(0,window.__bqhHits.length):[];
                    return JSON.stringify({t:t.slice(0,16000),s:s,h:hits});
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
                            val xhrArr = obj.optJSONArray("h")

                            if (scriptText.isNotBlank()) {
                                extractWizTokensFromHtml(scriptText)
                                val parsedScript = QuotaTextParser.parseHtmlOrJsonPayload(
                                    payload = scriptText,
                                    prev = getBaseSnapshotForSession(sess),
                                    source = "WebView-Script"
                                )
                                if (parsedScript != null && parsedScript.hasLiveData) {
                                    recordLearnedQuotaEndpoint(rawUrl, "GET", rpcHint = "WebView-Script", forceFromParsedResponse = true)
                                    publishSnapshotForSession(parsedScript, sess)
                                }
                            }
                            if (xhrArr != null) {
                                for (i in 0 until xhrArr.length()) {
                                    val hObj = xhrArr.optJSONObject(i) ?: continue
                                    val hitUrl = hObj.optString("u", "")
                                    val hitMethod = hObj.optString("m", "GET").uppercase().ifBlank { "GET" }
                                    val hitCt = hObj.optString("ct", "").takeIf { it.isNotBlank() }
                                    val hitReq = hObj.optString("rq", "").takeIf { it.isNotBlank() }
                                    val hitResp = hObj.optString("rs", "")
                                    if (hitUrl.isBlank() || hitResp.isBlank()) continue
                                    val absUrl = resolveAbsoluteUrl(rawUrl, hitUrl) ?: continue
                                    if (!isSafeAutoLearnUrl(absUrl, hitMethod)) continue
                                    val parsedXhr = QuotaTextParser.parseHtmlOrJsonPayload(
                                        payload = hitResp,
                                        prev = getBaseSnapshotForSession(sess),
                                        source = "WebView-XHR"
                                    )
                                    if (parsedXhr != null && parsedXhr.hasLiveData) {
                                        recordLearnedQuotaEndpoint(
                                            url = absUrl,
                                            method = hitMethod,
                                            rpcHint = "WebView-XHR",
                                            contentType = hitCt ?: if (hitMethod == "POST") "application/x-www-form-urlencoded;charset=utf-8" else null,
                                            bodyBytes = hitReq?.toByteArray(StandardCharsets.UTF_8),
                                            forceFromParsedResponse = true
                                        )
                                        publishSnapshotForSession(parsedXhr, sess)
                                    }
                                }
                            }
                            if (domText.isNotBlank() && QuotaTextParser.fastHasQuotaKeyword(domText)) {
                                val parsedDom = QuotaTextParser.parseScreenQuotaTexts(
                                    text = domText,
                                    prev = getBaseSnapshotForSession(sess),
                                    source = "WebView-DOM"
                                )
                                if (parsedDom != null && parsedDom.hasLiveData) {
                                    // Record DOM surface URL only as fallback hint, never overwriting resolved XHR endpoints
                                    recordLearnedQuotaEndpoint(rawUrl, "GET", rpcHint = "WebView-DOM", forceFromParsedResponse = true)
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

    private fun resolveAbsoluteUrl(baseUrl: String, relativeOrAbs: String): String? {
        return try {
            URL(URL(baseUrl), relativeOrAbs).toString()
        } catch (_: Throwable) {
            if (relativeOrAbs.startsWith("http")) relativeOrAbs else null
        }
    }

    private fun scanVisibleUiForQuotaTexts(sessionTag: String, allowFullScreenParse: Boolean = false) {
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
                    val baseSnap = getBaseSnapshotForSession(sessionTag)
                    val snap = if (allowFullScreenParse) {
                        QuotaTextParser.parseScreenQuotaTexts(
                            text = text,
                            prev = baseSnap,
                            source = "InApp-Screen"
                        ) ?: QuotaTextParser.parseExhaustedBannerOnly(
                            rawText = text,
                            prev = baseSnap,
                            source = "InApp-Screen"
                        )
                    } else {
                        QuotaTextParser.parseExhaustedBannerOnly(
                            rawText = text,
                            prev = baseSnap,
                            source = "InApp-Screen"
                        )
                    }
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

    private fun readBoundedHttpBytes(conn: HttpURLConnection, maxBytes: Int): ByteArray? {
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val stream = conn.inputStream ?: return null
            stream.use { input ->
                val out = ByteArrayOutputStream(minOf(32_768, maxBytes))
                val buf = ByteArray(4096)
                var total = 0
                var r: Int
                while (input.read(buf).also { r = it } != -1 && total < maxBytes) {
                    val allow = minOf(r, maxBytes - total)
                    out.write(buf, 0, allow)
                    total += allow
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBoundedHttpResponse(conn: HttpURLConnection, maxChars: Int): String? {
        val bytes = readBoundedHttpBytes(conn, minOf(maxChars * 2, 2_097_152)) ?: return null
        if (bytes.isEmpty()) return null
        return try {
            val s = String(bytes, StandardCharsets.UTF_8)
            if (s.length > maxChars) s.substring(0, maxChars) else s
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseHttpResponseBytes(
        url: String,
        bytes: ByteArray,
        sessionTag: String,
        sourceTag: String
    ): QuotaHudView.Snapshot? {
        if (bytes.isEmpty()) return null
        val prev = getBaseSnapshotForSession(sessionTag)

        var firstPrintable: Byte = 0
        for (i in 0 until minOf(bytes.size, 32)) {
            val b = bytes[i]
            if (b != ' '.code.toByte() && b != '\n'.code.toByte() && b != '\r'.code.toByte() && b != '\t'.code.toByte()) {
                firstPrintable = b
                break
            }
        }
        val looksLikeTextOrHtml = firstPrintable == '{'.code.toByte() ||
            firstPrintable == '['.code.toByte() ||
            firstPrintable == '<'.code.toByte() ||
            firstPrintable == ')'.code.toByte()

        if (looksLikeTextOrHtml) {
            val text = try {
                String(bytes, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
            if (!text.isNullOrBlank()) {
                extractWizTokensFromHtml(text)
                val parsedText = QuotaTextParser.parseHtmlOrJsonPayload(
                    payload = text,
                    prev = prev,
                    source = sourceTag
                )
                if (parsedText != null && parsedText.hasLiveData) {
                    return parsedText
                }
            }
        }

        if (bytes.size >= 6) {
            val parsedPb = try {
                QuotaTextParser.scanProtoBufBytes(
                    rawBytes = bytes,
                    length = minOf(bytes.size, MAX_STREAM_ACCUM_BYTES),
                    prev = prev,
                    source = if (sourceTag.endsWith("-PB")) sourceTag else "$sourceTag-PB",
                    rpcMethodHint = url
                )
            } catch (_: Throwable) {
                null
            }
            if (parsedPb != null && parsedPb.hasLiveData) {
                return parsedPb
            }
        }

        if (!looksLikeTextOrHtml) {
            val text = try {
                String(bytes, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
            if (!text.isNullOrBlank()) {
                extractWizTokensFromHtml(text)
                val parsedFallback = QuotaTextParser.parseHtmlOrJsonPayload(
                    payload = text,
                    prev = prev,
                    source = sourceTag
                )
                if (parsedFallback != null && parsedFallback.hasLiveData) {
                    return parsedFallback
                }
            }
        }

        return null
    }

    private fun openQuotaHttpConnection(url: String, preferCronet: Boolean): HttpURLConnection? {
        if (preferCronet) {
            val engine = synchronized(activeCronetEngines) {
                activeCronetEngines.firstOrNull()
            }
            if (engine != null) {
                try {
                    val m = engine.javaClass.getMethod("openConnection", URL::class.java)
                    val conn = m.invoke(engine, URL(url)) as? HttpURLConnection
                    if (conn != null) return conn
                } catch (_: Throwable) {
                }
            }
        }
        return try {
            URL(url).openConnection() as? HttpURLConnection
        } catch (_: Throwable) {
            null
        }
    }

    private fun refreshBatchexecuteAtToken(bodyBytes: ByteArray, freshXsrf: String?): ByteArray {
        if (freshXsrf.isNullOrBlank() || bodyBytes.isEmpty()) return bodyBytes
        return try {
            val bodyStr = String(bodyBytes, StandardCharsets.UTF_8)
            val encodedXsrf = URLEncoder.encode(freshXsrf, "UTF-8")
            val updated = if (Regex("""(?:^|&)at=[^&]*""").containsMatchIn(bodyStr)) {
                bodyStr.replace(Regex("""(^|&)at=[^&]*"""), "$1at=$encodedXsrf")
            } else {
                "$bodyStr&at=$encodedXsrf&"
            }
            updated.toByteArray(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            bodyBytes
        }
    }

    private fun fetchActiveQuotaOverHttp(sessionTag: String, reason: RefreshReason) {
        if (!isForegroundActive.get()) return
        val now = System.currentTimeMillis()
        val isManual = reason == RefreshReason.MANUAL_TAP || reason == RefreshReason.ACCOUNT_SWITCH
        val minInterval = if (isManual) 2_000L else 10_000L
        if (now - lastHttpFetchMs.get() < minInterval) return

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
        // WITHOUT poisoning lastHttpFetchMs so subsequent requests carrying real headers trigger immediately.
        if (cookieHeader.isNullOrBlank() && authHeader.isNullOrBlank() && perHostAuthorizations.isEmpty()) {
            applySessionQueryEstimationIfNeeded(sessionTag)
            return
        }

        lastHttpFetchMs.set(now)

        var fetchedSnapshot: QuotaHudView.Snapshot? = null
        val mobileUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // 0. If we have learned batchexecute POST endpoints and Cookies, ensure fresh SNlM0e XSRF token first when missing
        val learnedCopy: List<LearnedEndpoint> = synchronized(learnedEndpointsLock) {
            ArrayList(learnedQuotaEndpoints.values)
        }
        val needsXsrf = learnedCopy.any { it.url.contains("batchexecute") && it.method.equals("POST", ignoreCase = true) }
        if (needsXsrf && cachedXsrfToken.isNullOrBlank() && !cookieHeader.isNullOrBlank()) {
            try {
                val appUrl = if (authUser != "0") "https://gemini.google.com/u/$authUser/app?hl=zh-CN" else "https://gemini.google.com/app?hl=zh-CN"
                openQuotaHttpConnection(appUrl, preferCronet = false)?.let { conn ->
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("User-Agent", mobileUa)
                    conn.setRequestProperty("X-Goog-AuthUser", authUser)
                    conn.setRequestProperty("Cookie", cookieHeader)
                    val html = readBoundedHttpResponse(conn, 524_288)
                    conn.disconnect()
                    if (!html.isNullOrBlank()) extractWizTokensFromHtml(html)
                }
            } catch (_: Throwable) {
            }
        }

        // 0b. Replay learned GET, POST, XHR & gRPC/Cronet quota endpoints (preferring non-SPA API endpoints over WebView-DOM pages)
        val sortedLearned = learnedCopy.asReversed().sortedBy { ep ->
            if (ep.rpcHint == "WebView-DOM") 1 else 0
        }
        for (ep in sortedLearned) {
            if (!isForegroundActive.get() || fetchedSnapshot != null) break
            val isPost = ep.method.equals("POST", ignoreCase = true)
            if (!isPost && !ep.method.equals("GET", ignoreCase = true)) continue
            if (isPost && ep.url.contains("batchexecute") && ep.bodyBase64 == null && cachedXsrfToken.isNullOrBlank()) continue

            val isGrpcOrGoogleApi = ep.contentType?.contains("grpc", ignoreCase = true) == true ||
                ep.url.contains("/\$rpc/") ||
                ep.url.contains("googleapis.com")
            val scopedAuth = resolveAuthForUrl(ep.url, authHeader)

            // Try Chromium HTTP/2 Cronet stack first for gRPC/Google APIs, then fall back to standard HttpURLConnection
            val useCronetVariants = if (isGrpcOrGoogleApi && synchronized(activeCronetEngines) { activeCronetEngines.isNotEmpty() }) {
                booleanArrayOf(true, false)
            } else {
                booleanArrayOf(false)
            }

            for (preferCronet in useCronetVariants) {
                if (!isForegroundActive.get() || fetchedSnapshot != null) break
                try {
                    val conn = openQuotaHttpConnection(ep.url, preferCronet = preferCronet) ?: continue
                    conn.requestMethod = ep.method.uppercase()
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    for ((hk, hv) in ep.extraHeaders) {
                        if (isSafeTechnicalHeader(hk.lowercase())) {
                            conn.setRequestProperty(hk, hv)
                        }
                    }
                    conn.setRequestProperty("User-Agent", mobileUa)
                    if (!ep.extraHeaders.keys.any { it.equals("Accept", ignoreCase = true) }) {
                        conn.setRequestProperty("Accept", "application/json, application/x-protobuf, application/grpc, text/plain, */*")
                    }
                    if (isGrpcOrGoogleApi) {
                        conn.setRequestProperty("TE", "trailers")
                        conn.setRequestProperty("grpc-accept-encoding", "gzip,identity")
                    }
                    conn.setRequestProperty("X-Goog-AuthUser", authUser)
                    if (!cookieHeader.isNullOrBlank()) conn.setRequestProperty("Cookie", cookieHeader)
                    if (!scopedAuth.isNullOrBlank()) conn.setRequestProperty("Authorization", scopedAuth)

                    if (isPost) {
                        conn.doOutput = true
                        val ct = ep.contentType ?: "application/x-protobuf"
                        conn.setRequestProperty("Content-Type", ct)
                        var bodyBytes = ep.bodyBase64?.let {
                            try {
                                Base64.decode(it, Base64.DEFAULT)
                            } catch (_: Throwable) {
                                null
                            }
                        } ?: ByteArray(0)
                        if (ep.url.contains("batchexecute") && bodyBytes.isNotEmpty()) {
                            bodyBytes = refreshBatchexecuteAtToken(bodyBytes, cachedXsrfToken)
                        }
                        recordOutboundProtocolFrameTrace(
                            transport = if (preferCronet) "Replay-Cronet-H2" else "Replay-HUC-H2",
                            target = ep.url,
                            teHeader = if (isGrpcOrGoogleApi) "trailers" else "trailers (H2)",
                            bodyBytes = bodyBytes,
                            extraHeaders = ep.extraHeaders + mapOf("content-type" to ct)
                        )
                        conn.outputStream.use { out ->
                            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
                            out.flush()
                        }
                    } else {
                        recordOutboundProtocolFrameTrace(
                            transport = if (preferCronet) "Replay-Cronet-H2-GET" else "Replay-HUC-H2-GET",
                            target = ep.url,
                            teHeader = if (isGrpcOrGoogleApi) "trailers" else "trailers (H2)",
                            bodyBytes = null,
                            extraHeaders = ep.extraHeaders
                        )
                    }

                    val bytes = readBoundedHttpBytes(conn, 262_144)
                    conn.disconnect()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val parsed = parseHttpResponseBytes(
                            url = ep.url,
                            bytes = bytes,
                            sessionTag = sessionTag,
                            sourceTag = ep.rpcHint ?: "Learned-HTTP"
                        )
                        if (parsed != null && parsed.hasLiveData) {
                            fetchedSnapshot = parsed
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // 1. Fetch Gemini Web /usage & /app pages up to 1.5 MB: captures SNlM0e/FdrFJe/cfb2h & AF_initDataCallback blobs
        if (fetchedSnapshot == null && !cookieHeader.isNullOrBlank() && isForegroundActive.get()) {
            val usagePrimary = if (authUser != "0") "https://gemini.google.com/u/$authUser/usage?hl=zh-CN" else "https://gemini.google.com/usage?hl=zh-CN"
            val appPrimary = if (authUser != "0") "https://gemini.google.com/u/$authUser/app?hl=zh-CN" else "https://gemini.google.com/app?hl=zh-CN"
            val landingCandidates = arrayOf(usagePrimary, appPrimary, "https://gemini.google.com/app?hl=en")
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
                    conn.setRequestProperty("Cookie", cookieHeader)
                    if (!authHeader.isNullOrBlank()) conn.setRequestProperty("Authorization", authHeader)

                    val html = readBoundedHttpResponse(conn, 1_572_864)
                    conn.disconnect()
                    if (!html.isNullOrBlank()) {
                        extractWizTokensFromHtml(html)
                        val isUsagePage = pageUrl.contains("/usage")
                        val parsed = QuotaTextParser.parseHtmlOrJsonPayload(
                            payload = html,
                            prev = getBaseSnapshotForSession(sessionTag),
                            source = if (isUsagePage) "Active-Web-Usage" else "Active-Web"
                        )
                        if (parsed != null && parsed.hasLiveData) {
                            fetchedSnapshot = parsed
                            recordLearnedQuotaEndpoint(pageUrl, "GET", rpcHint = if (isUsagePage) "Active-Web-Usage" else "Active-Web", forceFromParsedResponse = true)
                        }
                        if (cachedXsrfToken != null && fetchedSnapshot != null) break
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
            hasCompletedLiveFetchThisWake.set(true)
            safeLog(
                activeBridgeRef.get(),
                "Active HTTP quota resolved -> 5h=${fetchedSnapshot.fiveHourQuota} (${fetchedSnapshot.fiveHourReset}), Weekly=${fetchedSnapshot.weeklyLimit} (${fetchedSnapshot.weeklyReset})"
            )
            publishSnapshotForSession(fetchedSnapshot, sessionTag)
        } else {
            // Do not count speculative Web/batchexecute fallback misses as QuotaHealthMonitor errors,
            // otherwise native gRPC Gemini clients without Web cookies would self-wipe cached state.
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
            val lenBefore = out.length
            collectComposeSemanticsTexts(v, out)
            if (out.length == lenBefore) {
                collectComposeAccessibilityTexts(v, out)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                collectViewTexts(child, out, depth + 1)
            }
        }
    }

    private fun collectComposeSemanticsTexts(rootView: View, out: StringBuilder) {
        try {
            var owner: Any? = null
            try {
                owner = rootView.javaClass.getMethod("getSemanticsOwner").invoke(rootView)
            } catch (_: Throwable) {
            }
            if (owner == null) {
                for (f in collectInstanceFieldsSafe(rootView.javaClass)) {
                    if (f.name.contains("semanticsOwner", ignoreCase = true) ||
                        f.type.name.endsWith("SemanticsOwner")
                    ) {
                        f.isAccessible = true
                        owner = f.get(rootView)
                        if (owner != null) break
                    }
                }
            }
            if (owner == null) return

            var rootNode: Any? = null
            for (mName in arrayOf("getUnmergedRootSemanticsNode", "getRootSemanticsNode")) {
                try {
                    rootNode = owner.javaClass.getMethod(mName).invoke(owner)
                    if (rootNode != null) break
                } catch (_: Throwable) {
                }
            }
            if (rootNode == null) {
                for (f in collectInstanceFieldsSafe(owner.javaClass)) {
                    if (f.name.contains("Root", ignoreCase = true) ||
                        f.type.name.endsWith("SemanticsNode")
                    ) {
                        f.isAccessible = true
                        rootNode = f.get(owner)
                        if (rootNode != null) break
                    }
                }
            }
            if (rootNode == null) return

            val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            val count = intArrayOf(0)
            walkComposeSemanticsNodeSafe(rootNode, out, 0, count, visited)
        } catch (_: Throwable) {
        }
    }

    private fun walkComposeSemanticsNodeSafe(
        node: Any,
        out: StringBuilder,
        depth: Int,
        visitedCount: IntArray,
        visited: MutableSet<Any>
    ) {
        try {
            if (depth > 20 || visitedCount[0] > 220 || out.length > 12_288) return
            if (!visited.add(node)) return
            visitedCount[0]++

            val cfg = try {
                node.javaClass.getMethod("getConfig").invoke(node)
            } catch (_: Throwable) {
                null
            }
            if (cfg != null) {
                val iter: Iterator<*>? = when (cfg) {
                    is Iterable<*> -> cfg.iterator()
                    is Map<*, *> -> cfg.entries.iterator()
                    else -> null
                }
                if (iter != null) {
                    var safety = 0
                    while (iter.hasNext() && safety++ < 24 && out.length <= 12_288) {
                        val entry = iter.next() as? Map.Entry<*, *> ?: continue
                        val kStr = entry.key?.toString().orEmpty()
                        if (!kStr.contains("Text", ignoreCase = true) &&
                            !kStr.contains("ContentDescription", ignoreCase = true)
                        ) {
                            continue
                        }
                        val v = entry.value ?: continue
                        when (v) {
                            is List<*> -> {
                                for (item in v) {
                                    val s = item?.toString()?.trim().orEmpty()
                                    if (s.isNotEmpty() && s.length <= 240 && !s.contains("bard_quota_hud")) {
                                        out.append(s).append('\n')
                                    }
                                }
                            }
                            else -> {
                                val s = v.toString().trim()
                                if (s.isNotEmpty() && s.length <= 240 && !s.contains("bard_quota_hud")) {
                                    out.append(s).append('\n')
                                }
                            }
                        }
                    }
                }
            }

            val children = try {
                node.javaClass.getMethod("getChildren").invoke(node) as? List<*>
            } catch (_: Throwable) {
                null
            }
            if (children != null) {
                for (i in 0 until minOf(children.size, 32)) {
                    val child = children[i] ?: continue
                    walkComposeSemanticsNodeSafe(child, out, depth + 1, visitedCount, visited)
                }
            }
        } catch (_: Throwable) {
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

    private fun isZeroMetric(metric: String): Boolean {
        return metric == "0%" || metric.startsWith("0/")
    }

    private fun isNonZeroMetric(metric: String): Boolean {
        return metric != "--" && !isZeroMetric(metric)
    }

    private fun hasNonZeroQuotaData(snap: QuotaHudView.Snapshot): Boolean {
        return isNonZeroMetric(snap.fiveHourQuota) || isNonZeroMetric(snap.weeklyLimit)
    }

    private fun snapshotHasZeroRatio(snap: QuotaHudView.Snapshot): Boolean {
        return isZeroMetric(snap.fiveHourQuota) || isZeroMetric(snap.weeklyLimit)
    }

    private fun isProtobufSource(source: String): Boolean {
        return source == "Cronet-PB" ||
            source == "gRPC-PB" ||
            source == "OkHttp-PB" ||
            source.endsWith("-PB")
    }

    private fun isDirectQuotaSurfaceSource(source: String): Boolean {
        return source == "WebView-DOM" ||
            source == "WebView-XHR" ||
            source == "WebView-Script" ||
            source == "InApp-Dialog" ||
            source == "InApp-Screen" ||
            source == "Learned-HTTP" ||
            source.startsWith("Active-RPC-")
    }

    private fun publishSnapshotForSession(snapshot: QuotaHudView.Snapshot, sessionTag: String) {
        val rawClean = snapshot.sanitized()
        if (!rawClean.hasLiveData) return

        var cleanToApply = rawClean
        var shouldUpdateActiveHud = false
        var warningToEmit: String? = null
        var recoveryToLog: String? = null
        var zeroGuardDevEvent: (() -> Unit)? = null

        synchronized(sessionSnapshotsLock) {
            val prevForSession = if (sessionTag == activeSessionTag.get()) {
                latestSnapshot.get().sanitized()
            } else {
                perSessionSnapshots[sessionTag]?.sanitized() ?: QuotaHudView.Snapshot()
            }
            // Never let synthetic SessionEstimate overwrite resolved real quota metrics
            if (rawClean.source == "SessionEstimate" &&
                prevForSession.hasLiveData &&
                prevForSession.source != "SessionEstimate"
            ) {
                return
            }

            // In-memory Protobuf zero-debounce guard:
            // When Protobuf returns a 0% / 0 ratio while a valid non-zero quota snapshot was
            // previously cached in memory, emit a warning log + Dev Console "归零防护已触发" event
            // and temporarily hold the last cached non-zero numbers in HUD offline/gray mode.
            if (isProtobufSource(rawClean.source) && snapshotHasZeroRatio(rawClean)) {
                val cachedFromSession = cachedNonZeroSnapshots[sessionTag]?.takeIf { hasNonZeroQuotaData(it) }
                val cachedFromDefault = cachedNonZeroSnapshots[DEFAULT_SESSION_TAG]?.takeIf { hasNonZeroQuotaData(it) }
                val cachedNonZero = cachedFromSession
                    ?: cachedFromDefault
                    ?: prevForSession.takeIf { hasNonZeroQuotaData(it) }?.copy(isStaleOffline = false)

                val cachedAtMs = when {
                    cachedFromSession != null -> cachedNonZeroTimestamps[sessionTag] ?: System.currentTimeMillis()
                    cachedFromDefault != null -> cachedNonZeroTimestamps[DEFAULT_SESSION_TAG] ?: System.currentTimeMillis()
                    else -> System.currentTimeMillis()
                }
                val cachedTimeStr = formatTimestampMs(cachedAtMs)

                val zeroedFiveWithCache = isZeroMetric(rawClean.fiveHourQuota) &&
                    cachedNonZero != null && isNonZeroMetric(cachedNonZero.fiveHourQuota)
                val zeroedWeekWithCache = isZeroMetric(rawClean.weeklyLimit) &&
                    cachedNonZero != null && isNonZeroMetric(cachedNonZero.weeklyLimit)

                if (cachedNonZero != null && (zeroedFiveWithCache || zeroedWeekWithCache)) {
                    val now = System.currentTimeMillis()
                    val lastWarn = lastPbZeroWarnAtMs[sessionTag] ?: 0L
                    val isBurstChunk = (now - lastWarn) < 350L
                    val hits = if (isBurstChunk) {
                        maxOf(1, pbZeroDebounceHits[sessionTag] ?: 1)
                    } else {
                        ((pbZeroDebounceHits[sessionTag] ?: 0) + 1).also {
                            pbZeroDebounceHits[sessionTag] = it
                            lastPbZeroWarnAtMs[sessionTag] = now
                        }
                    }

                    val retainedFive = if (isZeroMetric(rawClean.fiveHourQuota) && isNonZeroMetric(cachedNonZero.fiveHourQuota)) {
                        cachedNonZero.fiveHourQuota
                    } else if (rawClean.fiveHourQuota != "--" && !isZeroMetric(rawClean.fiveHourQuota)) {
                        rawClean.fiveHourQuota
                    } else {
                        cachedNonZero.fiveHourQuota
                    }

                    val retainedWeek = if (isZeroMetric(rawClean.weeklyLimit) && isNonZeroMetric(cachedNonZero.weeklyLimit)) {
                        cachedNonZero.weeklyLimit
                    } else if (rawClean.weeklyLimit != "--" && !isZeroMetric(rawClean.weeklyLimit)) {
                        rawClean.weeklyLimit
                    } else {
                        cachedNonZero.weeklyLimit
                    }

                    val heldOfflineSnap = cachedNonZero.copy(
                        fiveHourQuota = retainedFive,
                        fiveHourReset = if (rawClean.fiveHourReset != "--") rawClean.fiveHourReset else cachedNonZero.fiveHourReset,
                        weeklyLimit = retainedWeek,
                        weeklyReset = if (rawClean.weeklyReset != "--") rawClean.weeklyReset else cachedNonZero.weeklyReset,
                        source = cachedNonZero.source,
                        isStaleOffline = true
                    ).sanitized()

                    val wkResetZh = QuotaTextParser.formatWeeklyResetForDisplay(heldOfflineSnap.weeklyReset, true)
                    val wkResetEn = QuotaTextParser.formatWeeklyResetForDisplay(heldOfflineSnap.weeklyReset, false)
                    val restoredSummaryZh = "5h=$retainedFive (${heldOfflineSnap.fiveHourReset}), 每周=$retainedWeek ($wkResetZh)"
                    val restoredSummaryEn = "5h=$retainedFive (${heldOfflineSnap.fiveHourReset}), Weekly=$retainedWeek ($wkResetEn)"

                    if (!isBurstChunk) {
                        warningToEmit = "[归零防护已触发] source=${rawClean.source} | 触发次数=#$hits | " +
                            "缓存快照时间戳=$cachedTimeStr | 已恢复的数值: $restoredSummaryZh (session=$sessionTag)"
                        val ctxCopy = appContext
                        if (ctxCopy != null) {
                            val srcCopy = rawClean.source
                            val detailZhCopy = "归零防护已触发 (第 $hits 次)：${srcCopy} 返回 0 [5h=${rawClean.fiveHourQuota}, 每周=${rawClean.weeklyLimit}]，" +
                                "已调用缓存补偿 | 缓存快照时间戳: $cachedTimeStr | 已恢复的数值: $restoredSummaryZh"
                            val detailEnCopy = "Zero-Guard Triggered (#$hits): $srcCopy returned 0 [5h=${rawClean.fiveHourQuota}, Weekly=${rawClean.weeklyLimit}], " +
                                "cache compensation applied | Cached Snapshot Timestamp: $cachedTimeStr | Restored Values: $restoredSummaryEn"
                            zeroGuardDevEvent = {
                                submitForegroundWork {
                                    QuotaSyncProvider.recordZeroGuardTriggeredEvent(
                                        context = ctxCopy,
                                        sessionId = sessionTag,
                                        source = srcCopy,
                                        cachedAtMs = cachedAtMs,
                                        restoredQuota = restoredSummaryZh,
                                        detailZh = detailZhCopy,
                                        detailEn = detailEnCopy,
                                        triggerCount = hits
                                    )
                                }
                            }
                        }
                    }

                    val changed = heldOfflineSnap != prevForSession
                    if (sessionTag != DEFAULT_SESSION_TAG) {
                        perSessionSnapshots[sessionTag] = heldOfflineSnap
                    }
                    if (sessionTag == activeSessionTag.get()) {
                        latestSnapshot.set(heldOfflineSnap)
                        shouldUpdateActiveHud = changed || !prevForSession.isStaleOffline
                    }
                    cleanToApply = heldOfflineSnap
                } else {
                    // Reject uncached transient Protobuf 0% noise if no cached non-zero snapshot exists
                    return
                }
            } else {
                // Anti-zeroing monotonicity guard: never let unverified memory/protobuf/AF scans
                // overwrite an active non-zero window with "0%", and on direct quota surfaces only
                // accept "0%" if there was no prior non-zero metric or the reset window actually rolled over.
                val directSource = isDirectQuotaSurfaceSource(rawClean.source)
                val fivePrevNonZero = prevForSession.fiveHourQuota != "--" && !isZeroMetric(prevForSession.fiveHourQuota)
                val weekPrevNonZero = prevForSession.weeklyLimit != "--" && !isZeroMetric(prevForSession.weeklyLimit)

                val fiveResetUnchanged = prevForSession.fiveHourReset != "--" &&
                    (rawClean.fiveHourReset == "--" || rawClean.fiveHourReset == prevForSession.fiveHourReset)
                val weekResetUnchanged = prevForSession.weeklyReset != "--" &&
                    (rawClean.weeklyReset == "--" || rawClean.weeklyReset == prevForSession.weeklyReset)

                val safeFive = if (isZeroMetric(rawClean.fiveHourQuota) &&
                    (!directSource || (fivePrevNonZero && fiveResetUnchanged))
                ) {
                    if (fivePrevNonZero) prevForSession.fiveHourQuota else "--"
                } else {
                    rawClean.fiveHourQuota
                }

                val safeWeek = if (isZeroMetric(rawClean.weeklyLimit) &&
                    (!directSource || (weekPrevNonZero && weekResetUnchanged))
                ) {
                    if (weekPrevNonZero) prevForSession.weeklyLimit else "--"
                } else {
                    rawClean.weeklyLimit
                }

                val candidateClean = rawClean.copy(
                    fiveHourQuota = safeFive,
                    weeklyLimit = safeWeek,
                    isStaleOffline = false
                ).sanitized()

                if (!candidateClean.hasLiveData) return

                // Preserve existing stale-offline state if incoming non-direct update didn't actually
                // supply any live non-zero metric improvement; clear stale-offline once live non-zero data arrives.
                val clearOffline = hasNonZeroQuotaData(candidateClean) || directSource
                cleanToApply = candidateClean.copy(
                    isStaleOffline = if (clearOffline) false else prevForSession.isStaleOffline
                )

                if (cleanToApply == prevForSession) return

                if (hasNonZeroQuotaData(cleanToApply) && !cleanToApply.isStaleOffline) {
                    val cacheNow = System.currentTimeMillis()
                    cachedNonZeroSnapshots[sessionTag] = cleanToApply
                    cachedNonZeroTimestamps[sessionTag] = cacheNow
                    if (sessionTag != DEFAULT_SESSION_TAG) {
                        cachedNonZeroSnapshots[DEFAULT_SESSION_TAG] = cleanToApply
                        cachedNonZeroTimestamps[DEFAULT_SESSION_TAG] = cacheNow
                    }
                    if (prevForSession.isStaleOffline) {
                        recoveryToLog = "Recovered live non-zero quota [5h=${cleanToApply.fiveHourQuota}, wk=${cleanToApply.weeklyLimit}]; cleared HUD offline state (session=$sessionTag)"
                    }
                }

                if (sessionTag != DEFAULT_SESSION_TAG) {
                    perSessionSnapshots[sessionTag] = cleanToApply
                }
                if (sessionTag == activeSessionTag.get()) {
                    latestSnapshot.set(cleanToApply)
                    shouldUpdateActiveHud = true
                }
            }
        }

        if (warningToEmit != null) {
            warnLog(activeBridgeRef.get(), warningToEmit!!)
        }
        zeroGuardDevEvent?.invoke()
        if (recoveryToLog != null) {
            safeLog(activeBridgeRef.get(), recoveryToLog!!)
        }

        if (hasNonZeroQuotaData(cleanToApply) && !cleanToApply.isStaleOffline) {
            QuotaHealthMonitor.recordHealthySuccess(sessionTag)
            val s = cleanToApply.source
            if (s != "SessionEstimate" && s != "InAppCache" && s != "Local-DataStore" && s != "Local-Prefs") {
                hasCompletedLiveFetchThisWake.set(true)
            }
        }

        if (!shouldUpdateActiveHud) return

        // Persist last verified live numbers cleanly (keep cached metrics, never lock disk mirror offline)
        saveSanitizedHostMirror(
            cleanToApply.copy(isStaleOffline = false),
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
                        snapshot = cleanToApply,
                        sessionId = sessionTag,
                        replaceSession = false
                    )
                    applyUnifiedState(merged)
                }
            }
        }
    }

    /**
     * QuotaHealthMonitor:
     * Monitors extreme pipeline faults per Google account session:
     * 1. Protobuf parse failure / malformed wire-format payload on quota-hinted traffic.
     * 2. 3 consecutive empty quota responses (`EMPTY_STREAK_THRESHOLD = 3`).
     *
     * When triggered:
     * - Emits a dedicated "链路异常" (`LINK_ANOMALY`) warning + Dev Console telemetry log.
     * - Executes a deep cleanup of that session's cache slots & transient transport state.
     */
    private object QuotaHealthMonitor {
        private const val EMPTY_STREAK_THRESHOLD = 3
        private const val ANOMALY_COOLDOWN_MS = 2_000L

        private val monitorLock = Any()
        private val consecutiveEmptyBySession = HashMap<String, Int>()
        private val lastAnomalyAtBySession = HashMap<String, Long>()
        @Volatile private var totalAnomalyCount: Int = 0

        fun recordHealthySuccess(sessionTag: String) {
            val target = sessionTag.ifBlank { DEFAULT_SESSION_TAG }
            synchronized(monitorLock) {
                consecutiveEmptyBySession.remove(target)
            }
        }

        fun recordProtobufParseFailure(
            sessionTag: String,
            source: String,
            detail: String
        ) {
            val target = sessionTag.ifBlank { DEFAULT_SESSION_TAG }
            triggerLinkAnomaly(
                sessionTag = target,
                source = source,
                reasonZh = "Protobuf 解析失败 ($detail)",
                reasonEn = "Protobuf parse failure ($detail)"
            )
        }

        fun recordEmptyResponse(
            sessionTag: String,
            source: String,
            reason: String
        ) {
            val target = sessionTag.ifBlank { DEFAULT_SESSION_TAG }
            var streak = 0
            var triggered = false
            synchronized(monitorLock) {
                streak = (consecutiveEmptyBySession[target] ?: 0) + 1
                consecutiveEmptyBySession[target] = streak
                if (streak >= EMPTY_STREAK_THRESHOLD) {
                    consecutiveEmptyBySession[target] = 0
                    triggered = true
                }
            }
            if (triggered) {
                triggerLinkAnomaly(
                    sessionTag = target,
                    source = source,
                    reasonZh = "连续 $streak 次返回空数据 ($reason)",
                    reasonEn = "$streak consecutive empty responses ($reason)"
                )
            }
        }

        private fun triggerLinkAnomaly(
            sessionTag: String,
            source: String,
            reasonZh: String,
            reasonEn: String
        ) {
            val now = System.currentTimeMillis()
            val anomalyNum: Int
            synchronized(monitorLock) {
                val prevAt = lastAnomalyAtBySession[sessionTag] ?: 0L
                if ((now - prevAt) < ANOMALY_COOLDOWN_MS) return
                lastAnomalyAtBySession[sessionTag] = now
                consecutiveEmptyBySession[sessionTag] = 0
                totalAnomalyCount += 1
                anomalyNum = totalAnomalyCount
            }

            val cleanedSlotInfo = performDeepSessionCleanup(sessionTag)
            val ts = formatTimestampMs(now)
            warnLog(
                activeBridgeRef.get(),
                "[链路异常 #$anomalyNum] session=$sessionTag | source=$source | $reasonZh | 已触发深层清理: $cleanedSlotInfo | time=$ts"
            )

            val ctx = appContext
            if (ctx != null) {
                val detailZh = "链路异常 (第 $anomalyNum 次)：$reasonZh [$source]，已自动触发深层清理 -> $cleanedSlotInfo"
                val detailEn = "Link Anomaly (#$anomalyNum): $reasonEn [$source], triggered deep cleanup -> $cleanedSlotInfo"
                submitForegroundWork {
                    QuotaSyncProvider.recordLinkAnomalyEvent(
                        context = ctx,
                        sessionId = sessionTag,
                        source = source,
                        cleanedSummary = cleanedSlotInfo,
                        detailZh = detailZh,
                        detailEn = detailEn,
                        anomalyCount = anomalyNum
                    )
                }
            }
        }
    }

    private fun performDeepSessionCleanup(sessionTag: String): String {
        val target = sessionTag.ifBlank { DEFAULT_SESSION_TAG }
        var removedLiveSlot = false
        var removedCacheSlot = false
        var clearedLearnedCount = 0

        synchronized(sessionSnapshotsLock) {
            removedLiveSlot = perSessionSnapshots.remove(target) != null
            removedCacheSlot = cachedNonZeroSnapshots.remove(target) != null
            cachedNonZeroTimestamps.remove(target)
            pbZeroDebounceHits.remove(target)
            lastPbZeroWarnAtMs.remove(target)
            if (target == activeSessionTag.get()) {
                latestSnapshot.set(QuotaHudView.Snapshot(source = "HealthReset"))
                perSessionSnapshots.remove(DEFAULT_SESSION_TAG)
                cachedNonZeroSnapshots.remove(DEFAULT_SESSION_TAG)
                cachedNonZeroTimestamps.remove(DEFAULT_SESSION_TAG)
            }
        }

        // Keep persisted learnedQuotaEndpoints intact across soft session resets so next cold start
        // still remembers the discovered quota RPC/URL template for replay.
        synchronized(learnedEndpointsLock) {
            clearedLearnedCount = learnedQuotaEndpoints.size
        }

        cachedXsrfToken = null
        cachedFsidToken = null
        cachedBuildLabel = null
        lastHttpFetchMs.set(0L)
        clearTransientBuffers(wipeCredentials = false)

        val ctx = appContext
        if (target == activeSessionTag.get() && ctx != null) {
            try {
                ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove("five")
                    .remove("five_reset")
                    .remove("week")
                    .remove("week_reset")
                    .putString("src", "HealthReset")
                    .apply()
            } catch (_: Throwable) {
            }
            if (isForegroundActive.get()) {
                refreshHudOnUiThread()
                submitForegroundWork {
                    QuotaSyncProvider.publishSessionQuota(
                        context = ctx,
                        snapshot = QuotaHudView.Snapshot(source = "HealthReset"),
                        sessionId = target,
                        replaceSession = true
                    )
                }
            }
        }

        return "Session槽位=$target(live=$removedLiveSlot, cache=$removedCacheSlot), 保留已学习端点=$clearedLearnedCount, 流/WIZ令牌已重置"
    }

    private fun loadSanitizedHostMirror(ctx: Context?) {
        if (ctx == null) return
        try {
            val prefs = ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
            loadLearnedEndpoints(prefs)
            val langCode = prefs.getString("ui_lang", null)
            val themeCode = prefs.getString("ui_theme", null)
            val savedSession = prefs.getString("session_id", null)
            val savedStrong = prefs.getBoolean("session_strong", false)
            if (langCode != null) {
                currentLang.set(QuotaHudView.Lang.fromCode(langCode))
            } else {
                currentLang.set(QuotaSyncProvider.defaultLangForDevice())
            }
            if (themeCode != null) currentTheme.set(QuotaHudView.ThemeMode.fromCode(themeCode))
            if (!savedSession.isNullOrBlank()) {
                activeSessionTag.set(savedSession)
                persistedSessionWasStrong = savedStrong
            }
            val handledSeq = prefs.getLong("last_handled_force_scan_seq", 0L)
            if (handledSeq > lastHandledForceScanSeq.get()) {
                lastHandledForceScanSeq.set(handledSeq)
            }

            val clean = QuotaHudView.Snapshot(
                fiveHourQuota = prefs.getString("five", "--") ?: "--",
                fiveHourReset = prefs.getString("five_reset", "--") ?: "--",
                weeklyLimit = prefs.getString("week", "--") ?: "--",
                weeklyReset = prefs.getString("week_reset", "--") ?: "--",
                source = prefs.getString("src", "InAppCache") ?: "InAppCache",
                isStaleOffline = false
            ).sanitized()

            if (clean.hasLiveData) {
                synchronized(sessionSnapshotsLock) {
                    latestSnapshot.set(clean)
                    val s = activeSessionTag.get()
                    if (s != DEFAULT_SESSION_TAG) {
                        perSessionSnapshots[s] = clean
                    }
                    if (hasNonZeroQuotaData(clean)) {
                        val now = System.currentTimeMillis()
                        cachedNonZeroSnapshots[s] = clean
                        cachedNonZeroTimestamps[s] = now
                        cachedNonZeroSnapshots[DEFAULT_SESSION_TAG] = clean
                        cachedNonZeroTimestamps[DEFAULT_SESSION_TAG] = now
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
            if (state == null) {
                warnLog(
                    activeBridgeRef.get(),
                    "QuotaSyncProvider IPC returned null — check module Autostart / Associated-Startup permission on MIUI/HyperOS/ColorOS"
                )
            }
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
        val curSnap = latestSnapshot.get().sanitized()
        val sameOrDefaultSession = state.sessionId.isBlank() ||
            curSess == DEFAULT_SESSION_TAG ||
            state.sessionId == curSess ||
            (!curSnap.hasLiveData && confirmedIdentityStrength < IdentityStrength.COOKIE_SID)

        if (sameOrDefaultSession) {
            val cleanSnap = state.snapshot.sanitized()
            val canUpgradeFromProvider = cleanSnap.hasLiveData &&
                curSnap != cleanSnap &&
                (!curSnap.hasLiveData || (curSnap.source == "SessionEstimate" && cleanSnap.source != "SessionEstimate"))
            if (canUpgradeFromProvider) {
                synchronized(sessionSnapshotsLock) {
                    latestSnapshot.set(cleanSnap)
                    if (hasNonZeroQuotaData(cleanSnap) && !cleanSnap.isStaleOffline) {
                        val now = System.currentTimeMillis()
                        cachedNonZeroSnapshots[curSess] = cleanSnap
                        cachedNonZeroTimestamps[curSess] = now
                        if (curSess != DEFAULT_SESSION_TAG) {
                            cachedNonZeroSnapshots[DEFAULT_SESSION_TAG] = cleanSnap
                            cachedNonZeroTimestamps[DEFAULT_SESSION_TAG] = now
                        }
                    }
                }
                uiChanged = true
            }
        }

        if (uiChanged) {
            saveSanitizedHostMirror(latestSnapshot.get(), currentLang.get(), currentTheme.get(), curSess)
            refreshHudOnUiThread()
        }

        if (state.forceScanSeq > 0L && state.forceScanSeq > lastHandledForceScanSeq.get()) {
            lastHandledForceScanSeq.set(state.forceScanSeq)
            try {
                appContext?.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putLong("last_handled_force_scan_seq", state.forceScanSeq)
                    ?.apply()
            } catch (_: Throwable) {
            }
            runForegroundFullProtocolDiagnostic("MAIN_ACTIVITY_SETTINGS")
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
                        showInAppHudSettingsDialog(activity)
                    },
                    draggableInFrame = true
                )

                // Ensure compatibility with FrameLayout or generic ViewGroup layouts and avoid status bar / punch-hole cutout
                val resIdStatus = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
                val statusBarPx = if (resIdStatus > 0) {
                    try {
                        activity.resources.getDimensionPixelSize(resIdStatus)
                    } catch (_: Throwable) {
                        0
                    }
                } else {
                    0
                }
                val topMarginPx = maxOf(
                    QuotaHudView.dp(activity, 52f),
                    statusBarPx + QuotaHudView.dp(activity, 8f)
                )
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
            activeSettingsQuotaRef.get()?.let { tv ->
                val curSnap = latestSnapshot.get().sanitized()
                val zh = currentLang.get() == QuotaHudView.Lang.ZH
                val sess = activeSessionTag.get()
                tv.text = if (zh) {
                    "会话: $sess | 源: ${curSnap.source}\n5h: ${curSnap.formatFiveHourDisplay()}  |  每周: ${curSnap.formatWeeklyDisplay(QuotaHudView.Lang.ZH)}"
                } else {
                    "Session: $sess | Source: ${curSnap.source}\n5h: ${curSnap.formatFiveHourDisplay()}  |  Weekly: ${curSnap.formatWeeklyDisplay(QuotaHudView.Lang.EN)}"
                }
            }
        }
    }

    private fun showInAppHudSettingsDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        postToMain(requireForeground = true) {
            if (activity.isFinishing || activity.isDestroyed) return@postToMain
            try {
                val dlg = Dialog(activity)
                dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
                val zh = currentLang.get() == QuotaHudView.Lang.ZH
                val snap = latestSnapshot.get().sanitized()
                val sess = activeSessionTag.get()

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = QuotaHudView.dp(activity, 16f)
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0F172A"))
                        cornerRadius = QuotaHudView.dp(activity, 18f).toFloat()
                        setStroke(maxOf(1, QuotaHudView.dp(activity, 1f)), Color.parseColor("#334155"))
                    }
                }

                val titleTv = TextView(activity).apply {
                    text = if (zh) "Gemini-Eye HUD 设置" else "Gemini-Eye HUD Settings"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                }

                val quotaTv = TextView(activity).apply {
                    setTextColor(Color.parseColor("#34D399"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.MONOSPACE
                    setPadding(0, QuotaHudView.dp(activity, 8f), 0, QuotaHudView.dp(activity, 10f))
                    text = if (zh) {
                        "会话: $sess | 源: ${snap.source}\n5h: ${snap.formatFiveHourDisplay()}  |  每周: ${snap.formatWeeklyDisplay(QuotaHudView.Lang.ZH)}"
                    } else {
                        "Session: $sess | Source: ${snap.source}\n5h: ${snap.formatFiveHourDisplay()}  |  Weekly: ${snap.formatWeeklyDisplay(QuotaHudView.Lang.EN)}"
                    }
                }
                activeSettingsQuotaRef = WeakReference(quotaTv)

                val traceTv = TextView(activity).apply {
                    setTextColor(Color.parseColor("#67E8F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.MONOSPACE
                    text = buildOutboundProtocolDiagnosticReport("PREVIEW", sess)
                }
                activeSettingsTraceRef = WeakReference(traceTv)

                val traceScroll = ScrollView(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#020617"))
                        cornerRadius = QuotaHudView.dp(activity, 10f).toFloat()
                        setStroke(maxOf(1, QuotaHudView.dp(activity, 1f)), Color.parseColor("#1E293B"))
                    }
                    val p = QuotaHudView.dp(activity, 8f)
                    setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        QuotaHudView.dp(activity, 170f)
                    ).apply {
                        topMargin = QuotaHudView.dp(activity, 8f)
                    }
                    addView(traceTv)
                }

                val forceScanBtn = Button(activity).apply {
                    isAllCaps = false
                    text = if (zh) "强制全量协议扫描" else "Force Full-Protocol Scan"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0891B2"))
                        cornerRadius = QuotaHudView.dp(activity, 10f).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    val pV = QuotaHudView.dp(activity, 10f)
                    setPadding(QuotaHudView.dp(activity, 12f), pV, QuotaHudView.dp(activity, 12f), pV)
                    setOnClickListener {
                        val report = runForegroundFullProtocolDiagnostic("HUD_SETTINGS_POPUP")
                        traceTv.text = report
                        postToMain(delayMs = 800L, requireForeground = true) {
                            traceTv.text = buildOutboundProtocolDiagnosticReport("HUD_SETTINGS_POST_REPLAY", activeSessionTag.get())
                        }
                    }
                }

                val btnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 2f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = QuotaHudView.dp(activity, 10f)
                    }
                }

                val openModuleBtn = Button(activity).apply {
                    isAllCaps = false
                    text = if (zh) "打开模块控制台" else "Open Module Console"
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = QuotaHudView.dp(activity, 10f).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = QuotaHudView.dp(activity, 6f)
                    }
                    setOnClickListener {
                        dlg.dismiss()
                        QuotaHudView.openModuleSettings(activity)
                    }
                }

                val closeBtn = Button(activity).apply {
                    isAllCaps = false
                    text = if (zh) "关闭" else "Close"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#334155"))
                        cornerRadius = QuotaHudView.dp(activity, 10f).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { dlg.dismiss() }
                }

                btnRow.addView(openModuleBtn)
                btnRow.addView(closeBtn)

                root.addView(titleTv)
                root.addView(quotaTv)
                root.addView(forceScanBtn)
                root.addView(traceScroll)
                root.addView(btnRow)

                dlg.setContentView(root)
                dlg.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                dlg.window?.setLayout(
                    QuotaHudView.dp(activity, 330f),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                dlg.show()
            } catch (_: Throwable) {
                QuotaHudView.openModuleSettings(activity)
            }
        }
    }
}
