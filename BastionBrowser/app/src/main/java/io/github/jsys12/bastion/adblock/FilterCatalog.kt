package io.github.jsys12.bastion.adblock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FilterListInfo(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val category: String,
    val defaultEnabled: Boolean,
    val trusted: Boolean,
    val bundled: Boolean,
    val homepage: String,
    val custom: Boolean = false,
)

/** Built-in catalog (assets/filter_catalog.json) plus lists the user added by URL. */
class FilterCatalog(private val context: Context) {
    val builtIn: List<FilterListInfo> by lazy {
        val json = context.assets.open("filter_catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FilterListInfo(
                id = o.getString("id"),
                title = o.getString("title"),
                description = o.optString("desc"),
                url = o.optString("url"),
                category = o.optString("category", "ads"),
                defaultEnabled = o.optBoolean("enabled"),
                trusted = o.optBoolean("trusted"),
                bundled = o.optBoolean("bundle"),
                homepage = o.optString("home"),
            )
        }
    }

    private val prefs = context.getSharedPreferences("filter_lists", Context.MODE_PRIVATE)

    fun customLists(): List<FilterListInfo> {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FilterListInfo(
                id = o.getString("id"), title = o.getString("title"), description = o.optString("url"),
                url = o.getString("url"), category = "custom", defaultEnabled = true, trusted = false,
                bundled = false, homepage = "", custom = true,
            )
        }
    }

    fun all(): List<FilterListInfo> = builtIn + customLists()

    fun addCustom(url: String, title: String): FilterListInfo {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        val id = "custom_" + Integer.toHexString(url.hashCode())
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("id") == id) return customLists().first { it.id == id }
        }
        arr.put(JSONObject().put("id", id).put("title", title).put("url", url))
        prefs.edit().putString("custom", arr.toString()).apply()
        setEnabled(id, true)
        return customLists().first { it.id == id }
    }

    fun removeCustom(id: String) {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("id") != id) out.put(arr.getJSONObject(i))
        prefs.edit().putString("custom", out.toString()).remove("enabled_$id").apply()
    }

    fun isEnabled(list: FilterListInfo): Boolean = prefs.getBoolean("enabled_${list.id}", list.defaultEnabled)

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun enabled(): List<FilterListInfo> = all().filter { isEnabled(it) }

    // Download metadata
    fun lastUpdated(id: String): Long = prefs.getLong("updated_$id", 0)
    fun etag(id: String): String? = prefs.getString("etag_$id", null)
    fun lastModified(id: String): String? = prefs.getString("lastmod_$id", null)
    fun lastError(id: String): String? = prefs.getString("error_$id", null)

    fun recordDownload(id: String, etag: String?, lastModified: String?) {
        prefs.edit().putLong("updated_$id", System.currentTimeMillis())
            .putString("etag_$id", etag).putString("lastmod_$id", lastModified).remove("error_$id").apply()
    }

    fun recordNotModified(id: String) {
        prefs.edit().putLong("updated_$id", System.currentTimeMillis()).remove("error_$id").apply()
    }

    fun recordError(id: String, message: String) {
        prefs.edit().putString("error_$id", message).apply()
    }
}
