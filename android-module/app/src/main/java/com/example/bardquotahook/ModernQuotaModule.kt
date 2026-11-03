package com.example.bardquotahook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Executable

/**
 * Modern LibXposed API 101/102 Entry Point (Vector & Modern LSPosed).
 * Registered via `src/main/resources/META-INF/xposed/java_init.list`.
 */
class ModernQuotaModule : XposedModule() {

    @Volatile
    private var currentProcessName: String = "unknown"

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            currentProcessName = param.processName
            log(
                Log.INFO,
                "BardQuota",
                "ModernQuotaModule loaded in proc=${param.processName} ($frameworkName $frameworkVersion API $apiVersion)"
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

            val moduleRef = this
            val bridge = object : QuotaCoreEngine.HookBridge {
                override val bridgeName: String
                    get() = try {
                        "${moduleRef.frameworkName}-API-${moduleRef.apiVersion}"
                    } catch (_: Throwable) {
                        "LibXposed-102"
                    }

                override fun log(msg: String) {
                    try {
                        moduleRef.log(Log.INFO, "BardQuota", msg)
                    } catch (_: Throwable) {
                    }
                }

                override fun hookExecutable(
                    executable: Executable,
                    before: ((thisObj: Any?, args: Array<Any?>) -> Unit)?,
                    after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)?
                ): (() -> Unit)? {
                    return try {
                        val handle = moduleRef.hook(executable)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(XposedInterface.Hooker { chain ->
                                val thisObj = chain.thisObject
                                val rawArgs = chain.args
                                val argsArr = Array<Any?>(rawArgs.size) { i -> rawArgs[i] }
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
                        unhookFn
                    } catch (t: Throwable) {
                        try {
                            moduleRef.log(
                                Log.DEBUG,
                                "BardQuota",
                                "Hook skipped ${executable.declaringClass.name}.${executable.name}: ${t.javaClass.simpleName}"
                            )
                        } catch (_: Throwable) {
                        }
                        null
                    }
                }
            }

            QuotaCoreEngine.install(
                packageName = pkg,
                processName = currentProcessName,
                classLoader = param.classLoader,
                bridge = bridge
            )
        } catch (t: Throwable) {
            try {
                log(Log.ERROR, "BardQuota", "onPackageReady err: ${t.message}", t)
            } catch (_: Throwable) {
            }
        }
    }
}
