package io.github.jsys12.bastion.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HistoryEntry(val id: Long, val url: String, val title: String, val visitedAt: Long, val visits: Int)
data class Bookmark(val id: Long, val url: String, val title: String, val onHome: Boolean, val position: Int)

/** History and bookmarks. Small enough that plain SQLite is simpler than Room. */
class BrowserDb(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 1) {

    /** Bumped on every change so UI can re-query. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, " +
                "title TEXT NOT NULL DEFAULT '', visited_at INTEGER NOT NULL, visits INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX history_time ON history(visited_at)")
        db.execSQL(
            "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, " +
                "title TEXT NOT NULL DEFAULT '', on_home INTEGER NOT NULL DEFAULT 1, position INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL)"
        )
        val defaults = listOf(
            "https://m.youtube.com/" to "YouTube",
            "https://ru.m.wikipedia.org/" to "Википедия",
            "https://vk.com/" to "ВКонтакте",
            "https://ya.ru/" to "Яндекс",
            "https://www.google.com/" to "Google",
            "https://habr.com/" to "Хабр",
            "https://github.com/" to "GitHub",
            "https://www.reddit.com/" to "Reddit",
        )
        defaults.forEachIndexed { i, (url, title) ->
            db.insert("bookmarks", null, ContentValues().apply {
                put("url", url); put("title", title); put("on_home", 1); put("position", i)
                put("created_at", System.currentTimeMillis())
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun changed() {
        _version.value = _version.value + 1
    }

    // ------------------------------------------------------------ history

    fun addVisit(url: String, title: String?) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val updated = db.compileStatement(
            "UPDATE history SET visits = visits + 1, visited_at = ?, title = CASE WHEN ? = '' THEN title ELSE ? END WHERE url = ?"
        ).apply {
            bindLong(1, now); bindString(2, title ?: ""); bindString(3, title ?: ""); bindString(4, url)
        }.executeUpdateDelete()
        if (updated == 0) {
            db.insert("history", null, ContentValues().apply {
                put("url", url); put("title", title ?: ""); put("visited_at", now); put("visits", 1)
            })
        }
        changed()
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
        changed()
    }

    fun history(query: String = "", limit: Int = 300): List<HistoryEntry> {
        val q = "%${query.trim()}%"
        val cursor = readableDatabase.rawQuery(
            "SELECT id, url, title, visited_at, visits FROM history WHERE url LIKE ? OR title LIKE ? " +
                "ORDER BY visited_at DESC LIMIT $limit",
            arrayOf(q, q),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
            }
        }
    }

    /** History entries matching typed text, most visited first (for address bar suggestions). */
    fun suggest(text: String, limit: Int = 5): List<HistoryEntry> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val like = "%$t%"
        val prefix = listOf("https://$t%", "http://$t%", "https://www.$t%", "http://www.$t%")
        val cursor = readableDatabase.rawQuery(
            "SELECT id, url, title, visited_at, visits FROM history WHERE url LIKE ? OR title LIKE ? " +
                "ORDER BY (url LIKE ? OR url LIKE ? OR url LIKE ? OR url LIKE ?) DESC, visits DESC, visited_at DESC LIMIT $limit",
            arrayOf(like, like) + prefix.toTypedArray(),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
            }
        }
    }

    fun deleteHistory(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
        changed()
    }

    // ------------------------------------------------------------ bookmarks

    fun bookmarks(): List<Bookmark> {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, url, title, on_home, position FROM bookmarks ORDER BY position ASC, id ASC", null
        )
        return cursor.use { c ->
            buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4))) }
        }
    }

    fun isBookmarked(url: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url))
        return c.use { it.moveToFirst() }
    }

    fun addBookmark(url: String, title: String, onHome: Boolean = true) {
        if (isBookmarked(url)) return
        val pos = readableDatabase.rawQuery("SELECT COALESCE(MAX(position), 0) + 1 FROM bookmarks", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title); put("on_home", if (onHome) 1 else 0); put("position", pos)
            put("created_at", System.currentTimeMillis())
        })
        changed()
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
        changed()
    }

    fun updateBookmark(id: Long, url: String, title: String, onHome: Boolean) {
        writableDatabase.update("bookmarks", ContentValues().apply {
            put("url", url); put("title", title); put("on_home", if (onHome) 1 else 0)
        }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
        changed()
    }
}
