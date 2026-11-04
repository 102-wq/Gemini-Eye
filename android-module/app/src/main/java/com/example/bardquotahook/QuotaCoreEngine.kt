package com.example.bardquotahook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dalvik.system.BaseDexClassLoader
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Enumeration
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Purely In-Process Gemini Quota Interception & Single-HUD Engine (`QuotaCoreEngine`).
 *
 * Strictly zero external web fallback and zero outbound HTTP polling.
 * Extracts quota exclusively from inside `com.google.android.apps.bard` (and Google host processes):
 * 1. Native Cronet (`org.chromium.net.UrlRequest` / `BidirectionalStream`) & gRPC (`io.grpc.Channel` /
 *    `ClientInterceptor` / `ClientCall.Listener`) raw `ByteArray` / `ByteBuffer` interception +
 *    ProtoBuf binary wire-format scanning (`QuotaTextParser.scanProtoBufBytes`).
 * 2. Memory / ViewModel / StateFlow / LiveData / Repository hooks & DEX state-holder class scanning
 *    with 100% `try-catch (Throwable)` reflection safety against R8 obfuscation.
 * 3. Single-Line Capsule HUD (`QuotaHudView`) with MIUIX / MD3 themes, EN / CN i18n, and automatic
 *    App System Overlay suppression when `ModernQuotaModule` (API 102) binds.
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

    private const val LOG_TAG = "BardQuotaHook"
    private const val MAX_STREAM_ACCUM_BYTES = 65_536
    private const val MAX_CHUNK_COPY_BYTES = 32_768

    val TARGET_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox"
    )

    private val hudInstalledGuard = AtomicBoolean(false)
    private val classLoaderWatcherInstalled = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val observerRegistered = AtomicBoolean(false)
    private val lifecycleRegistered = AtomicBoolean(false)
    private val dexScanCompleted = AtomicBoolean(false)

    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())
    private val currentLang = AtomicReference(QuotaHudView.Lang.ZH)
    private val currentTheme = AtomicReference(QuotaHudView.ThemeMode.MIUIX)

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
    private val cronetRollingStreams = ConcurrentHashMap<Int, RollingStreamBuffer>()
    private val grpcCallMethodNames = ConcurrentHashMap<Int, String>()

    private val lastMemoryScanMs = AtomicLong(0L)

    private val ipcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bard-quota-ipc").apply { isDaemon = true }
    }

    // Bounded worker pool so high-throughput Cronet/gRPC streams never stall or OOM host threads
    private val inspectorExecutor = ThreadPoolExecutor(
        1,
        2,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
        { r ->
            Thread(r, "bard-quota-inspect").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    @Volatile private var appContext: Context? = null
    @Volatile private var mainHandler: Handler? = null
    @Volatile private var providerObserver: ContentObserver? = null
    @Volatile private var hostPackageName: String = ""
    @Volatile private var hostProcessName: String = ""
    @Volatile private var activeBridgeName: String = ""
    @Volatile private var isModernApi102Bridge: Boolean = false
    @Volatile private var lastHeartbeatMs: Long = 0L

    private class RollingStreamBuffer(val urlHint: String?) {
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
     * Full in-process installation convenience entrypoint invoking all 3 purely in-app stages.
     */
    fun install(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        if (!isTargetHostPackage(packageName)) return
        installInAppHudAndExclusivity(packageName, processName, classLoader, bridge)
        installNativeGrpcAndCronetInterceptors(classLoader, bridge)
        installMemoryAndViewModelHooks(classLoader, bridge)
    }

    // =========================================================================
    // Reflection Safety Helpers (100% try-catch Throwable)
    // =========================================================================

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
        val key = "$category:${clazz.ClassLoaderId()}:${clazz.name}"
        return hookedDynamicClasses.add(key)
    }

    private fun Class<*>.ClassLoaderId(): Int {
        return try {
            System.identityHashCode(this.classLoader)
        } catch (_: Throwable) {
            0
        }
    }

    // =========================================================================
    // Stage 1: Single-Line Capsule HUD & Single-HUD Exclusion (API 102)
    // =========================================================================

    fun installInAppHudAndExclusivity(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        if (!isTargetHostPackage(packageName)) return
        activeBridgeRef.set(bridge)
        hostPackageName = packageName
        hostProcessName = processName
        activeBridgeName = bridge.bridgeName
        if (bridge.isModernApi102) {
            isModernApi102Bridge = true
        }

        if (!hudInstalledGuard.compareAndSet(false, true)) {
            appContext?.let { syncHeartbeatWithProviderAsync(it, force = true) }
            return
        }

        safeLog(
            bridge,
            "Stage 1 [HUD + Single-HUD Exclusion] bound via ${bridge.bridgeName} (api102=${bridge.isModernApi102}) in $packageName [$processName]"
        )

        // Immediately check if Application already exists in ActivityThread so API 102 suppresses
        // the App System Overlay immediately on module bind without waiting for next Activity resume.
        captureExistingApplicationIfReady()

        hookMethodSafe(
            bridge = bridge,
            clazz = Application::class.java,
            name = "onCreate",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val app = thisObj as? Application ?: return@cb
                onContextCaptured(app)
                registerActivityCallbacksOnce(app)
                bindHooksForClassLoader(app.classLoader, bridge)
                scheduleInProcessMemoryScan(delayMs = 1200L)
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onResume",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val activity = thisObj as? Activity ?: return@cb
                val ctx = activity.applicationContext ?: activity
                onContextCaptured(ctx)
                bindHooksForClassLoader(activity.classLoader, bridge)
                if (shouldDisplayHudInActivity(activity)) {
                    activeActivityRef.set(WeakReference(activity))
                    attachOrUpdateOverlay(activity)
                    syncHeartbeatWithProviderAsync(ctx, force = true)
                    scheduleInProcessMemoryScan(delayMs = 450L)
                    scheduleScreenBannerCheck()
                }
            }
        )

        if (classLoader != null) {
            bindHooksForClassLoader(classLoader, bridge)
        }
    }

    private fun captureExistingApplicationIfReady() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = atClass.getDeclaredMethod("currentApplication")
            currentAppMethod.isAccessible = true
            val app = currentAppMethod.invoke(null) as? Application
            if (app != null) {
                onContextCaptured(app)
                registerActivityCallbacksOnce(app)
            }
        } catch (_: Throwable) {
        }
    }

    private fun registerActivityCallbacksOnce(app: Application) {
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    if (shouldDisplayHudInActivity(activity)) {
                        activeActivityRef.set(WeakReference(activity))
                        attachOrUpdateOverlay(activity)
                        syncHeartbeatWithProviderAsync(activity.applicationContext ?: activity, force = true)
                        scheduleInProcessMemoryScan(delayMs = 450L)
                        scheduleScreenBannerCheck()
                    }
                }
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
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

    private fun onContextCaptured(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext ?: ctx
            loadSanitizedHostMirror(appContext)
        }
        val targetCtx = appContext ?: ctx
        registerUnifiedObserverOnce(targetCtx)
        registerReceiversOnce(targetCtx)
        syncHeartbeatWithProviderAsync(targetCtx, force = false)
    }

    // =========================================================================
    // Stage 2: Native Cronet & gRPC Raw ByteArray / ProtoBuf Stream Interception
    // =========================================================================

    fun installNativeGrpcAndCronetInterceptors(
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        activeBridgeRef.set(bridge)

        // Watch newly created dynamic ClassLoaders (e.g. GMS Chimera Cronet / split feature DEXs)
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

            // Passive in-process JSON payload inspection (never initiates network calls)
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

        // 1. Native Cronet Engine & UrlRequest / BidirectionalStream hooks
        installCronetHooksInClassLoader(classLoader, bridge)

        // 2. gRPC Channel / ClientInterceptor / ClientCall.Listener hooks
        installGrpcHooksInClassLoader(classLoader, bridge)

        // 3. Passive OkHttp ResponseBody hook (if OkHttp exists in this ClassLoader)
        installPassiveOkHttpHooksInClassLoader(classLoader, bridge)

        // 4. Reactive StateFlow / LiveData / ViewModel hooks in this ClassLoader
        installReactiveStateHooksInClassLoader(classLoader, bridge)
    }

    private fun installCronetHooksInClassLoader(cl: ClassLoader, bridge: HookBridge) {
        val builderNames = arrayOf(
            "org.chromium.net.CronetEngine\$Builder",
            "org.chromium.net.ExperimentalCronetEngine\$Builder",
            "org.chromium.net.ICronetEngineBuilder"
        )
        for (bName in builderNames) {
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

        val knownEngineImpls = arrayOf(
            "org.chromium.net.impl.CronetUrlRequestContext",
            "org.chromium.net.impl.JavaCronetEngine",
            "org.chromium.net.CronetEngine",
            "org.chromium.net.ExperimentalCronetEngine"
        )
        for (eName in knownEngineImpls) {
            val engineCls = findClassSafe(eName, cl) ?: continue
            hookConcreteCronetEngineClass(engineCls, bridge)
        }

        val knownRequestImpls = arrayOf(
            "org.chromium.net.impl.CronetUrlRequest",
            "org.chromium.net.impl.JavaUrlRequest"
        )
        for (rName in knownRequestImpls) {
            val reqCls = findClassSafe(rName, cl) ?: continue
            if (!markDynamicHookOnce("cronet_req_ctor", reqCls)) continue
            bridge.hookAllConstructors(
                clazz = reqCls,
                after = { thisObj, args ->
                    var urlStr: String? = null
                    for (arg in args) {
                        if (arg is String && (arg.startsWith("https://") || arg.startsWith("http://"))) {
                            urlStr = arg
                        } else if (arg != null && isCronetCallbackCandidate(arg.javaClass)) {
                            hookConcreteCronetCallbackClass(arg.javaClass, bridge)
                        }
                    }
                    if (thisObj != null && urlStr != null && !isStaticAssetUrl(urlStr)) {
                        pruneCronetStreamsIfNeeded()
                        cronetRollingStreams[System.identityHashCode(thisObj)] = RollingStreamBuffer(urlStr)
                    }
                }
            )
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
                    val url = args.firstOrNull { it is String } as? String
                    if (url != null && isStaticAssetUrl(url)) return@cb
                    for (arg in args) {
                        if (arg == null || arg is String) continue
                        val argCls = arg.javaClass
                        if (isCronetCallbackCandidate(argCls)) {
                            hookConcreteCronetCallbackClass(argCls, bridge)
                        }
                    }
                }
            )
        }
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

        // Hook onReadCompleted(request/stream, info, byteBuffer [, endOfStream])
        bridge.hookAllMethodsByName(
            clazz = callbackCls,
            methodName = "onReadCompleted",
            before = cb@{ _, args ->
                val reqObj = args.getOrNull(0) ?: return@cb
                val infoObj = args.getOrNull(1)
                val byteBuf = args.firstOrNull { it is ByteBuffer } as? ByteBuffer ?: return@cb

                val urlHint = extractCronetUrlSafe(infoObj)
                if (urlHint != null && isStaticAssetUrl(urlHint)) return@cb

                val copied = copyCronetByteBufferNonDestructive(byteBuf) ?: return@cb
                val reqId = System.identityHashCode(reqObj)
                submitCronetChunk(reqId, urlHint, copied)
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
                        cronetRollingStreams.remove(System.identityHashCode(reqObj))
                    }
                }
            )
        }
    }

    /**
     * Non-destructively copies fresh bytes from a Cronet `ByteBuffer` via `duplicate()`
     * without mutating the original buffer's `position`, `limit`, or `mark`.
     */
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

    private fun pruneCronetStreamsIfNeeded() {
        if (cronetRollingStreams.size < 48) return
        val cutoff = System.currentTimeMillis() - 60_000L
        val it = cronetRollingStreams.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.createdAtMs < cutoff || cronetRollingStreams.size > 64) {
                it.remove()
            }
        }
    }

    private fun submitCronetChunk(reqId: Int, urlHint: String?, chunk: ByteArray) {
        try {
            inspectorExecutor.execute {
                withReentrancyGuard {
                    pruneCronetStreamsIfNeeded()
                    val holder = cronetRollingStreams.getOrPut(reqId) { RollingStreamBuffer(urlHint) }
                    val combined = holder.appendAndSnapshot(chunk, chunk.size) ?: return@withReentrancyGuard
                    val snap = QuotaTextParser.scanProtoBufBytes(
                        rawBytes = combined,
                        length = combined.size,
                        prev = latestSnapshot.get(),
                        source = "Cronet-PB",
                        rpcMethodHint = urlHint ?: holder.urlHint
                    )
                    if (snap != null) {
                        publishSnapshot(snap)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    // -------------------------------------------------------------------------
    // gRPC Interception (`io.grpc.ClientInterceptor`, `Channel`, `ClientCall`)
    // -------------------------------------------------------------------------

    private fun installGrpcHooksInClassLoader(cl: ClassLoader, bridge: HookBridge) {
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

    private fun hookConcreteGrpcInterceptorClass(interceptorCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_interceptor", interceptorCls)) return
        bridge.hookAllMethodsByName(
            clazz = interceptorCls,
            methodName = "interceptCall",
            after = { _, args, result ->
                val methodDesc = args.getOrNull(0)
                val fullMethod = extractGrpcMethodNameSafe(methodDesc)
                if (result != null) {
                    if (fullMethod != null) {
                        if (grpcCallMethodNames.size > 128) grpcCallMethodNames.clear()
                        grpcCallMethodNames[System.identityHashCode(result)] = fullMethod
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
                val methodDesc = args.getOrNull(0)
                val fullMethod = extractGrpcMethodNameSafe(methodDesc)
                if (result != null) {
                    if (fullMethod != null) {
                        if (grpcCallMethodNames.size > 128) grpcCallMethodNames.clear()
                        grpcCallMethodNames[System.identityHashCode(result)] = fullMethod
                    }
                    hookConcreteGrpcClientCallClass(result.javaClass, bridge)
                }
            }
        )
    }

    private fun hookConcreteGrpcClientCallClass(callCls: Class<*>, bridge: HookBridge) {
        if (!markDynamicHookOnce("grpc_call", callCls)) return
        bridge.hookAllMethodsByName(
            clazz = callCls,
            methodName = "start",
            before = { thisObj, args ->
                val listener = args.getOrNull(0)
                if (listener != null) {
                    val rpcName = if (thisObj != null) {
                        grpcCallMethodNames[System.identityHashCode(thisObj)]
                    } else {
                        null
                    }
                    hookConcreteGrpcListenerClass(listener.javaClass, rpcName, bridge)
                }
            }
        )
    }

    private fun hookConcreteGrpcListenerClass(
        listenerCls: Class<*>,
        rpcHint: String?,
        bridge: HookBridge
    ) {
        if (!markDynamicHookOnce("grpc_listener", listenerCls)) return
        bridge.hookAllMethodsByName(
            clazz = listenerCls,
            methodName = "onMessage",
            after = cb@{ _, args, _ ->
                val msg = args.firstOrNull() ?: return@cb
                inspectGrpcResponseMessageAsync(msg, rpcHint)
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

    private fun inspectGrpcResponseMessageAsync(message: Any, rpcHint: String?) {
        try {
            inspectorExecutor.execute {
                withReentrancyGuard {
                    when (message) {
                        is ByteArray -> {
                            val snap = QuotaTextParser.scanProtoBufBytes(
                                rawBytes = message,
                                length = message.size,
                                prev = latestSnapshot.get(),
                                source = "gRPC-PB",
                                rpcMethodHint = rpcHint
                            )
                            if (snap != null) publishSnapshot(snap)
                        }
                        is ByteBuffer -> {
                            val bytes = copyCronetByteBufferNonDestructive(message)
                            if (bytes != null) {
                                val snap = QuotaTextParser.scanProtoBufBytes(
                                    rawBytes = bytes,
                                    length = bytes.size,
                                    prev = latestSnapshot.get(),
                                    source = "gRPC-PB",
                                    rpcMethodHint = rpcHint
                                )
                                if (snap != null) publishSnapshot(snap)
                            }
                        }
                        else -> {
                            // 1. Inspect deserialized ProtoLite / Kotlin response object fields directly
                            val fieldSnap = inspectObjectGraphForQuota(message, "gRPC-Model")
                            if (fieldSnap != null) {
                                publishSnapshot(fieldSnap)
                                return@withReentrancyGuard
                            }
                            // 2. If message exposes ProtoBuf `toByteArray()`, scan wire bytes when compact
                            val serialized = trySerializeProtoLite(message)
                            if (serialized != null && serialized.size in 6..MAX_STREAM_ACCUM_BYTES) {
                                val protoSnap = QuotaTextParser.scanProtoBufBytes(
                                    rawBytes = serialized,
                                    length = serialized.size,
                                    prev = latestSnapshot.get(),
                                    source = "gRPC-PB",
                                    rpcMethodHint = rpcHint
                                )
                                if (protoSnap != null) publishSnapshot(protoSnap)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
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

    private fun installPassiveOkHttpHooksInClassLoader(cl: ClassLoader, bridge: HookBridge) {
        val respBodyCls = findClassSafe("okhttp3.ResponseBody", cl) ?: return
        if (!markDynamicHookOnce("okhttp_body", respBodyCls)) return

        bridge.hookAllMethodsByName(
            clazz = respBodyCls,
            methodName = "bytes",
            after = cb@{ _, _, result ->
                val bytes = result as? ByteArray ?: return@cb
                if (bytes.size !in 6..MAX_STREAM_ACCUM_BYTES) return@cb
                inspectorExecutor.execute {
                    withReentrancyGuard {
                        val snap = QuotaTextParser.scanProtoBufBytes(
                            rawBytes = bytes,
                            length = bytes.size,
                            prev = latestSnapshot.get(),
                            source = "OkHttp-PB"
                        )
                        if (snap != null) publishSnapshot(snap)
                    }
                }
            }
        )

        bridge.hookAllMethodsByName(
            clazz = respBodyCls,
            methodName = "string",
            after = cb@{ _, _, result ->
                val raw = result as? String ?: return@cb
                if (raw.length > MAX_STREAM_ACCUM_BYTES || !QuotaTextParser.fastHasQuotaKeyword(raw)) return@cb
                inspectorExecutor.execute {
                    withReentrancyGuard {
                        val snap = QuotaTextParser.parseInAppStructuredPayload(
                            payload = raw,
                            prev = latestSnapshot.get(),
                            source = "OkHttp-JSON"
                        )
                        if (snap != null) publishSnapshot(snap)
                    }
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
            val raw = args.firstOrNull() as? String ?: return@ctor
            if (raw.length > MAX_STREAM_ACCUM_BYTES || !QuotaTextParser.fastHasQuotaKeyword(raw)) return@ctor
            withReentrancyGuard {
                val obj = thisObj as? JSONObject ?: return@withReentrancyGuard
                val snap = QuotaTextParser.inspectJsonObject(obj, latestSnapshot.get(), "InApp-JSON")
                if (snap != null) publishSnapshot(snap)
            }
        }

        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONObject::class.java,
            paramTypes = arrayOf(JSONTokener::class.java)
        ) { thisObj, _ ->
            withReentrancyGuard {
                val obj = thisObj as? JSONObject ?: return@withReentrancyGuard
                val snap = QuotaTextParser.inspectJsonObject(obj, latestSnapshot.get(), "InApp-JSON")
                if (snap != null) publishSnapshot(snap)
            }
        }
    }

    // =========================================================================
    // Stage 3: Memory / ViewModel / StateFlow / LiveData / Repository Hook
    // =========================================================================

    fun installMemoryAndViewModelHooks(
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        activeBridgeRef.set(bridge)
        if (classLoader != null) {
            bindHooksForClassLoader(classLoader, bridge)
            scheduleLoadedDexStateHolderScanOnce(classLoader)
        }
    }

    private fun installReactiveStateHooksInClassLoader(cl: ClassLoader, bridge: HookBridge) {
        // 1. Hook Jetpack ViewModel constructors to track every live ViewModel in memory
        val vmCls = findClassSafe("androidx.lifecycle.ViewModel", cl)
        if (vmCls != null && markDynamicHookOnce("androidx_vm", vmCls)) {
            bridge.hookAllConstructors(
                clazz = vmCls,
                after = { thisObj, _ ->
                    if (thisObj != null) {
                        try {
                            liveViewModels.add(thisObj)
                        } catch (_: Throwable) {
                        }
                        scheduleInProcessMemoryScan(delayMs = 900L)
                    }
                }
            )
        }

        // 2. Hook Kotlin Coroutines StateFlowImpl updates (setValue / updateState)
        val stateFlowNames = arrayOf(
            "kotlinx.coroutines.flow.StateFlowImpl"
        )
        for (sfName in stateFlowNames) {
            val sfCls = findClassSafe(sfName, cl) ?: continue
            if (!markDynamicHookOnce("stateflow_impl", sfCls)) continue
            val mutators = arrayOf("setValue", "updateState")
            for (mName in mutators) {
                bridge.hookAllMethodsByName(
                    clazz = sfCls,
                    methodName = mName,
                    after = cb@{ _, args, _ ->
                        val emitted = if (args.size >= 2) args[1] else args.firstOrNull()
                        if (emitted != null && isFastQuotaModelCandidate(emitted)) {
                            inspectStateEmissionAsync(emitted, "StateFlow")
                        }
                    }
                )
            }
        }

        // 3. Hook AndroidX LiveData / MutableLiveData updates (setValue / postValue)
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
                        val emitted = args.firstOrNull() ?: return@cb
                        if (isFastQuotaModelCandidate(emitted)) {
                            inspectStateEmissionAsync(emitted, "LiveData")
                        }
                    }
                )
            }
        }
    }

    /**
     * Fast O(1)-cached eligibility check so high-frequency UI StateFlows exit in nanoseconds
     * when carrying primitives, Strings, Compose layout structs, or non-quota models.
     */
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
                    // Allow compact R8-obfuscated data/proto classes with numeric + timestamp/nested fields
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
        try {
            inspectorExecutor.execute {
                withReentrancyGuard {
                    val snap = inspectObjectGraphForQuota(stateObj, source)
                    if (snap != null) {
                        publishSnapshot(snap)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Scans loaded DEX classes in the host ClassLoader once on a background daemon thread
     * for singleton Repository / StateFlow / LiveData holders containing quota fields.
     * Every reflection & DEX lookup is wrapped in `try-catch (Throwable)`.
     */
    private fun scheduleLoadedDexStateHolderScanOnce(classLoader: ClassLoader) {
        if (!dexScanCompleted.compareAndSet(false, true)) return
        try {
            inspectorExecutor.execute {
                try {
                    scanDexClassesForRepositoriesSafe(classLoader)
                } catch (_: Throwable) {
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

            while (currentCl != null && inspectedCount < 4000) {
                if (currentCl is BaseDexClassLoader) {
                    val pathListField = findFieldInHierarchy(currentCl.javaClass, "pathList")
                    val pathList = pathListField?.get(currentCl)
                    val dexElementsField = pathList?.let { findFieldInHierarchy(it.javaClass, "dexElements") }
                    val dexElements = dexElementsField?.get(pathList) as? Array<*>

                    if (dexElements != null) {
                        for (element in dexElements) {
                            if (element == null) continue
                            val dexFileField = findFieldInHierarchy(element.javaClass, "dexFile")
                            val dexFile = dexFileField?.get(element) ?: continue
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

                            while (entries.hasMoreElements() && inspectedCount < 4000) {
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
                                val matchesDomain = lower.contains("quota") ||
                                    lower.contains("usage") ||
                                    lower.contains("limit") ||
                                    lower.contains("allowance") ||
                                    lower.contains("ratelimit") ||
                                    lower.contains("tier")
                                if (!matchesDomain) continue

                                inspectedCount++
                                try {
                                    val loaded = Class.forName(clsName, false, currentCl)
                                    inspectLoadedClassStaticHolders(loaded)
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

    private fun inspectLoadedClassStaticHolders(clazz: Class<*>) {
        try {
            for (f in clazz.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val staticVal = f.get(null) ?: continue
                    if (staticVal.javaClass == clazz ||
                        isReactiveHolder(staticVal) ||
                        isFastQuotaModelCandidate(staticVal)
                    ) {
                        liveRepositories.add(staticVal)
                        val snap = inspectObjectGraphForQuota(staticVal, "RepoScan")
                        if (snap != null) publishSnapshot(snap)
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleInProcessMemoryScan(delayMs: Long = 0L, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastMemoryScanMs.get() < 3_500L) return
        lastMemoryScanMs.set(now)

        val trigger = {
            try {
                inspectorExecutor.execute {
                    withReentrancyGuard {
                        runLiveMemoryAndViewModelScan()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (delayMs > 0L) {
            postToMain(delayMs = delayMs) { trigger() }
        } else {
            trigger()
        }
    }

    /**
     * Walks active Activity `ViewModelStore`, tracked `ViewModel` instances, and `Application`
     * Dagger/Hilt `@Singleton` Repositories to extract live quota states and discover any
     * dynamically loaded Cronet/gRPC engines in isolated GMS Chimera ClassLoaders.
     */
    private fun runLiveMemoryAndViewModelScan() {
        val roots = ArrayList<Pair<Any, String>>(24)

        // 1. Collect ViewModels from active Activity's ViewModelStore
        val activity = activeActivityRef.get().get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            collectViewModelsFromActivitySafe(activity, roots)
        }

        // 2. Collect tracked ViewModel instances
        try {
            synchronized(liveViewModels) {
                for (vm in liveViewModels) {
                    if (roots.size < 40) roots.add(vm to "ViewModel")
                }
            }
        } catch (_: Throwable) {
        }

        // 3. Collect tracked Repository singletons & Application DI graph
        try {
            synchronized(liveRepositories) {
                for (repo in liveRepositories) {
                    if (roots.size < 56) roots.add(repo to "Repository")
                }
            }
        } catch (_: Throwable) {
        }

        val ctx = appContext
        if (ctx != null) {
            roots.add(ctx to "AppGraph")
        }

        for ((rootObj, tag) in roots) {
            val snap = inspectObjectGraphForQuota(rootObj, tag)
            if (snap != null) {
                publishSnapshot(snap)
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
        source: String
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
            val fromNamed = QuotaTextParser.mergeExtractedFields(namedFields, latestSnapshot.get(), source)
            if (fromNamed != null) return fromNamed
        }

        if (structuralWindows.isNotEmpty()) {
            val best5h = structuralWindows
                .filter { it.kind == QuotaTextParser.MetricKind.FIVE_HOUR }
                .maxByOrNull { it.confidence }
            val bestWk = structuralWindows
                .filter { it.kind == QuotaTextParser.MetricKind.WEEKLY }
                .maxByOrNull { it.confidence }

            // Require dual windows (5h + Weekly) OR high-confidence structural window
            if ((best5h != null && bestWk != null) ||
                (best5h?.confidence ?: 0) >= 3 ||
                (bestWk?.confidence ?: 0) >= 3
            ) {
                val base = latestSnapshot.get().sanitized()
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
        if (node == null || depth > 4 || visited.size >= 160 || windowsOut.size >= 8) return
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

        // Dynamically hook live GMS Chimera CronetEngine or gRPC Channel singletons found in DI memory!
        if (bridge != null) {
            if (clsName.startsWith("org.chromium.net.")) {
                bindHooksForClassLoader(cls.classLoader, bridge)
                if (clsName.contains("Cronet") && clsName.contains("Context") || clsName.endsWith("Engine")) {
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

        // Unwrap StateFlow / LiveData / Compose State / AtomicReference
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

        // Inspect fields of app/domain/ViewModel/Repository/ProtoLite classes
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
                    // Never read arbitrary user chat strings; only check strict quota metrics if field is named
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

        // Structural R8-obfuscated quota window match: future reset timestamp + usage/limit or fraction
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
                // ProtoLite google.protobuf.Timestamp { long seconds_; int nanos_; }
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

    // =========================================================================
    // Rate-Limit Banner Check (In-App Native Views Only, Zero Chat Scraping)
    // =========================================================================

    private fun scheduleScreenBannerCheck() {
        postToMain(delayMs = 550L) { scanForExhaustedBannerOnly() }
    }

    private fun scanForExhaustedBannerOnly() {
        val activity = activeActivityRef.get().get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView ?: return
        val sb = StringBuilder()
        collectViewTexts(decor, sb, 0)
        if (sb.isNotEmpty()) {
            val text = sb.toString()
            if (QuotaTextParser.fastHasQuotaKeyword(text)) {
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseExhaustedBannerOnly(text, latestSnapshot.get(), "InApp-Banner")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        }
    }

    private fun collectViewTexts(v: View, out: StringBuilder, depth: Int) {
        if (depth > 16 || out.length > 6144) return
        if (v.tag == QuotaHudView.HUD_VIEW_TAG) return
        if (v.visibility != View.VISIBLE) return

        if (v is TextView) {
            val tagStr = v.tag as? String
            if (tagStr == null || !tagStr.startsWith("bard_quota_hud")) {
                val t = v.text
                if (!t.isNullOrBlank() && t.length <= 200) {
                    out.append(t).append('\n')
                }
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                collectViewTexts(child, out, depth + 1)
            }
        }
    }

    // =========================================================================
    // Cross-Process Unified Sync & Single-Line Capsule HUD Rendering
    // =========================================================================

    private fun loadSanitizedHostMirror(ctx: Context?) {
        if (ctx == null) return
        try {
            val prefs = ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
            val langCode = prefs.getString("ui_lang", null)
            val themeCode = prefs.getString("ui_theme", null)
            if (langCode != null) currentLang.set(QuotaHudView.Lang.fromCode(langCode))
            if (themeCode != null) currentTheme.set(QuotaHudView.ThemeMode.fromCode(themeCode))

            val rawFive = prefs.getString("five", "--") ?: "--"
            val rawFiveReset = prefs.getString("five_reset", null)
                ?: prefs.getString("reset", "--")
                ?: "--"
            val rawWeek = prefs.getString("week", "--") ?: "--"
            val rawWeekReset = prefs.getString("week_reset", "--") ?: "--"
            val src = prefs.getString("src", "InAppCache") ?: "InAppCache"

            val clean = QuotaHudView.Snapshot(
                fiveHourQuota = rawFive,
                fiveHourReset = rawFiveReset,
                weeklyLimit = rawWeek,
                weeklyReset = rawWeekReset,
                source = src
            ).sanitized()

            if (clean.fiveHourQuota != rawFive ||
                clean.fiveHourReset != rawFiveReset ||
                clean.weeklyLimit != rawWeek ||
                clean.weeklyReset != rawWeekReset
            ) {
                prefs.edit()
                    .putString("five", clean.fiveHourQuota)
                    .putString("five_reset", clean.fiveHourReset)
                    .putString("week", clean.weeklyLimit)
                    .putString("week_reset", clean.weeklyReset)
                    .remove("reset")
                    .apply()
            }

            if (clean.hasLiveData) {
                latestSnapshot.set(clean)
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveSanitizedHostMirror(
        snapshot: QuotaHudView.Snapshot,
        lang: QuotaHudView.Lang,
        theme: QuotaHudView.ThemeMode
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
                .putString("ui_lang", lang.code)
                .putString("ui_theme", theme.code)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun applyUnifiedState(state: QuotaSyncProvider.UnifiedState?) {
        if (state == null) return
        val cleanSnap = state.snapshot.sanitized()
        var changed = false

        if (currentLang.get() != state.lang) {
            currentLang.set(state.lang)
            changed = true
        }
        if (currentTheme.get() != state.theme) {
            currentTheme.set(state.theme)
            changed = true
        }
        if (latestSnapshot.get() != cleanSnap && (cleanSnap.hasLiveData || !latestSnapshot.get().hasLiveData)) {
            latestSnapshot.set(cleanSnap)
            changed = true
        }

        if (changed) {
            saveSanitizedHostMirror(latestSnapshot.get(), currentLang.get(), currentTheme.get())
            refreshHudOnUiThread()
        }
    }

    private fun registerUnifiedObserverOnce(ctx: Context) {
        if (!observerRegistered.compareAndSet(false, true)) return
        providerObserver = QuotaSyncProvider.registerStateObserver(ctx) {
            pullFromUnifiedProviderAsync(ctx)
        }
    }

    private fun pullFromUnifiedProviderAsync(ctx: Context, delayMs: Long = 0L) {
        val runPull = {
            try {
                ipcExecutor.execute {
                    try {
                        val state = QuotaSyncProvider.readUnifiedState(ctx)
                        applyUnifiedState(state)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (delayMs > 0L) {
            postToMain(delayMs = delayMs) { runPull() }
        } else {
            runPull()
        }
    }

    private fun syncHeartbeatWithProviderAsync(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeatMs < 6_000L) return
        lastHeartbeatMs = now

        try {
            ipcExecutor.execute {
                val localSnap = latestSnapshot.get().sanitized()
                val state = QuotaSyncProvider.sendHeartbeatAndFetchState(
                    context = ctx,
                    pkg = hostPackageName,
                    proc = hostProcessName,
                    bridge = activeBridgeName,
                    isApi102 = isModernApi102Bridge,
                    localSnapshot = localSnap
                )
                if (state != null) {
                    applyUnifiedState(state)
                } else {
                    sendFallbackHeartbeatBroadcast(ctx, localSnap)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun sendFallbackHeartbeatBroadcast(ctx: Context, snap: QuotaHudView.Snapshot) {
        try {
            val clean = snap.sanitized()
            val hb = Intent(QuotaTextParser.ACTION_HOOK_HEARTBEAT).apply {
                setClassName(
                    QuotaSyncProvider.MODULE_PACKAGE,
                    "com.example.bardquotahook.HookTelemetryReceiver"
                )
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("pkg", hostPackageName)
                putExtra("proc", hostProcessName)
                putExtra("bridge", activeBridgeName)
                putExtra("api_102", isModernApi102Bridge)
                putExtra("five", clean.fiveHourQuota)
                putExtra("five_reset", clean.fiveHourReset)
                putExtra("week", clean.weeklyLimit)
                putExtra("week_reset", clean.weeklyReset)
                putExtra("src", clean.source)
            }
            ctx.sendBroadcast(hb)
        } catch (_: Throwable) {
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiversOnce(ctx: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        try {
            val filter = IntentFilter().apply {
                addAction(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC)
                addAction(QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val act = intent?.action ?: return
                    if (act != QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC &&
                        act != QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC
                    ) {
                        return
                    }

                    var uiUpdated = false

                    val langExtra = intent.getStringExtra("ui_lang")
                    if (!langExtra.isNullOrBlank()) {
                        currentLang.set(QuotaHudView.Lang.fromCode(langExtra))
                        uiUpdated = true
                    }
                    val themeExtra = intent.getStringExtra("ui_theme")
                    if (!themeExtra.isNullOrBlank()) {
                        currentTheme.set(QuotaHudView.ThemeMode.fromCode(themeExtra))
                        uiUpdated = true
                    }

                    if (intent.getBooleanExtra("rescan_memory", false)) {
                        scheduleInProcessMemoryScan(delayMs = 0L, force = true)
                        scheduleScreenBannerCheck()
                    }

                    if (intent.getBooleanExtra("ping", false) && context != null) {
                        syncHeartbeatWithProviderAsync(context, force = true)
                    }

                    val incoming = QuotaHudView.Snapshot(
                        fiveHourQuota = intent.getStringExtra("five") ?: "--",
                        fiveHourReset = intent.getStringExtra("five_reset")
                            ?: intent.getStringExtra("reset")
                            ?: "--",
                        weeklyLimit = intent.getStringExtra("week") ?: "--",
                        weeklyReset = intent.getStringExtra("week_reset") ?: "--",
                        source = intent.getStringExtra("src") ?: "Sync"
                    ).sanitized()

                    val prev = latestSnapshot.get().sanitized()
                    val merged = QuotaHudView.Snapshot(
                        fiveHourQuota = if (incoming.fiveHourQuota != "--") incoming.fiveHourQuota else prev.fiveHourQuota,
                        fiveHourReset = if (incoming.fiveHourReset != "--") incoming.fiveHourReset else prev.fiveHourReset,
                        weeklyLimit = if (incoming.weeklyLimit != "--") incoming.weeklyLimit else prev.weeklyLimit,
                        weeklyReset = if (incoming.weeklyReset != "--") incoming.weeklyReset else prev.weeklyReset,
                        source = incoming.source
                    ).sanitized()

                    if (merged.hasLiveData && merged != prev) {
                        latestSnapshot.set(merged)
                        uiUpdated = true
                    }

                    if (uiUpdated) {
                        saveSanitizedHostMirror(latestSnapshot.get(), currentLang.get(), currentTheme.get())
                        refreshHudOnUiThread()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Triggered when user taps Refresh (↻) on the in-app `QuotaHudView`.
     * Runs an immediate in-process ViewModel / StateFlow / Repository scan and queries
     * sibling Gemini processes via `QuotaSyncProvider` (strictly zero web/HTTP polling).
     */
    private fun triggerInProcessQuotaRefresh(ctx: Context) {
        scheduleInProcessMemoryScan(delayMs = 0L, force = true)
        scheduleScreenBannerCheck()

        try {
            ipcExecutor.execute {
                QuotaSyncProvider.requestInProcessRefresh(ctx, hostPackageName)
            }
        } catch (_: Throwable) {
        }

        pullFromUnifiedProviderAsync(ctx, delayMs = 600L)
        pullFromUnifiedProviderAsync(ctx, delayMs = 1800L)
    }

    private fun publishSnapshot(snapshot: QuotaHudView.Snapshot) {
        val clean = snapshot.sanitized()
        if (!clean.hasLiveData) return
        val current = latestSnapshot.get().sanitized()
        if (clean == current) return

        latestSnapshot.set(clean)
        saveSanitizedHostMirror(clean, currentLang.get(), currentTheme.get())
        refreshHudOnUiThread()

        val ctx = appContext
        if (ctx != null) {
            try {
                val sync = Intent(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC).apply {
                    if (hostPackageName.isNotEmpty()) setPackage(hostPackageName)
                    putExtra("five", clean.fiveHourQuota)
                    putExtra("five_reset", clean.fiveHourReset)
                    putExtra("week", clean.weeklyLimit)
                    putExtra("week_reset", clean.weeklyReset)
                    putExtra("src", clean.source)
                }
                ctx.sendBroadcast(sync)
            } catch (_: Throwable) {
            }

            try {
                ipcExecutor.execute {
                    val merged = QuotaSyncProvider.publishQuota(ctx, clean)
                    if (merged != null) {
                        applyUnifiedState(merged)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun postToMain(delayMs: Long = 0L, action: () -> Unit) {
        try {
            val looper = Looper.getMainLooper() ?: return
            val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
            val runnable = Runnable {
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
        postToMain {
            val hud = activeHudRef.get().get()
            if (hud != null && hud.isAttachedToWindow) {
                hud.applyPreferences(currentLang.get(), currentTheme.get())
                hud.updateSnapshot(latestSnapshot.get().sanitized())
            } else {
                val act = activeActivityRef.get().get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    attachOrUpdateOverlay(act)
                }
            }
        }
    }

    private fun attachOrUpdateOverlay(activity: Activity) {
        postToMain {
            if (activity.isFinishing || activity.isDestroyed) return@postToMain
            val decorGroup = activity.window?.decorView as? ViewGroup ?: return@postToMain

            var hud = decorGroup.findViewWithTag<View>(QuotaHudView.HUD_VIEW_TAG) as? QuotaHudView
            if (hud == null) {
                hud = QuotaHudView(
                    context = activity,
                    onRefreshClick = {
                        triggerInProcessQuotaRefresh(activity.applicationContext ?: activity)
                    },
                    onOpenUsageClick = {
                        QuotaHudView.openModuleSettings(activity)
                    },
                    draggableInFrame = true
                )

                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = QuotaHudView.dp(activity, 52f)
                    rightMargin = QuotaHudView.dp(activity, 10f)
                }
                decorGroup.addView(hud, lp)
            }

            hud.applyPreferences(currentLang.get(), currentTheme.get())
            hud.updateSnapshot(latestSnapshot.get().sanitized())
            hud.bringToFront()
            activeHudRef.set(WeakReference(hud))
        }
    }
}
