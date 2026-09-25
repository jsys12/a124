package io.github.jsys12.bastion.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App preferences exposed as observable values. */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val all = ArrayList<Pref<*>>()

    inner class Pref<T>(
        val key: String,
        private val default: T,
        private val read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(prefs.read(key, default))
        val flow: StateFlow<T> get() = state
        var value: T
            get() = state.value
            set(v) {
                if (v == state.value) return
                state.value = v
                prefs.edit().apply { write(key, v) }.apply()
                onChange?.invoke(key)
            }

        init {
            all.add(this)
        }

        fun reset() {
            value = default
        }
    }

    /** Called after any preference changes (used to refresh WebView settings / filter config). */
    @Volatile
    var onChange: ((String) -> Unit)? = null

    private fun bool(key: String, def: Boolean) =
        Pref(key, def, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun int(key: String, def: Int) = Pref(key, def, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })
    private fun long(key: String, def: Long) = Pref(key, def, { k, d -> getLong(k, d) }, { k, v -> putLong(k, v) })
    private fun string(key: String, def: String) =
        Pref(key, def, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    private fun stringSet(key: String) =
        Pref(key, emptySet<String>(), { k, d -> getStringSet(k, d)?.toSet() ?: d }, { k, v -> putStringSet(k, v) })

    // Protection
    val adblock = bool("adblock", true)
    val cosmetic = bool("cosmetic", true)
    val strictMode = bool("strict_mode", true)
    val blockPopups = bool("block_popups", true)
    val blockAppRedirects = bool("block_app_redirects", true)
    val strictBlocking = bool("strict_blocking", true)
    val stripTracking = bool("strip_tracking", true)
    val youtube = bool("youtube", true)
    val allowlist = stringSet("allowlist")
    val updateWifiOnly = bool("update_wifi_only", false)
    val autoUpdate = bool("auto_update", true)

    // Privacy
    val thirdPartyCookies = bool("third_party_cookies", false)
    val gpc = bool("gpc", true)
    val httpsUpgrade = bool("https_upgrade", true)
    val safeBrowsing = bool("safe_browsing", true)
    val saveHistory = bool("save_history", true)
    val clearOnExit = bool("clear_on_exit", false)

    // Appearance
    val theme = string("theme", "system")
    val webDarkMode = bool("web_dark", false)
    val bottomBar = bool("bottom_bar", true)
    val textZoom = int("text_zoom", 100)
    val pullToRefresh = bool("pull_refresh", true)

    // General
    val searchEngine = string("search_engine", "duckduckgo")
    val suggestions = bool("suggestions", true)
    val javascript = bool("javascript", true)
    val loadImages = bool("load_images", true)
    val desktopDefault = bool("desktop_default", false)
    val openApps = string("open_apps", "ask")
    val restoreTabs = bool("restore_tabs", true)
    val homepage = string("homepage", "")
    val onboarded = bool("onboarded", false)

    fun isAllowlisted(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val set = allowlist.value
        if (set.isEmpty()) return false
        var h: String = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun setAllowlisted(host: String, allowed: Boolean) {
        val set = allowlist.value.toMutableSet()
        if (allowed) set.add(host) else {
            // Remove the host and any parent entry that covers it.
            set.removeAll { host == it || host.endsWith(".$it") }
        }
        allowlist.value = set
    }
}
