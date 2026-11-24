package info.jevqq.analyst.data

import android.content.ContentValues
import android.content.Context
import info.jevqq.analyst.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** One stored judgement. Original text stays encrypted at rest. */
internal data class HistoryEntry(
    val id: Long,
    val chatHmac: String,
    val chatName: String,
    val message: String,
    val quote: String,
    val intent: String,
    val confidence: Double,
    val risk: Double,
    val emotion: String,
    val need: String,
    val signals: List<String>,
    val actions: List<String>,
    val replyProbability: Double?,
    val suggestions: List<String>,
    val createdAt: Long,
)

internal data class ChatSummary(
    val chatHmac: String,
    val chatName: String,
    val count: Int,
    val lastAt: Long,
    val avgRisk: Double,
)

/** Pure serialization, unit-testable without Android. */
internal object HistoryCodec {
    fun encodeSuggestions(texts: List<String>): String =
        JSONArray().apply { texts.forEach { put(it) } }.toString()

    fun decodeSuggestions(json: String): List<String> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    fun encodeList(items: List<String>): String = items.joinToString("|")
    fun decodeList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split('|')
}

/** Append-only judgement history with retention pruning and one-tap wipe. */
internal class HistoryStore private constructor(
    private val db: HistoryDb,
    private val cipher: FieldCipher,
    private val salt: ByteArray,
) {
    constructor(context: Context) : this(
        HistoryDb(context.applicationContext),
        AesGcmFieldCipher(context.applicationContext),
        loadOrCreateSalt(context.applicationContext),
    )

    fun chatHmac(chat: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(chat.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    @Synchronized fun append(
        chat: String,
        message: String,
        quote: String,
        verdict: Verdict,
        suggestions: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (chat.isBlank()) return
        val values = ContentValues().apply {
            put("chat_hmac", chatHmac(chat))
            put("chat_name", cipher.encrypt(chat))
            put("msg", cipher.encrypt(message.take(MAX_TEXT)))
            put("quote", cipher.encrypt(quote.take(MAX_QUOTE)))
            put("intent", verdict.intent)
            put("confidence", verdict.confidence)
            put("risk", verdict.risk)
            put("emotion", verdict.emotion)
            put("need", verdict.need)
            put("signals", HistoryCodec.encodeList(verdict.signals))
            put("actions", HistoryCodec.encodeList(verdict.rankedActions.map { it.label }))
            if (verdict.replyProbability != null) put("reply_probability", verdict.replyProbability)
            put("suggestions", cipher.encrypt(HistoryCodec.encodeSuggestions(suggestions)))
            put("created_at", now)
        }
        db.writableDatabase.insert(HistoryDb.TABLE_HISTORY, null, values)
    }

    @Synchronized fun chats(): List<ChatSummary> {
        val out = LinkedHashMap<String, ChatSummary>()
        db.readableDatabase.query(
            HistoryDb.TABLE_HISTORY,
            arrayOf("chat_hmac", "chat_name", "COUNT(*)", "MAX(created_at)", "AVG(risk)"),
            null, null, "chat_hmac", null, "MAX(created_at) DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = runCatching { cipher.decrypt(cursor.getBlob(1)) }.getOrDefault("（无法解密）")
                out[cursor.getString(0)] = ChatSummary(
                    cursor.getString(0), name,
                    cursor.getInt(2), cursor.getLong(3), cursor.getDouble(4),
                )
            }
        }
        return out.values.toList()
    }

    @Synchronized fun timeline(chatHmac: String, limit: Int = 100, offset: Int = 0): List<HistoryEntry> {
        val rows = ArrayList<HistoryEntry>()
        db.readableDatabase.query(
            HistoryDb.TABLE_HISTORY, null,
            "chat_hmac = ?", arrayOf(chatHmac),
            null, null, "created_at DESC", "$offset,$limit",
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(readEntry(cursor))
        }
        return rows
    }

    /** intent -> count for one chat, for stats. */
    @Synchronized fun intentDistribution(chatHmac: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        db.readableDatabase.query(
            HistoryDb.TABLE_HISTORY, arrayOf("intent", "COUNT(*)"),
            "chat_hmac = ?", arrayOf(chatHmac), "intent", null, "COUNT(*) DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getInt(1)
        }
        return out
    }

    /** Daily average risk, oldest first, for trend display. */
    @Synchronized fun riskTrend(chatHmac: String, days: Int = 14): List<Pair<Long, Double>> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val out = ArrayList<Pair<Long, Double>>()
        db.readableDatabase.rawQuery(
            "SELECT created_at / 86400000, AVG(risk) FROM ${HistoryDb.TABLE_HISTORY} " +
                "WHERE chat_hmac = ? AND created_at >= ? GROUP BY created_at / 86400000 ORDER BY 1",
            arrayOf(chatHmac, since.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(cursor.getLong(0) * 86_400_000L to cursor.getDouble(1))
        }
        return out
    }

    @Synchronized fun deleteChat(chatHmac: String) {
        db.writableDatabase.delete(HistoryDb.TABLE_HISTORY, "chat_hmac = ?", arrayOf(chatHmac))
    }

    @Synchronized fun clearAll() {
        db.writableDatabase.delete(HistoryDb.TABLE_HISTORY, null, null)
        db.writableDatabase.delete(HistoryDb.TABLE_PROFILE, null, null)
    }

    /** Drop rows older than retentionDays and cap the table at maxRows newest entries. */
    @Synchronized fun prune(retentionDays: Int, maxRows: Int = 5000, now: Long = System.currentTimeMillis()) {
        val writable = db.writableDatabase
        writable.delete(HistoryDb.TABLE_HISTORY, "created_at < ?",
            arrayOf((now - retentionDays * 86_400_000L).toString()))
        writable.execSQL(
            "DELETE FROM ${HistoryDb.TABLE_HISTORY} WHERE id NOT IN " +
                "(SELECT id FROM ${HistoryDb.TABLE_HISTORY} ORDER BY created_at DESC LIMIT ?)",
            arrayOf(maxRows),
        )
    }

    private fun readEntry(cursor: android.database.Cursor): HistoryEntry {
        fun dec(index: Int): String =
            runCatching { cipher.decrypt(cursor.getBlob(index)) }.getOrDefault("")
        val reply = if (cursor.isNull(12)) null else cursor.getDouble(12)
        return HistoryEntry(
            id = cursor.getLong(0),
            chatHmac = cursor.getString(1),
            chatName = dec(2),
            message = dec(3),
            quote = dec(4),
            intent = cursor.getString(5),
            confidence = cursor.getDouble(6),
            risk = cursor.getDouble(7),
            emotion = cursor.getString(8),
            need = cursor.getString(9),
            signals = HistoryCodec.decodeList(cursor.getString(10)),
            replyProbability = reply,
            actions = HistoryCodec.decodeList(cursor.getString(11)),
            suggestions = HistoryCodec.decodeSuggestions(dec(13)),
            createdAt = cursor.getLong(14),
        )
    }

    private companion object {
        const val MAX_TEXT = 500
        const val MAX_QUOTE = 200

        fun loadOrCreateSalt(context: Context): ByteArray {
            val prefs = context.getSharedPreferences("history_meta", Context.MODE_PRIVATE)
            val saved = prefs.getString("hmac_salt", null)
            if (saved != null) return Base64.decode(saved, Base64.NO_WRAP)
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("hmac_salt", Base64.encodeToString(salt, Base64.NO_WRAP)).apply()
            return salt
        }
    }
}
