package io.github.jsys12.bastion.adblock

import android.webkit.JavascriptInterface
import io.github.jsys12.bastion.browser.Tab

/**
 * Exposed to page JavaScript under a random name; the content runtime captures and deletes it at
 * document start. It only hands out filter data, so a page that finds it learns nothing sensitive.
 */
class ContentBridge(private val tab: Tab) {

    @JavascriptInterface
    fun config(frameUrl: String?, isTop: Boolean): String {
        if (frameUrl.isNullOrEmpty() || frameUrl.length > 8192) return "{\"on\":false}"
        return try {
            AdBlocker.contentConfig(frameUrl, tab.pageHost)
        } catch (e: Exception) {
            "{\"on\":false}"
        }
    }

    @JavascriptInterface
    fun generic(frameUrl: String?, keys: String?): String {
        if (frameUrl.isNullOrEmpty() || keys.isNullOrEmpty() || keys.length > 2_000_000) return ""
        return try {
            AdBlocker.genericCss(frameUrl, keys)
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun heuristic(frameUrl: String?, src: String?) {
        if (src.isNullOrEmpty()) return
        tab.onHeuristicBlock(src.take(2048))
    }
}
