package info.jevqq.analyst.suggest

import info.jevqq.analyst.RankedAction
import info.jevqq.analyst.RankedIntent
import info.jevqq.analyst.Verdict
import info.jevqq.analyst.data.ReplyLength
import info.jevqq.analyst.data.Tone
import info.jevqq.analyst.data.UserStyleProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySuggesterTest {
    private val spec = loadResource("decision_questions.json")
    private val templates = loadResource("reply_templates.json")
    private val suggester = ReplySuggester(spec, templates)

    private fun verdict(
        risk: Double = 2.0,
        signals: List<String> = emptyList(),
        actions: List<Pair<String, Double>> = listOf("直接回答对方的问题" to 3.5),
    ) = Verdict(
        intent = "问情况", confidence = .9, risk = risk,
        behavior = "提问", behaviorConfidence = .8,
        emotion = "平静", emotionConfidence = .8,
        need = "信息", needConfidence = .8,
        signals = signals, replyProbability = .8,
        rankedIntents = listOf(RankedIntent("问情况", .9)),
        rankedActions = actions.map { RankedAction(it.first, it.second) },
        backend = "test",
    )

    @Test
    fun `high risk holds off`() {
        val result = suggester.suggest(verdict(risk = 7.5))
        assertTrue(result.holdOff)
        assertTrue(result.holdReason.contains("风险"))
        assertTrue(result.replies.isEmpty())
    }

    @Test
    fun `boundary signal holds off`() {
        val result = suggester.suggest(verdict(signals = listOf("对方在设边界")))
        assertTrue(result.holdOff)
        assertTrue(result.holdReason.contains("边界"))
    }

    @Test
    fun `pause-only actions hold off`() {
        val result = suggester.suggest(verdict(actions = listOf("先不回复，留点空间" to 3.8)))
        assertTrue(result.holdOff)
    }

    @Test
    fun `normal message yields up to three replies`() {
        val result = suggester.suggest(verdict(actions = listOf(
            "直接回答对方的问题" to 3.5,
            "先问清楚对方的意思" to 3.0,
            "说明事实和当前进展" to 2.5,
        )))
        assertFalse(result.holdOff)
        assertEquals(3, result.replies.size)
        assertEquals("直接回答对方的问题", result.replies[0].actionLabel)
        assertTrue(result.replies.all { it.text.isNotBlank() })
        // No duplicate texts within one result
        assertEquals(result.replies.size, result.replies.map { it.text }.toSet().size)
    }

    @Test
    fun `tone selects template register`() {
        val casual = suggester.suggest(verdict(), UserStyleProfile(tone = Tone.CASUAL))
        val formal = suggester.suggest(verdict(), UserStyleProfile(tone = Tone.FORMAL))
        assertNotEquals(casual.replies[0].text, formal.replies[0].text)
    }

    @Test
    fun `short length picks the shortest template`() {
        val short = suggester.suggest(verdict(), UserStyleProfile(length = ReplyLength.SHORT))
        val long = suggester.suggest(verdict(), UserStyleProfile(length = ReplyLength.LONG))
        assertTrue(short.replies[0].text.length <= long.replies[0].text.length)
    }

    @Test
    fun `forbidden words filter templates`() {
        val every = templates.getJSONObject("templates").getJSONObject("answer")
        val banned = every.getJSONArray("casual").getString(0)
        val style = UserStyleProfile(tone = Tone.CASUAL, forbiddenWords = listOf(banned))
        val result = suggester.suggest(verdict(), style)
        assertTrue(result.replies.none { it.text == banned })
    }

    @Test
    fun `used texts are skipped`() {
        val first = suggester.suggest(verdict())
        val second = suggester.suggest(verdict(), usedTexts = first.replies.map { it.text }.toSet())
        assertTrue(second.replies.none { it.text in first.replies.map { r -> r.text } })
    }

    @Test
    fun `template file covers every non-pause action`() {
        val actions = spec.getJSONObject("actions")
        val pool = templates.getJSONObject("templates")
        for (id in actions.keys().asSequence()) {
            if (id == "pause") continue
            val entry = pool.optJSONObject(id) ?: error("模板缺少动作 $id")
            assertTrue("$id 缺少 formal", entry.getJSONArray("formal").length() > 0)
            assertTrue("$id 缺少 casual", entry.getJSONArray("casual").length() > 0)
        }
    }

    private fun loadResource(name: String): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())
}
