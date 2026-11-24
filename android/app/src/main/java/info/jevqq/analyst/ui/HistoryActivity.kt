package info.jevqq.analyst.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import info.jevqq.analyst.SettingsStore
import info.jevqq.analyst.data.ChatSummary
import info.jevqq.analyst.data.HistoryEntry
import info.jevqq.analyst.data.HistoryStore
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

/**
 * Local judgement history: chat list, then a per-chat timeline.
 * Available only when history opt-in is enabled; originals are decrypted on device only.
 */
class HistoryActivity : Activity() {
    private lateinit var store: HistoryStore
    private val uiFont = runCatching {
        Typeface.createFromFile(File("/system/fonts/NotoSansCJK-Regular.ttc"))
    }.getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SettingsStore(this).historyEnabled()) {
            showHint("历史记录未开启", "在设置或首次引导中开启“保存分析历史”后，这里才会记录判定结果。")
            return
        }
        store = HistoryStore(this)
        val chatHmac = intent.getStringExtra(EXTRA_CHAT_HMAC)
        if (chatHmac.isNullOrBlank()) showChatList() else showTimeline(chatHmac,
            intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty())
    }

    private fun showChatList() {
        val root = page("分析历史", "按会话查看本地保存的判定结果")
        val chats = store.chats()
        if (chats.isEmpty()) {
            root.addView(text("还没有记录。开启历史后，分析过的对方消息会出现在这里。", 13f, MUTED))
        }
        chats.forEach { summary -> root.addView(chatCard(summary), fullWidth()) }
        setContent(root)
    }

    private fun chatCard(summary: ChatSummary) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(Color.WHITE)
        addView(text(summary.chatName, 15f, INK, bold = true))
        addView(text("${summary.count} 条 · 平均风险 %.1f/9 · 最近 %s".format(
            summary.avgRisk, DateFormat.format("MM-dd HH:mm", Date(summary.lastAt))), 11f, MUTED))
        setOnClickListener {
            showTimeline(summary.chatHmac, summary.chatName)
        }
    }

    private fun showTimeline(chatHmac: String, chatName: String) {
        val root = page(chatName, "最近 100 条判定记录")
        val entries = store.timeline(chatHmac)
        if (entries.isEmpty()) root.addView(text("这个会话还没有记录。", 13f, MUTED))
        entries.forEach { entry -> root.addView(entryCard(entry), fullWidth()) }
        root.addView(Button(this).apply {
            text = "删除此会话的全部记录"
            setOnClickListener {
                store.deleteChat(chatHmac)
                finish()
            }
        }, fullWidth())
        setContent(root)
    }

    private fun entryCard(entry: HistoryEntry) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(Color.WHITE)
        addView(text(DateFormat.format("MM-dd HH:mm", Date(entry.createdAt)).toString(), 10f, MUTED))
        addView(text(entry.message.take(120), 13f, INK))
        addView(text("${entry.intent} · ${entry.emotion.ifBlank { "—" }} · 风险 ${entry.risk.roundToInt()}/9" +
            (entry.replyProbability?.let { " · 值得回复 ${(it * 100).roundToInt()}%" } ?: ""), 11f, GREEN, bold = true))
        if (entry.actions.isNotEmpty()) {
            addView(text("建议策略：" + entry.actions.take(3).joinToString("、"), 10f, MUTED))
        }
        if (entry.suggestions.isNotEmpty()) {
            addView(text("建议回复：" + entry.suggestions.joinToString(" / ") { it.take(30) }, 10f, ACCENT))
        }
    }

    private fun showHint(title: String, detail: String) {
        val root = page(title, detail)
        setContent(root)
    }

    private fun page(title: String, subtitle: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(BG)
        }
        root.addView(text(title, 22f, INK, bold = true))
        root.addView(text(subtitle, 13f, MUTED))
        return root
    }

    private fun setContent(root: LinearLayout) {
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
        setPadding(0, dp(4), 0, dp(4))
        typeface = Typeface.create(uiFont, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CHAT_HMAC = "chat_hmac"
        const val EXTRA_CHAT_NAME = "chat_name"
        private val BG = Color.rgb(246, 247, 248)
        private val INK = Color.rgb(35, 39, 43)
        private val MUTED = Color.rgb(114, 121, 126)
        private val GREEN = Color.rgb(57, 130, 105)
        private val ACCENT = Color.rgb(49, 91, 112)
    }
}
