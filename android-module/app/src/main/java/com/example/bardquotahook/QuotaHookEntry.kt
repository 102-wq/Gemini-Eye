package com.example.bardquotahook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Executable
import java.lang.reflect.Modifier

/**
 * Legacy Xposed / LSPosed Bridge API 82-93 Entry Point (`QuotaHookEntry` — v2.1.0).
 * Registered via `src/main/assets/xposed_init`.
 *
 * Uses the exact same Zero-Background In-App Lifecycle and Dynamic Session/Cookie Interception
 * architecture as `ModernQuotaModule`:
 * - Skips non-UI background daemon processes (`isEligibleUiProcess`).
 * - Dynamically intercepts outgoing `Authorization` / `Cookie` / `X-Goog-AuthUser` headers on the fly
 *   so switching Google accounts inside Gemini seamlessly switches quota state.
 * - Attaches `QuotaHudView` strictly while Gemini is in the foreground and sleeps completely in background.
 */
class QuotaHookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // Zero background work at Zygote init
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        try {
            val pkg = lpparam.packageName ?: return
            if (pkg == "android" || pkg == "com.example.bardquotahook") return

            val procName = lpparam.processName ?: pkg
            if (!QuotaCoreEngine.isEligibleUiProcess(pkg, procName)) return

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

            // 1. Zero-Background Foreground HUD Lifecycle
            QuotaCoreEngine.installForegroundHudLifecycle(
                packageName = pkg,
                processName = procName,
                classLoader = hostClassLoader,
                bridge = bridge
            )

            // 2. Dynamic Outgoing Session/Cookie/Auth Interception + Query Event & ProtoBuf Stream Hooks
            QuotaCoreEngine.installDynamicSessionAndNetworkHooks(
                classLoader = hostClassLoader,
                bridge = bridge
            )

            // 3. Foreground-Only ViewModel / StateFlow / LiveData On-Demand Inspection
            QuotaCoreEngine.installForegroundMemoryHooks(
                classLoader = hostClassLoader,
                bridge = bridge
            )
        } catch (_: Throwable) {
        }
    }
}
