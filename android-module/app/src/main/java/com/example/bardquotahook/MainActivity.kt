package com.example.bardquotahook

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 96, 56, 96)
        }

        val titleView = TextView(this).apply {
            text = "Gemini-Eye 用量监控 (v1.4.0)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val subtitleView = TextView(this).apply {
            text = "Single-DEX (035 Stored) · 固定签名版\n若能看到 v1.4.0，说明新安装包已成功覆盖安装。"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 16, 0, 40)
        }

        val guideCard = TextView(this).apply {
            text = """
                必做检查清单（缺一不可）：
                
                1. 打开 LSPosed 管理器 -> 模块 -> 启用「Gemini 用量监控 Hook」
                
                2. 在作用域页面点击右上角「⋮」->「隐藏」-> 取消勾选「系统应用」！
                   （关键：Gemini 界面实际运行在系统应用 Google 内）
                
                3. 同时勾选以下两个应用：
                   • Gemini (com.google.android.apps.bard)
                   • Google (com.google.android.googlequicksearchbox)
                
                4. 进入系统设置 -> 应用 -> 找到「Google」点击「强行停止」，然后点击下方按钮打开 Gemini。
                   （成功挂载后会弹出 Toast 提示并显示右上角可拖拽悬浮窗；长按悬浮窗可折叠/展开，单击切换中英文）
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
            text = "打开 Gemini 测试悬浮窗 / Launch Gemini"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = 24f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
            layoutParams = lp
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")
                    ?: packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                } else {
                    Toast.makeText(this@MainActivity, "未检测到 Gemini 或 Google 应用", Toast.LENGTH_SHORT).show()
                }
            }
        }

        container.addView(titleView)
        container.addView(subtitleView)
        container.addView(guideCard)
        container.addView(launchBtn)
        scroll.addView(container)
        setContentView(scroll)
    }
}
