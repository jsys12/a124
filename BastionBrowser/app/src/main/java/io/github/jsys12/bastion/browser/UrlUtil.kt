package io.github.jsys12.bastion.browser

import android.net.Uri
import java.net.URLEncoder

data class SearchEngine(val id: String, val name: String, val search: String, val suggest: String?)

object SearchEngines {
    val all = listOf(
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/ac/?type=list&q=%s"),
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s", "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"),
        SearchEngine("yandex", "Яндекс", "https://ya.ru/search/?text=%s", "https://suggest.yandex.ru/suggest-ff.cgi?part=%s"),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s", "https://api.bing.com/osjson.aspx?query=%s"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com/api/suggest?q=%s"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", null),
        SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "https://ac.ecosia.org/autocomplete?type=list&q=%s"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]
}

object UrlUtil {
    private val hostLike = Regex("^([a-zA-Z0-9\\u00A0-\\uFFFF-]+\\.)+[a-zA-Z\\u00A0-\\uFFFF]{2,63}(:\\d{1,5})?([/?#].*)?$")
    private val ipLike = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?([/?#].*)?$")

    /** Turns address bar input into a URL: a URL as typed, a hostname with https://, or a search. */
    fun fromInput(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return text
        if (lower.startsWith("about:") || lower.startsWith("view-source:")) return text
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:")) return search(text, engine)
        if (!text.contains(' ')) {
            if (lower.startsWith("localhost") || hostLike.matches(text) || ipLike.matches(text)) {
                return "https://$text"
            }
        }
        return search(text, engine)
    }

    fun search(query: String, engine: SearchEngine): String =
        engine.search.replace("%s", URLEncoder.encode(query, "UTF-8"))

    /** The search terms if [url] is a results page of [engine], for showing in the address bar. */
    fun searchTerms(url: String, engine: SearchEngine): String? {
        val prefix = engine.search.substringBefore("%s")
        if (!url.startsWith(prefix)) return null
        val param = prefix.substringAfterLast('?').substringAfterLast('&').removeSuffix("=")
        return try { Uri.parse(url).getQueryParameter(param) } catch (e: Exception) { null }
    }

    fun displayHost(url: String): String {
        if (url.isEmpty()) return ""
        return try {
            val h = Uri.parse(url).host ?: return url
            h.removePrefix("www.").removePrefix("m.")
        } catch (e: Exception) { url }
    }
}
