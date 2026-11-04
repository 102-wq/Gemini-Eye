package com.example.bardquotahook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Executable
import java.lang.reflect.Modifier

/**
 * Legacy Xposed / LSPosed Bridge API 82-93 Entry Point (`QuotaHookEntry`).
 * Registered via `src/main/assets/xposed_init`.
 *
 * Strictly In-App Quota Extraction (Zero Web Fallback / Zero HTTP Polling):
 * 1. Native Cronet & gRPC Stream Interception:
 *    Matches all processes (`lpparam.processName`) of `com.google.android.apps.bard` and hooks
 *    `org.chromium.net.UrlRequest` / `BidirectionalStream` and `io.grpc.Channel` / `ClientInterceptor` /
 *    `ClientCall.Listener` to scan raw ProtoBuf binary payloads for 5h & Weekly limits + reset epochs.
 * 2. Memory / ViewModel / StateFlow / LiveData / Repository Hook:
 *    Hooks `androidx.lifecycle.ViewModel`, `StateFlowImpl`, and `LiveData` and scans loaded DEX
 *    state holders with every reflection lookup wrapped in `try-catch (Throwable)`.
 * 3. Single-Line Capsule HUD & Unified Sync:
 *    Renders captured data onto the single-line `QuotaHudView` pill (MIUIX / MD3, ZH / EN) and
 *    suppresses duplicate overlays via `QuotaSyncProvider`.
 */
class QuotaHookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        try {
            XposedBridge.log("[BardQuota] initZygote -> ${startupParam.modulePath}")
        } catch (_: Throwable) {
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        try {
            val pkg = lpparam.packageName ?: return
            if (pkg == "android" || pkg == "com.example.bardquotahook") return
            if (!QuotaCoreEngine.isTargetHostPackage(pkg)) return

            val procName = lpparam.processName ?: pkg
            val hostClassLoader = lpparam.classLoader

            val bridge = object : QuotaCoreEngine.HookBridge {
                override val bridgeName: String = "XposedBridge-API-82"
                override val isModernApi102: Boolean = false

                override fun log(msg: String) {
                    try {
                        XposedBridge.log(msg)
                    } catch (_: Throwable) {
                    }
                }

                override fun hookExecutable(
                    executable: Executable,
                    before: ((thisObj: Any?, args: Array<Any?>) -> Unit)?,
                    after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)?
                ): (() -> Unit)? {
                    return try {
                        if (Modifier.isAbstract(executable.modifiers)) return null
                        executable.isAccessible = true

                        val unhook = XposedBridge.hookMethod(executable, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (before != null) {
                                    val raw = param.args
                                    val argsArr = if (raw != null) {
                                        Array<Any?>(raw.size) { i -> raw[i] }
                                    } else {
                                        emptyArray()
                                    }
                                    try {
                                        before(param.thisObject, argsArr)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (after != null) {
                                    val raw = param.args
                                    val argsArr = if (raw != null) {
                                        Array<Any?>(raw.size) { i -> raw[i] }
                                    } else {
                                        emptyArray()
                                    }
                                    try {
                                        after(param.thisObject, argsArr, param.result)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        })

                        val unhookFn: () -> Unit = {
                            try {
                                unhook.unhook()
                            } catch (_: Throwable) {
                            }
                        }
                        unhookFn
                    } catch (_: Throwable) {
                        null
                    }
                }
            }

            // 1. Bind In-App Single-Line Capsule HUD & Unified Exclusivity Heartbeat
            QuotaCoreEngine.installInAppHudAndExclusivity(
                packageName = pkg,
                processName = procName,
                classLoader = hostClassLoader,
                bridge = bridge
            )

            // 2. Hook Native Cronet Engine & gRPC Interceptors for Raw ProtoBuf Stream Scanning
            QuotaCoreEngine.installNativeGrpcAndCronetInterceptors(
                classLoader = hostClassLoader,
                bridge = bridge
            )

            // 3. Hook ViewModel / StateFlow / LiveData & Scan Loaded Repository State Holders
            QuotaCoreEngine.installMemoryAndViewModelHooks(
                classLoader = hostClassLoader,
                bridge = bridge
            )
        } catch (_: Throwable) {
        }
    }
}
