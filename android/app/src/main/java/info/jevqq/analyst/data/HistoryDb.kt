package info.jevqq.analyst.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local history database. Enum-like judgement fields stay readable for aggregation;
 * message text, quotes, chat names and suggestions are AES/GCM encrypted blobs.
 * Chats are indexed by a salted hash so the database alone does not reveal titles.
 */
internal class HistoryDb(context: Context) :
    SQLiteOpenHelper(context, "history.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE verdict_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_hmac TEXT NOT NULL,
                chat_name BLOB,
                msg BLOB,
                quote BLOB,
                intent TEXT NOT NULL,
                confidence REAL NOT NULL,
                risk REAL NOT NULL,
                emotion TEXT NOT NULL DEFAULT '',
                need TEXT NOT NULL DEFAULT '',
                signals TEXT NOT NULL DEFAULT '',
                actions TEXT NOT NULL DEFAULT '',
                reply_probability REAL,
                suggestions BLOB,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_history_chat ON verdict_history(chat_hmac, created_at)")
        db.execSQL(
            """CREATE TABLE contact_profile(
                chat_hmac TEXT PRIMARY KEY,
                name BLOB,
                note BLOB,
                relation BLOB,
                tone_pref TEXT NOT NULL DEFAULT '',
                length_pref TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val VERSION = 1
        const val TABLE_HISTORY = "verdict_history"
        const val TABLE_PROFILE = "contact_profile"
    }
}
