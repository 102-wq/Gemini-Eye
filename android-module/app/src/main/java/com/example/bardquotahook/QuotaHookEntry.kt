package com.example.bardquotahook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Executable

/**
 * Legacy Xposed / LSPosed Bridge API 82-93 Entry Point.
 * Registered via `src/main/assets/xposed_init`.
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

            QuotaCoreEngine.install(
                packageName = pkg,
                processName = lpparam.processName ?: pkg,
                classLoader = lpparam.classLoader,
                bridge = bridge
            )
        } catch (_: Throwable) {
        }
    }
}
