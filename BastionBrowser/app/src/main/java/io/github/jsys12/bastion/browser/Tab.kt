package io.github.jsys12.bastion.browser

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.HostInfo
import io.github.jsys12.bastion.adblock.MatchResult
import io.github.jsys12.bastion.adblock.RequestType
import io.github.jsys12.bastion.adblock.Urls
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BlockedEntry(val url: String, val rule: String, val list: String?, val type: Int, val time: Long) {
    val kind: String
        get() = when (type) {
            RequestType.SCRIPT -> "script"
            RequestType.IMAGE -> "image"
            RequestType.STYLESHEET -> "css"
            RequestType.SUBDOCUMENT -> "frame"
            RequestType.XHR -> "xhr"
            RequestType.MEDIA -> "media"
            RequestType.FONT -> "font"
            RequestType.DOCUMENT -> "page"
            RequestType.POPUP -> "popup"
            else -> "request"
        }
}

/** Popup opened from [opener]; its first URL decides whether it may live. */
class PopupGuard(val openerInfo: HostInfo, val openerFlags: Int)

class Tab(
    val id: Long,
    val incognito: Boolean,
    initialUrl: String,
    initialTitle: String,
) {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf(initialTitle)
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var favicon by mutableStateOf<Bitmap?>(null)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var desktopMode by mutableStateOf(false)
    var sslError by mutableStateOf(false)
    /** Shows the native start page instead of the WebView. */
    var homeVisible by mutableStateOf(initialUrl.isEmpty())
    /** Home was opened with the Home button on top of a loaded page. */
    var homeOverPage by mutableStateOf(false)
    var blockedCount by mutableIntStateOf(0)
    var hiddenCount by mutableIntStateOf(0)
    var popupsBlocked by mutableIntStateOf(0)
    var protectionOff by mutableStateOf(false)

    var parentId: Long? = null
    var webView: BastionWebView? = null
    var savedState: Bundle? = null
    var lastUsed = System.currentTimeMillis()
    /** The tab was opened from the start page, so "back" at the first entry returns there. */
    var cameFromHome = initialUrl.isEmpty()

    @Volatile var popupGuard: PopupGuard? = null

    // ---- state read from WebView background threads
    @Volatile var pageHost: String? = Urls.host(initialUrl)
    @Volatile var pageInfo: HostInfo = HostInfo(pageHost ?: "")
    @Volatile var pageFlags = 0
    @Volatile var pageAllowlisted = false

    private val counter = AtomicInteger()
    private val hidden = AtomicInteger()
    private val log = ArrayDeque<BlockedEntry>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var uiScheduled = false

    /** https URL -> original http URL for upgrades we made (fallback on failure). */
    val upgraded = ConcurrentHashMap<String, String>()
    private val proceedTokens = ConcurrentHashMap<String, String>()
    private val proceedOnce = ConcurrentHashMap.newKeySet<String>()

    val isSecure: Boolean get() = url.startsWith("https://") && !sslError
    val displayTitle: String get() = title.ifBlank { Urls.host(url) ?: url }.ifBlank { "Новая вкладка" }

    /** Called on a WebView thread when a new top-level document starts loading. */
    fun onNewPage(url: String) {
        val host = Urls.host(url)
        pageHost = host
        pageInfo = HostInfo(host ?: "")
        val settings = AdBlocker.settings
        pageAllowlisted = settings.isAllowlisted(host) || (host != null && host in trustedHosts)
        pageFlags = if (settings.adblock.value) (AdBlocker.engine?.pageFlags(url) ?: 0) else 0
        counter.set(0)
        hidden.set(0)
        synchronized(log) { log.clear() }
        scheduleUi()
    }

    fun recordBlocked(url: String, r: MatchResult, type: Int) {
        counter.incrementAndGet()
        val entry = BlockedEntry(url, r.describe(), AdBlocker.listTitle(r.listId), type, System.currentTimeMillis())
        synchronized(log) {
            log.addFirst(entry)
            while (log.size > 300) log.removeLast()
        }
        io.github.jsys12.bastion.BastionApp.instance.stats.blockedRequest()
        scheduleUi()
    }

    fun onHeuristicBlock(src: String) {
        hidden.incrementAndGet()
        synchronized(log) {
            log.addFirst(BlockedEntry(src, "эвристика: рекламный iframe", null, RequestType.SUBDOCUMENT, System.currentTimeMillis()))
        }
        io.github.jsys12.bastion.BastionApp.instance.stats.hiddenElement()
        scheduleUi()
    }

    fun blockedLog(): List<BlockedEntry> = synchronized(log) { log.toList() }

    private fun scheduleUi() {
        if (uiScheduled) return
        uiScheduled = true
        handler.postDelayed({
            uiScheduled = false
            blockedCount = counter.get()
            hiddenCount = hidden.get()
            protectionOff = pageAllowlisted
        }, 250)
    }

    fun createProceedToken(url: String): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        proceedTokens[token] = url
        return token
    }

    fun redeemProceedToken(token: String): String? {
        val url = proceedTokens.remove(token) ?: return null
        proceedOnce.add(url)
        // The site's own resources would hit the same domain rule; trust it for this tab session.
        Urls.host(url)?.let { trustedHosts.add(it) }
        return url
    }

    /** Hosts the user chose to open from the block page; unfiltered in this tab. */
    private val trustedHosts = ConcurrentHashMap.newKeySet<String>()

    fun consumeProceed(url: String): Boolean = proceedOnce.remove(url)
}
