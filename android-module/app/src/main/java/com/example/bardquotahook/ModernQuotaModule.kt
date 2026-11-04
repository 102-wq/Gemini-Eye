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
 * Modern LibXposed API 102 Entry Point (`ModernQuotaModule`).
 * Registered via `src/main/resources/META-INF/xposed/java_init.list` + `module.prop`.
 *
 * Strictly In-App Quota Extraction (Zero Web Fallback / Zero HTTP Polling):
 * 1. Native Cronet & gRPC Stream Interception:
 *    Hooks `org.chromium.net.UrlRequest` / `BidirectionalStream` callbacks and `io.grpc.Channel` /
 *    `ClientInterceptor` / `ClientCall.Listener` inside `com.google.android.apps.bard` (across UI and
 *    background `:interactor` / `:search` / `:cronet` processes) to scan raw ProtoBuf binary frames.
 * 2. Memory / ViewModel / StateFlow / LiveData / Repository Hook:
 *    Hooks `androidx.lifecycle.ViewModel`, `kotlinx.coroutines.flow.StateFlowImpl`, and `LiveData`,
 *    and scans loaded DEX / Hilt singleton repositories with 100% `try-catch (Throwable)` safety.
 * 3. Single-Line Capsule HUD & Single-HUD Exclusion:
 *    Signals `isModernApi102 = true` on bind so `QuotaSyncProvider` + `OverlayHudController`
 *    automatically suppress the App System Overlay and render only the single in-app `QuotaHudView`
 *    capsule (`5h %` / `Weekly %` + reset timers, MIUIX / MD3 theme switchable, ZH / EN i18n).
 */
class ModernQuotaModule : XposedModule() {

    @Volatile
    private var currentProcessName: String = "unknown"

    private val activeUnhookCallbacks = CopyOnWriteArrayList<() -> Unit>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            currentProcessName = param.processName
            log(
                Log.INFO,
                "BardQuota",
                "ModernQuotaModule initialized in proc=${param.processName} ($frameworkName $frameworkVersion API $apiVersion)"
            )
        } catch (_: Throwable) {
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        try {
            currentProcessName = param.processName
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
            if (!QuotaCoreEngine.isTargetHostPackage(pkg)) return

            val procName = currentProcessName.ifEmpty { pkg }
            val hostClassLoader = param.classLoader
            val moduleRef = this

            val bridge = object : QuotaCoreEngine.HookBridge {
                override val bridgeName: String
                    get() = try {
                        "${moduleRef.frameworkName}-API-${moduleRef.apiVersion}"
                    } catch (_: Throwable) {
                        "LibXposed-API-102"
                    }

                // Signals API 102 active binding so App System Overlay auto-suppresses immediately
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

            // 1. Bind In-App Single-Line Capsule HUD & Auto-Suppress App System Overlay
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
        } catch (t: Throwable) {
            try {
                log(Log.ERROR, "BardQuota", "onPackageReady safe-caught: ${t.message}", t)
            } catch (_: Throwable) {
            }
        }
    }
}
