package com.example.bardquotahook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Modern LibXposed API 102 Entry Point (`ModernQuotaModule` — v2.1.0).
 * Registered via `src/main/resources/META-INF/xposed/java_init.list` + `module.prop`.
 *
 * Clean, Dynamic, Zero-Background-Service Architecture:
 * 1. Dynamic Session & Cookie/Auth Interception:
 *    - Zero static or hardcoded cookies/tokens.
 *    - Dynamically intercepts active `Authorization`, `Cookie`, and `X-Goog-AuthUser` headers
 *      from Gemini's outgoing Cronet, gRPC `Metadata`, and OkHttp requests on the fly, seamlessly
 *      switching quota state whenever the user switches Google accounts inside Gemini.
 * 2. Zero-Background In-App Lifecycle:
 *    - Skips non-UI background daemon processes (`:interactor`, `:crash_report`, `:background`, etc.).
 *    - Zero background services, zero foreground services, and zero polling timers.
 *    - Attaches `QuotaHudView` ONLY when a Gemini `Activity` comes to the foreground, and sleeps
 *      completely (detaching HUD, unregistering observers, spinning worker threads down to 0)
 *      when Gemini goes to the background.
 *    - Triggers lightweight quota refresh events strictly when the user opens Gemini, sends a
 *      query, switches Google accounts, or taps the HUD refresh button.
 */
class ModernQuotaModule : XposedModule() {

    @Volatile
    private var currentProcessName: String = "unknown"

    private val activeUnhookCallbacks = CopyOnWriteArrayList<() -> Unit>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            currentProcessName = param.processName
        } catch (_: Throwable) {
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        try {
            currentProcessName = param.processName
            QuotaCoreEngine.forceSleepForHotReload()

            for (unhookFn in activeUnhookCallbacks) {
                try {
                    unhookFn.invoke()
                } catch (_: Throwable) {
                }
            }
            activeUnhookCallbacks.clear()

            for (oldHandle in param.oldHookHandles) {
                try {
                    oldHandle.unhook()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        try {
            val pkg = param.packageName
            if (pkg == "android" || pkg == "com.example.bardquotahook") return

            val procName = currentProcessName.ifEmpty { pkg }
            // Zero-Background Gate: never instrument non-UI background or hotword daemon processes
            if (!QuotaCoreEngine.isEligibleUiProcess(pkg, procName)) return

            val hostClassLoader = param.classLoader
            val moduleRef = this

            val bridge = object : QuotaCoreEngine.HookBridge {
                override val bridgeName: String
                    get() = try {
                        "${moduleRef.frameworkName}-API-${moduleRef.apiVersion}"
                    } catch (_: Throwable) {
                        "LibXposed-API-102"
                    }

                override val isModernApi102: Boolean = true

                override fun log(msg: String) {
                    try {
                        moduleRef.log(Log.INFO, "BardQuota", msg)
                    } catch (_: Throwable) {
                    }
                }

                override fun openRemotePreferences(group: String): SharedPreferences? {
                    return try {
                        val method = moduleRef.javaClass.methods.firstOrNull {
                            it.name == "getRemotePreferences" && it.parameterTypes.size == 1
                        }
                        method?.invoke(moduleRef, group) as? SharedPreferences
                    } catch (_: Throwable) {
                        null
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

                        val handle = moduleRef.hook(executable)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(XposedInterface.Hooker { chain ->
                                val thisObj = try {
                                    chain.thisObject
                                } catch (_: Throwable) {
                                    null
                                }
                                val rawArgs = try {
                                    chain.args
                                } catch (_: Throwable) {
                                    null
                                }
                                val argsArr = if (rawArgs != null) {
                                    Array<Any?>(rawArgs.size) { i -> rawArgs[i] }
                                } else {
                                    emptyArray()
                                }

                                if (before != null) {
                                    try {
                                        before(thisObj, argsArr)
                                    } catch (_: Throwable) {
                                    }
                                }

                                val result = chain.proceed()

                                if (after != null) {
                                    try {
                                        after(thisObj, argsArr, result)
                                    } catch (_: Throwable) {
                                    }
                                }
                                result
                            })

                        val unhookFn: () -> Unit = {
                            try {
                                handle.unhook()
                            } catch (_: Throwable) {
                            }
                        }
                        activeUnhookCallbacks.add(unhookFn)
                        unhookFn
                    } catch (t: Throwable) {
                        try {
                            moduleRef.log(
                                Log.DEBUG,
                                "BardQuota",
                                "Skipped hook ${executable.declaringClass.name}.${executable.name}: ${t.javaClass.simpleName}"
                            )
                        } catch (_: Throwable) {
                        }
                        null
                    }
                }
            }

            // 1. Zero-Background Foreground HUD Lifecycle (wakes on Gemini foreground, sleeps on background)
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
        } catch (t: Throwable) {
            try {
                log(Log.ERROR, "BardQuota", "onPackageReady safe-caught: ${t.message}", t)
            } catch (_: Throwable) {
            }
        }
    }
}
