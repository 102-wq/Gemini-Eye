package com.example.bardquotahook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private fun isPkgInstalled(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Throwable) {
            packageManager.getLaunchIntentForPackage(pkg) != null
        }
    }

    private fun launchGeminiWithFallback() {
        val candidates = listOf(
            packageManager.getLaunchIntentForPackage("com.google.android.apps.bard"),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.google.android.apps.bard",
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
            },
            packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))
        )

        for (intent in candidates) {
            if (intent == null) continue
            val started = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bardInstalled = isPkgInstalled("com.google.android.apps.bard")
        val gsaInstalled = isPkgInstalled("com.google.android.googlequicksearchbox")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 96, 56, 96)
        }

        val titleView = TextView(this).apply {
            text = "Gemini-Eye 用量监控 (v1.7.0)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val statusView = TextView(this).apply {
            text = "检测状态 · Gemini 壳应用: ${if (bardInstalled) "已安装" else "未安装(走Google主程序)"} | Google 主程序: ${if (gsaInstalled) "已安装" else "未检测到"}"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 16, 0, 36)
        }

        val guideCard = TextView(this).apply {
            text = """
                抗 R8 混淆版 (v1.7.0 · Xposed API 102) 配置说明：
                
                1. 在 LSPosed 作用域页面点击右上角「⋮」->「隐藏」-> 取消勾选「系统应用」。
                2. 同时勾选「Gemini」与「Google (com.google.android.googlequicksearchbox)」。
                3. 在系统设置中「强行停止」Google 应用，再点击下方按钮启动 Gemini。
                4. 进入 Gemini「设置 -> 用量限制 (Usage Limits)」后，底层 JSONObject / String 构造器与界面层会自动捕获 quota / remaining 并实时刷新单行悬浮胶囊。
            """.trimIndent()
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(10f, 1.15f)
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#334155"))
            }
        }

        val launchBtn = Button(this).apply {
            text = "启动 Gemini / Launch Gemini"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
            setPadding(32, 32, 32, 32)
            setOnClickListener { launchGeminiWithFallback() }
        }

        container.addView(titleView)
        container.addView(statusView)
        container.addView(guideCard)
        container.addView(launchBtn)
        scroll.addView(container)
        setContentView(scroll)
    }
}
