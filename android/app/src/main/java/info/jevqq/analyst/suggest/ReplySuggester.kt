package info.jevqq.analyst.suggest

import info.jevqq.analyst.Verdict
import info.jevqq.analyst.VerdictKey
import info.jevqq.analyst.data.ReplyLength
import info.jevqq.analyst.data.Tone
import info.jevqq.analyst.data.UserStyleProfile
import org.json.JSONObject

internal data class SuggestedReply(
    val actionLabel: String,
    val text: String,
)

/**
 * Result for one analysed peer message. When [holdOff] is true the overlay
 * shows a hold-off card instead of reply candidates.
 */
internal data class SuggestionResult(
    val holdOff: Boolean,
    val holdReason: String = "",
    val replies: List<SuggestedReply> = emptyList(),
)

/**
 * Local, template-based reply suggestions. The Jev systemone protocol only
 * returns structured scores (never text), so drafts are assembled on device
 * from the verdict's ranked actions. Nothing is auto-filled or sent; the
 * user copies manually.
 */
internal class ReplySuggester(
    spec: JSONObject,
    templates: JSONObject,
) {
    private val pauseLabel: String = spec.getJSONObject("actions").getString("pause")
    private val boundarySignal = "对方在设边界"
    private val labelToId: Map<String, String> = spec.getJSONObject("actions").let { actions ->
        actions.keys().asSequence().associate { id -> actions.getString(id) to id }
    }
    private val templates: JSONObject = templates.getJSONObject("templates")

    fun suggest(
        verdict: Verdict,
        style: UserStyleProfile = UserStyleProfile(),
        honorific: String = "",
        usedTexts: Set<String> = emptySet(),
    ): SuggestionResult {
        if (verdict.risk >= 7.0) return SuggestionResult(true,
            "回复风险偏高（${verdict.risk.toInt()}/9），建议先想清楚再回")
        if (boundarySignal in verdict.signals) return SuggestionResult(true,
            "对方在设边界，建议先尊重、暂停追问")
        val actions = verdict.rankedActions.filter { it.label != pauseLabel }
        if (actions.isEmpty()) return SuggestionResult(true, "此刻更适合先不回复，留点空间")

        val replies = ArrayList<SuggestedReply>(3)
        val used = usedTexts.toMutableSet()
        for (action in actions.take(3)) {
            val id = labelToId[action.label] ?: continue
            val text = pickTemplate(id, style, honorific, used) ?: continue
            used += text
            replies += SuggestedReply(action.label, text)
        }
        if (replies.isEmpty()) return SuggestionResult(true, "暂无合适的回复模板，建议手动组织语言")
        return SuggestionResult(false, replies = replies)
    }

    private fun pickTemplate(
        actionId: String,
        style: UserStyleProfile,
        honorific: String,
        used: Set<String>,
    ): String? {
        val entry = templates.optJSONObject(actionId) ?: return null
        val toneKeys = when (style.tone) {
            Tone.FORMAL -> listOf("formal", "casual")
            Tone.CASUAL -> listOf("casual", "formal")
            Tone.NEUTRAL -> listOf("casual", "formal")
        }
        val candidates = toneKeys.flatMap { key ->
            val array = entry.optJSONArray(key) ?: return@flatMap emptyList()
            (0 until array.length()).map { array.getString(it) }
        }.map { fillSlots(it, honorific) }
            .filter { text -> style.forbiddenWords.none { it.isNotEmpty() && text.contains(it) } }
            .filter { it !in used }
        if (candidates.isEmpty()) return null
        return when (style.length) {
            ReplyLength.SHORT -> candidates.minByOrNull { it.length }
            ReplyLength.LONG -> candidates.maxByOrNull { it.length }
            ReplyLength.ANY -> candidates.first()
        }
    }

    private fun fillSlots(template: String, honorific: String): String {
        var text = template
        text = if (honorific.isBlank()) text.replace("{称呼}", "").replace("，，", "，")
            else text.replace("{称呼}", honorific)
        return text.trim().trimStart('，').trim()
    }
}

/** In-memory suggestion cache with the same lifecycle as the verdict cache. */
internal class SuggestionBook(private val maxEntries: Int = 128) {
    private val entries = object : LinkedHashMap<VerdictKey, SuggestionResult>(160, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VerdictKey, SuggestionResult>?) =
            size > maxEntries
    }

    @Synchronized fun get(key: VerdictKey): SuggestionResult? = entries[key]

    @Synchronized fun put(key: VerdictKey, result: SuggestionResult) {
        entries[key] = result
    }

    @Synchronized fun textsFor(chat: String): Set<String> =
        entries.filterKeys { it.chat == chat }.values
            .flatMap { it.replies.map(SuggestedReply::text) }.toSet()

    @Synchronized fun clearChat(chat: String) {
        entries.keys.removeAll { it.chat == chat }
    }

    @Synchronized fun clear() = entries.clear()
}
