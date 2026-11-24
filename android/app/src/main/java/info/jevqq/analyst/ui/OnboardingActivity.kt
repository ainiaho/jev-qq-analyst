package info.jevqq.analyst.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import info.jevqq.analyst.SettingsStore
import java.io.File

/**
 * First-run guide. P0 scope: explain the new local features and ask for
 * explicit opt-in before anything is persisted. Declining keeps the app
 * behaving exactly like the original release (process-memory only).
 */
class OnboardingActivity : Activity() {
    private val uiFont = runCatching {
        Typeface.createFromFile(File("/system/fonts/NotoSansCJK-Regular.ttc"))
    }.getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(246, 247, 248))
        }
        root.addView(text("欢迎使用 Jev · QQ 加强版", 22f, INK, bold = true))
        root.addView(text(
            "加强版在原版基础上新增了本地能力。开启前，请确认你理解数据如何被处理：",
            13f, MUTED))
        root.addView(text(
            "· 回复建议：完全由本机模板生成，不联网、不自动填入、不自动发送，复制粘贴由你手动完成。\n" +
                "· 分析历史（可选）：把判定结果加密保存在本机，按会话回看。默认保留 30 天，可随时一键清空。\n" +
                "· 原版承诺不变：不截图、不滚动、不读取数据库、不执行任何无障碍动作。",
            13f, INK))

        root.addView(Button(this).apply {
            text = "开启分析历史（本机加密保存）"
            setOnClickListener {
                store.setHistoryEnabled(true)
                store.setOnboardingDone()
                finish()
            }
        }, fullWidth())
        root.addView(Button(this).apply {
            text = "暂不开启（保持原版行为）"
            setOnClickListener {
                store.setHistoryEnabled(false)
                store.setOnboardingDone()
                finish()
            }
        }, fullWidth())
        root.addView(text("之后可以在设置页的“数据管理”里随时更改。", 11f, MUTED))

        setContentView(ScrollView(this).apply { addView(root) })
        applyFont(root)
    }

    private fun applyFont(view: android.view.View) {
        if (view is TextView) view.typeface = uiFont
        if (view is ViewGroup) for (i in 0 until view.childCount) applyFont(view.getChildAt(i))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(6), 0, dp(6))
        typeface = Typeface.create(uiFont, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val INK = Color.rgb(35, 39, 43)
        val MUTED = Color.rgb(114, 121, 126)
    }
}
