package io.github.jsys12.bastion.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.jsys12.bastion.BastionApp
import io.github.jsys12.bastion.MainActivity
import io.github.jsys12.bastion.R
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.ContentBridge
import io.github.jsys12.bastion.adblock.ContentScripts
import io.github.jsys12.bastion.adblock.ParamCleaner
import io.github.jsys12.bastion.adblock.Urls
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Sheet { MENU, SHIELD, TABS }

enum class Screen { SETTINGS, FILTERS, USER_RULES, ALLOWLIST, HISTORY, BOOKMARKS, STATS, ABOUT }

data class FindState(val query: String = "", val index: Int = 0, val total: Int = 0)

data class PickerState(val selected: Boolean = false, val selector: String = "", val count: Int = 0, val label: String = "")

data class ContextMenuState(val tab: Tab, val link: String?, val image: String?)

sealed class DialogState {
    class Confirm(
        val title: String,
        val message: String,
        val confirm: String,
        val dismiss: String = "Отмена",
        val onResult: (Boolean) -> Unit,
    ) : DialogState()

    class HttpAuth(val host: String, val realm: String, val onResult: (Pair<String, String>?) -> Unit) : DialogState()

    class Input(
        val title: String,
        val label: String,
        val initial: String,
        val confirm: String = "Готово",
        val onResult: (String?) -> Unit,
    ) : DialogState()
}

class BrowserController(val activity: MainActivity) {
    val app = activity.application as BastionApp
    val settings = app.settings
    val stats = app.stats
    val db = app.db
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf<Tab?>(null)
        private set

    var sheet by mutableStateOf<Sheet?>(null)
    val screens = mutableStateListOf<Screen>()
    var editingAddress by mutableStateOf(false)
    var addressValue by mutableStateOf(TextFieldValue(""))
    var find by mutableStateOf<FindState?>(null)
    var picker by mutableStateOf<PickerState?>(null)
    var dialog by mutableStateOf<DialogState?>(null)
    var contextMenu by mutableStateOf<ContextMenuState?>(null)
    var fullscreenView by mutableStateOf<View?>(null)
    var refreshing by mutableStateOf(false)
    val snackbar = SnackbarHostState()

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val httpOnlyHosts = HashSet<String>()
    private val pendingBlobs = HashMap<String, String>()

    private val multiProfile = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (t: Throwable) { false }
    val incognitoIsolated: Boolean get() = multiProfile

    // ------------------------------------------------------------------ tabs

    fun newTab(url: String = "", incognito: Boolean = false, select: Boolean = true, parent: Tab? = null): Tab {
        val tab = Tab(nextId++, incognito, "", "")
        tab.desktopMode = settings.desktopDefault.value
        tab.parentId = parent?.id
        val index = if (parent != null) tabs.indexOf(parent) + 1 else tabs.size
        tabs.add(index.coerceIn(0, tabs.size), tab)
        if (select) selectTab(tab)
        if (url.isNotEmpty()) load(tab, url)
        return tab
    }

    fun selectTab(tab: Tab) {
        val prev = current
        if (prev != null && prev !== tab) {
            captureThumbnail(prev)
            prev.webView?.onPause()
        }
        current = tab
        tab.lastUsed = System.currentTimeMillis()
        if (!tab.homeVisible || tab.url.isNotEmpty()) ensureWebView(tab).onResume()
        find = null
        stopPicker()
        trimWebViews()
    }

    fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        destroyWebView(tab)
        if (current === tab) {
            val parent = tabs.firstOrNull { it.id == tab.parentId }
            val next = parent ?: tabs.getOrNull(index.coerceAtMost(tabs.size - 1))
            if (next != null) selectTab(next) else {
                current = null
                newTab()
            }
        }
        if (tab.incognito && tabs.none { it.incognito }) clearIncognito()
    }

    fun closeAll(incognito: Boolean) {
        tabs.filter { it.incognito == incognito }.forEach { closeTab(it) }
    }

    private fun destroyWebView(tab: Tab) {
        val wv = tab.webView ?: return
        tab.webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    fun ensureWebView(tab: Tab): BastionWebView {
        tab.webView?.let { return it }
        val wv = createWebView(tab)
        tab.webView = wv
        val saved = tab.savedState
        if (saved != null) {
            tab.savedState = null
            if (wv.restoreState(saved) == null && tab.url.isNotEmpty()) wv.loadUrl(tab.url)
        } else if (tab.url.isNotEmpty() && !tab.homeVisible) {
            wv.loadUrl(tab.url)
        }
        return wv
    }

    private fun trimWebViews() {
        val live = tabs.filter { it.webView != null && it !== current }.sortedBy { it.lastUsed }
        val excess = live.size - (MAX_LIVE_WEBVIEWS - 1)
        for (i in 0 until excess) {
            val tab = live[i]
            val state = Bundle()
            tab.webView?.saveState(state)
            tab.savedState = state
            destroyWebView(tab)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): BastionWebView {
        val wv = BastionWebView(activity, tab)
        if (tab.incognito && multiProfile) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE)
                WebViewCompat.setProfile(wv, INCOGNITO_PROFILE)
            } catch (e: Exception) { /* fall back to the default profile */ }
        }
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.apply {
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }
        applySettings(wv)
        applyUserAgent(wv, tab)
        compat {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
            }
        }
        wv.webViewClient = BrowserWebViewClient(this, tab)
        wv.webChromeClient = BrowserChromeClient(this, tab)
        wv.addJavascriptInterface(ContentBridge(tab), ContentScripts.bridgeName)
        wv.addJavascriptInterface(BlobReceiver(this), ContentScripts.blobName)
        compat {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, ContentScripts.documentStart, setOf("*"))
            }
        }
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(tab, url, userAgent, contentDisposition, mimeType)
        }
        wv.setOnLongClickListener { onLongPress(tab, wv) }
        wv.setFindListener { active, total, _ ->
            if (find != null) find = find?.copy(index = if (total == 0) 0 else active + 1, total = total)
        }
        return wv
    }

    fun applySettings(wv: WebView) {
        wv.settings.javaScriptEnabled = settings.javascript.value
        wv.settings.javaScriptCanOpenWindowsAutomatically = !(settings.adblock.value && settings.blockPopups.value)
        wv.settings.loadsImagesAutomatically = settings.loadImages.value
        wv.settings.textZoom = settings.textZoom.value
        wv.settings.safeBrowsingEnabled = settings.safeBrowsing.value
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, settings.thirdPartyCookies.value)
        compat {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, settings.webDarkMode.value)
            }
        }
    }

    /** androidx.webkit calls can throw when the WebView provider is missing or mid-update. */
    private inline fun compat(block: () -> Unit) {
        try { block() } catch (t: Throwable) { /* feature unavailable */ }
    }

    fun applySettingsToAll() {
        tabs.forEach { t -> t.webView?.let { applySettings(it) } }
    }

    private fun applyUserAgent(wv: WebView, tab: Tab) {
        val default = WebSettings.getDefaultUserAgent(activity)
        // Present as regular Chrome: some sites (and Google sign-in) reject the WebView marker.
        val mobile = default.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
        wv.settings.userAgentString = if (tab.desktopMode) {
            val chrome = Regex("Chrome/[\\d.]+").find(default)?.value ?: "Chrome/130.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else mobile
    }

    private fun clearIncognito() {
        if (!multiProfile) return
        try { ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE) } catch (e: Exception) { /* still in use */ }
    }

    fun captureThumbnail(tab: Tab) {
        val wv = tab.webView ?: return
        if (tab.homeVisible || wv.width <= 0 || wv.height <= 0 || !wv.isAttachedToWindow) return
        try {
            val scale = 0.4f
            val w = (wv.width * scale).toInt().coerceAtLeast(1)
            val h = (wv.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)
            tab.thumbnail = bmp.asImageBitmap()
        } catch (e: Throwable) { /* out of memory or not laid out */ }
    }

    // ------------------------------------------------------------------ navigation

    fun startEditing(initial: String? = null) {
        val tab = current
        val engine = SearchEngines.byId(settings.searchEngine.value)
        val text = initial ?: if (tab == null || tab.homeVisible) "" else UrlUtil.searchTerms(tab.url, engine) ?: tab.url
        addressValue = TextFieldValue(text, TextRange(0, text.length))
        editingAddress = true
    }

    fun openInput(text: String) {
        val engine = SearchEngines.byId(settings.searchEngine.value)
        val url = UrlUtil.fromInput(text, engine)
        if (url.isEmpty()) return
        editingAddress = false
        val tab = current ?: newTab()
        load(tab, url)
    }

    fun load(tab: Tab, url: String) {
        tab.homeVisible = false
        tab.homeOverPage = false
        tab.url = url
        val wv = ensureWebView(tab)
        val cleaned = if (settings.adblock.value && settings.stripTracking.value) ParamCleaner.stripDefault(url) else null
        wv.loadUrl(cleaned ?: url)
    }

    fun goBack(): Boolean {
        val tab = current ?: return false
        val wv = tab.webView
        if (tab.homeVisible) {
            // Home opened over a loaded page: return to that page.
            if (tab.homeOverPage) {
                tab.homeVisible = false
                tab.homeOverPage = false
                return true
            }
            if (tab.parentId != null) {
                closeTab(tab); return true
            }
            return false
        }
        if (wv != null && wv.canGoBack()) {
            wv.goBack(); return true
        }
        if (tab.cameFromHome) {
            tab.homeVisible = true
            tab.homeOverPage = false
            return true
        }
        if (tab.parentId != null) {
            closeTab(tab); return true
        }
        return false
    }

    fun goForward() {
        val tab = current ?: return
        if (tab.homeVisible && tab.homeOverPage) {
            tab.homeVisible = false
            tab.homeOverPage = false
            return
        }
        tab.webView?.goForward()
    }

    fun reload() {
        val tab = current ?: return
        if (tab.homeVisible) return
        tab.webView?.reload()
    }

    fun stop() {
        current?.webView?.stopLoading()
    }

    fun goHome() {
        val tab = current ?: newTab()
        val home = settings.homepage.value
        if (home.isNotBlank()) {
            load(tab, home); return
        }
        captureThumbnail(tab)
        tab.homeOverPage = !tab.homeVisible && tab.url.isNotEmpty()
        tab.homeVisible = true
    }

    // ------------------------------------------------------------------ WebView callbacks

    fun onHttpNavigation(tab: Tab, view: WebView, request: WebResourceRequest, url: String): Boolean {
        if (tab.popupGuard != null && !checkPopup(tab, url)) return true
        if (!request.isForMainFrame) return false
        val host = Urls.host(url)
        val referer = mapOf("Referer" to tab.url).filterValues { it.startsWith("http") }
        if (settings.adblock.value && !settings.isAllowlisted(host)) {
            val engine = AdBlocker.engine
            engine?.urlSkip(url)?.let { dest ->
                stats.cleanedParams()
                view.loadUrl(dest, referer)
                return true
            }
            if (settings.stripTracking.value) {
                val listCleaned = engine?.removeParams(url)
                val cleaned = ParamCleaner.stripDefault(listCleaned ?: url) ?: listCleaned
                if (cleaned != null && cleaned != url) {
                    stats.cleanedParams()
                    view.loadUrl(cleaned, referer)
                    return true
                }
            }
        }
        if (url.startsWith("http://") && settings.httpsUpgrade.value && host != null && host !in httpOnlyHosts &&
            !host.endsWith(".local") && !host.startsWith("192.168.") && !host.startsWith("10.") && host != "localhost"
        ) {
            val https = "https://" + url.substring(7)
            tab.upgraded[https] = url
            view.loadUrl(https, referer)
            return true
        }
        return false
    }

    fun httpFallback(tab: Tab, view: WebView, httpUrl: String) {
        Urls.host(httpUrl)?.let { httpOnlyHosts.add(it) }
        view.loadUrl(httpUrl)
    }

    fun onInternalUrl(tab: Tab, uri: Uri) {
        when (uri.host) {
            "proceed" -> {
                val url = uri.getQueryParameter("token")?.let { tab.redeemProceedToken(it) } ?: return
                tab.webView?.loadUrl(url)
                showSnackbar("Защита для ${UrlUtil.displayHost(url)} отключена в этой вкладке")
            }
            "back" -> if (!goBack()) closeTab(tab)
        }
    }

    fun onExternalScheme(tab: Tab, url: String, hasGesture: Boolean, mainFrame: Boolean) {
        val protect = settings.adblock.value && settings.blockAppRedirects.value && !tab.pageAllowlisted
        if (protect && (!hasGesture || !mainFrame)) {
            stats.blockedAppRedirect()
            showSnackbar(activity.getString(R.string.app_redirect_blocked), activity.getString(R.string.open)) {
                launchExternal(tab, url, ask = false)
            }
            return
        }
        launchExternal(tab, url, ask = settings.openApps.value == "ask")
    }

    private fun parseIntent(url: String): Intent? = try {
        if (url.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        }
    } catch (e: Exception) { null }

    private fun launchExternal(tab: Tab, url: String, ask: Boolean) {
        val intent = parseIntent(url) ?: return
        val scheme = Uri.parse(url).scheme?.lowercase()
        val resolved = intent.resolveActivity(activity.packageManager)
        if (resolved == null) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                load(tab, fallback)
            } else if (intent.`package` != null) {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}"))
                if (market.resolveActivity(activity.packageManager) != null) {
                    confirm(activity.getString(R.string.open_store_title), activity.getString(R.string.open_store_message), activity.getString(R.string.open)) {
                        if (it) startActivitySafe(market)
                    }
                }
            } else {
                showSnackbar(activity.getString(R.string.no_app_for_link))
            }
            return
        }
        if (scheme in setOf("tel", "mailto", "sms", "smsto", "geo") || !ask || settings.openApps.value == "always") {
            if (settings.openApps.value == "never" && scheme !in setOf("tel", "mailto", "sms", "smsto", "geo")) return
            startActivitySafe(intent)
            return
        }
        if (settings.openApps.value == "never") return
        val label = try {
            activity.packageManager.getActivityInfo(resolved, 0).loadLabel(activity.packageManager).toString()
        } catch (e: Exception) { activity.getString(R.string.external_app) }
        confirm(activity.getString(R.string.open_app_title, label), activity.getString(R.string.open_app_message), activity.getString(R.string.open)) {
            if (it) startActivitySafe(intent)
        }
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showSnackbar(activity.getString(R.string.no_app_for_link))
        } catch (e: SecurityException) {
            showSnackbar(activity.getString(R.string.no_app_for_link))
        }
    }

    /** Decides a popup tab's fate by its first URL. Thread-safe; returns false if it was blocked. */
    fun checkPopup(tab: Tab, url: String): Boolean {
        val guard = synchronized(tab) { tab.popupGuard.also { tab.popupGuard = null } } ?: return true
        val engine = AdBlocker.engine
        val result = engine?.matchPopup(url, guard.openerInfo, guard.openerFlags)
        if (result != null && result.blocked) {
            stats.blockedPopup()
            main.post {
                closeTab(tab)
                tabs.firstOrNull { it.id == tab.parentId }?.let { it.popupsBlocked++ }
                showSnackbar(activity.getString(R.string.popup_ad_blocked))
            }
            return false
        }
        main.post { if (tab in tabs) selectTab(tab) }
        return true
    }

    fun onCreateWindow(opener: Tab, userGesture: Boolean, msg: Message): Boolean {
        val guard = settings.adblock.value && settings.blockPopups.value && !opener.pageAllowlisted
        if (guard && !userGesture) {
            stats.blockedPopup()
            opener.popupsBlocked++
            showSnackbar(activity.getString(R.string.popup_blocked))
            return false
        }
        val tab = newTab(incognito = opener.incognito, select = !guard, parent = opener)
        tab.cameFromHome = false
        tab.homeVisible = false
        if (guard) tab.popupGuard = PopupGuard(opener.pageInfo, opener.pageFlags)
        val wv = ensureWebView(tab)
        val transport = msg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = wv
        msg.sendToTarget()
        if (guard) {
            // about:blank popups filled by script never navigate; show them after a moment.
            main.postDelayed({
                val pending = synchronized(tab) { tab.popupGuard.also { tab.popupGuard = null } }
                if (pending != null && tab in tabs) selectTab(tab)
            }, 1500)
        }
        return true
    }

    fun onPageStarted(tab: Tab) {
        if (tab === current) refreshing = false
    }

    fun onPageFinished(tab: Tab, view: WebView, url: String) {
        if (tab === current) refreshing = false
        if (!tab.incognito && settings.saveHistory.value && url.startsWith("http")) {
            val title = view.title ?: ""
            app.appScope.launch { db.addVisit(url, title) }
        }
        tab.favicon?.let { FaviconCache.put(Urls.host(url), it) }
    }

    fun onTitle(tab: Tab, title: String) {
        if (!tab.incognito && settings.saveHistory.value && tab.url.startsWith("http")) {
            val url = tab.url
            app.appScope.launch { db.updateTitle(url, title) }
        }
    }

    fun askSslError(tab: Tab, url: String, error: SslError, onResult: (Boolean) -> Unit) {
        val reason = when (error.primaryError) {
            SslError.SSL_EXPIRED -> activity.getString(R.string.ssl_expired)
            SslError.SSL_IDMISMATCH -> activity.getString(R.string.ssl_mismatch)
            SslError.SSL_UNTRUSTED -> activity.getString(R.string.ssl_untrusted)
            SslError.SSL_NOTYETVALID -> activity.getString(R.string.ssl_not_yet_valid)
            else -> activity.getString(R.string.ssl_invalid)
        }
        confirm(
            activity.getString(R.string.ssl_title),
            activity.getString(R.string.ssl_message, UrlUtil.displayHost(url), reason),
            activity.getString(R.string.ssl_proceed),
            activity.getString(R.string.ssl_back),
        ) { proceed ->
            onResult(proceed)
            if (!proceed && tab.webView?.canGoBack() == true) tab.webView?.goBack()
        }
    }

    fun askHttpAuth(host: String, realm: String, onResult: (Pair<String, String>?) -> Unit) {
        dialog = DialogState.HttpAuth(host, realm) { result ->
            dialog = null
            onResult(result)
        }
    }

    fun onRendererGone(tab: Tab, view: WebView) {
        if (tab.webView === view) {
            tab.webView = null
            (view.parent as? ViewGroup)?.removeView(view)
        }
        view.destroy()
        if (tab === current) {
            ensureWebView(tab).loadUrl(tab.url)
            showSnackbar(activity.getString(R.string.renderer_crashed))
        }
    }

    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden(); return
        }
        fullscreenView = view
        fullscreenCallback = callback
        activity.setFullscreen(true)
    }

    fun exitFullscreen() {
        if (fullscreenView == null) return
        fullscreenView = null
        activity.setFullscreen(false)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        val intent = try { params.createIntent() } catch (e: Exception) { null }
        if (intent == null) {
            callback.onReceiveValue(null); return true
        }
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.launchFileChooser(intent) { resultCode, data ->
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                ?: data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } })
        }
        return true
    }

    fun onPermissionRequest(tab: Tab, request: PermissionRequest) {
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (wanted.isEmpty()) {
            request.deny(); return
        }
        // Protected media (DRM) alone is granted silently, like Chrome.
        if (wanted.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }) {
            request.grant(wanted.toTypedArray()); return
        }
        val what = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(activity.getString(R.string.perm_camera))
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add(activity.getString(R.string.perm_microphone))
        }.joinToString(", ")
        val host = UrlUtil.displayHost(request.origin.toString())
        confirm(activity.getString(R.string.perm_title, host), activity.getString(R.string.perm_message, what), activity.getString(R.string.allow), activity.getString(R.string.deny)) { ok ->
            if (!ok) {
                request.deny(); return@confirm
            }
            val android = buildList {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add(Manifest.permission.CAMERA)
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add(Manifest.permission.RECORD_AUDIO)
            }
            activity.requestPermissions(android) { granted ->
                if (granted) request.grant(wanted.toTypedArray()) else request.deny()
            }
        }
    }

    fun onGeolocationRequest(tab: Tab, origin: String, callback: GeolocationPermissions.Callback) {
        confirm(activity.getString(R.string.perm_title, UrlUtil.displayHost(origin)), activity.getString(R.string.perm_message, activity.getString(R.string.perm_location)), activity.getString(R.string.allow), activity.getString(R.string.deny)) { ok ->
            if (!ok) {
                callback.invoke(origin, false, false); return@confirm
            }
            activity.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted ->
                callback.invoke(origin, granted, false)
            }
        }
    }

    private fun onLongPress(tab: Tab, wv: WebView): Boolean {
        val hit = wv.hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> contextMenu = ContextMenuState(tab, hit.extra, null)
            WebView.HitTestResult.IMAGE_TYPE -> contextMenu = ContextMenuState(tab, null, hit.extra)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val image = hit.extra
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val link = msg.data?.getString("url")
                    contextMenu = ContextMenuState(tab, link, image)
                    true
                }
                wv.requestFocusNodeHref(handler.obtainMessage())
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ downloads

    fun download(tab: Tab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("data:") -> Downloads.saveDataUrl(this, url, contentDisposition, mimeType)
            url.startsWith("blob:") -> downloadBlob(tab, url, contentDisposition, mimeType)
            url.startsWith("http") -> {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val needsPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                val go = {
                    Downloads.enqueue(activity, url, userAgent, contentDisposition, mimeType, tab.url, name)
                    showSnackbar(activity.getString(R.string.download_started, name), activity.getString(R.string.downloads)) {
                        Downloads.openDownloads(activity)
                    }
                }
                if (needsPermission) {
                    activity.requestPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { if (it) go() }
                } else go()
            }
        }
    }

    private fun downloadBlob(tab: Tab, url: String, contentDisposition: String?, mimeType: String?) {
        val wv = tab.webView ?: return
        val nonce = java.util.UUID.randomUUID().toString()
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        pendingBlobs[nonce] = name
        val js = """(function(){var n=${JSONObject.quote(nonce)};var b=window[${JSONObject.quote(ContentScripts.blobName)}];
            fetch(${JSONObject.quote(url)}).then(function(r){return r.blob();}).then(function(blob){
            var fr=new FileReader();fr.onloadend=function(){b.onData(n,blob.type||'',fr.result);};fr.readAsDataURL(blob);})
            .catch(function(){b.onData(n,'','');});})();"""
        wv.evaluateJavascript(js, null)
    }

    fun onBlobData(nonce: String, mime: String, dataUrl: String) {
        main.post {
            val name = pendingBlobs.remove(nonce) ?: return@post
            if (dataUrl.isEmpty()) {
                showSnackbar(activity.getString(R.string.download_failed)); return@post
            }
            Downloads.saveDataUrl(this, dataUrl, "attachment; filename=\"$name\"", mime.ifEmpty { null })
        }
    }

    // ------------------------------------------------------------------ page features

    fun toggleDesktop() {
        val tab = current ?: return
        tab.desktopMode = !tab.desktopMode
        val wv = tab.webView ?: return
        applyUserAgent(wv, tab)
        wv.reload()
    }

    fun startFind() {
        sheet = null
        find = FindState()
    }

    fun findQuery(q: String) {
        find = FindState(q)
        val wv = current?.webView ?: return
        if (q.isEmpty()) wv.clearMatches() else wv.findAllAsync(q)
    }

    fun findNext(forward: Boolean) {
        current?.webView?.findNext(forward)
    }

    fun closeFind() {
        find = null
        current?.webView?.clearMatches()
    }

    fun share(url: String? = current?.url, title: String? = current?.title) {
        if (url.isNullOrEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivitySafe(Intent.createChooser(send, null))
    }

    fun copyToClipboard(text: String) {
        val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        if (android.os.Build.VERSION.SDK_INT < 33) showSnackbar(activity.getString(R.string.copied))
    }

    fun print() {
        val tab = current ?: return
        val wv = tab.webView ?: return
        val pm = activity.getSystemService(PrintManager::class.java) ?: return
        val name = tab.displayTitle
        pm.print(name, wv.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }

    fun addToHomeScreen() {
        val tab = current ?: return
        if (tab.url.isEmpty()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tab.url), activity, MainActivity::class.java)
        val icon = tab.favicon?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(activity, R.mipmap.ic_launcher)
        val info = ShortcutInfoCompat.Builder(activity, "site-" + tab.url.hashCode())
            .setShortLabel(tab.displayTitle.take(24))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            ShortcutManagerCompat.requestPinShortcut(activity, info, null)
        } else showSnackbar(activity.getString(R.string.shortcut_unsupported))
    }

    fun toggleBookmark() {
        val tab = current ?: return
        val url = tab.url
        if (url.isEmpty()) return
        app.appScope.launch {
            if (db.isBookmarked(url)) {
                db.removeBookmark(url)
                showSnackbar(activity.getString(R.string.bookmark_removed))
            } else {
                db.addBookmark(url, tab.displayTitle)
                showSnackbar(activity.getString(R.string.bookmark_added))
            }
        }
    }

    fun setProtection(tab: Tab, enabled: Boolean) {
        val host = tab.pageHost ?: Urls.host(tab.url) ?: return
        val target = if (!enabled) host.removePrefix("www.") else host
        settings.setAllowlisted(target, !enabled)
        tab.pageAllowlisted = settings.isAllowlisted(host)
        tab.protectionOff = tab.pageAllowlisted
        tab.webView?.reload()
    }

    // ------------------------------------------------------------------ element picker

    fun startPicker() {
        val tab = current ?: return
        val wv = tab.webView ?: return
        if (tab.homeVisible) return
        sheet = null
        picker = PickerState()
        wv.evaluateJavascript(ContentScripts.picker, null)
        pollPicker()
    }

    private fun pollPicker() {
        if (picker == null) return
        pickerCall("state()")
        main.postDelayed({ pollPicker() }, 350)
    }

    private fun pickerCall(method: String) {
        val wv = current?.webView ?: return
        val name = JSONObject.quote(ContentScripts.pickerName)
        wv.evaluateJavascript("(function(){var p=window[$name];return p?p.$method:null;})()") { result ->
            if (picker == null) return@evaluateJavascript
            val json = try { JSONArray("[$result]").optString(0, "") } catch (e: Exception) { "" }
            if (json.isEmpty() || json == "null") return@evaluateJavascript
            try {
                val o = JSONObject(json)
                picker = PickerState(o.optBoolean("selected"), o.optString("selector"), o.optInt("count"), o.optString("label"))
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun pickerWider() = pickerCall("wider()")
    fun pickerNarrower() = pickerCall("narrower()")

    fun pickerConfirm() {
        val state = picker ?: return
        val tab = current ?: return
        val host = tab.pageHost ?: return
        val selector = state.selector
        if (!state.selected || selector.isBlank() || selector.contains('{') || selector.contains('}')) return
        val rule = "${host.removePrefix("www.")}##$selector"
        AdBlocker.addUserRule(rule)
        val wv = tab.webView
        val css = JSONObject.quote("$selector{display:none!important}")
        wv?.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=$css;(document.head||document.documentElement).appendChild(s);})()", null)
        stopPicker()
        showSnackbar(activity.getString(R.string.picker_done))
    }

    fun stopPicker() {
        if (picker == null) return
        picker = null
        val wv = current?.webView ?: return
        val name = JSONObject.quote(ContentScripts.pickerName)
        wv.evaluateJavascript("(function(){var p=window[$name];if(p)p.stop();})()", null)
    }

    // ------------------------------------------------------------------ data

    fun clearBrowsingData(history: Boolean, cookies: Boolean, cache: Boolean) {
        if (history) app.appScope.launch { db.clearHistory() }
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        if (cache) {
            // The HTTP cache is shared by all WebViews, so clearing it through any one is enough.
            val live = tabs.firstNotNullOfOrNull { it.webView }
            if (live != null) live.clearCache(true) else WebView(activity).apply { clearCache(true); destroy() }
        }
    }

    // ------------------------------------------------------------------ helpers

    fun confirm(title: String, message: String, confirm: String, dismiss: String = activity.getString(R.string.cancel), onResult: (Boolean) -> Unit) {
        dialog = DialogState.Confirm(title, message, confirm, dismiss) { ok ->
            dialog = null
            onResult(ok)
        }
    }

    fun showSnackbar(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        main.post {
            activity.lifecycleScope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(message, actionLabel = action, withDismissAction = action == null, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
            }
        }
    }

    fun openScreen(screen: Screen) {
        sheet = null
        screens.add(screen)
    }

    /** Handles system back; returns false when the activity should go to background. */
    fun onBack(): Boolean {
        if (fullscreenView != null) {
            exitFullscreen(); return true
        }
        if (picker != null) {
            stopPicker(); return true
        }
        if (contextMenu != null) {
            contextMenu = null; return true
        }
        if (sheet != null) {
            sheet = null; return true
        }
        if (screens.isNotEmpty()) {
            screens.removeAt(screens.size - 1); return true
        }
        if (find != null) {
            closeFind(); return true
        }
        if (editingAddress) {
            editingAddress = false; return true
        }
        return goBack()
    }

    // ------------------------------------------------------------------ persistence & lifecycle

    private val tabsFile get() = File(activity.filesDir, "tabs.json")

    fun saveTabs() {
        if (!settings.restoreTabs.value) {
            tabsFile.delete(); return
        }
        val arr = JSONArray()
        var currentIndex = 0
        tabs.filter { !it.incognito }.forEachIndexed { i, t ->
            if (t === current) currentIndex = i
            arr.put(JSONObject().put("url", if (t.homeVisible && !t.cameFromHome) "" else t.url).put("title", t.title))
        }
        val o = JSONObject().put("tabs", arr).put("current", currentIndex)
        try { tabsFile.writeText(o.toString()) } catch (e: Exception) { /* ignore */ }
    }

    fun restoreTabs(): Boolean {
        if (!settings.restoreTabs.value || !tabsFile.isFile) return false
        return try {
            val o = JSONObject(tabsFile.readText())
            val arr = o.getJSONArray("tabs")
            if (arr.length() == 0) return false
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val url = t.optString("url")
                val tab = Tab(nextId++, false, url, t.optString("title"))
                tab.cameFromHome = false
                tab.favicon = FaviconCache.get(Urls.host(url))
                tab.lastUsed = 0
                tabs.add(tab)
            }
            selectTab(tabs[o.optInt("current").coerceIn(0, tabs.size - 1)])
            true
        } catch (e: Exception) {
            false
        }
    }

    fun onPause() {
        current?.webView?.onPause()
        saveTabs()
    }

    fun onResume() {
        current?.webView?.onResume()
    }

    fun onDestroy() {
        saveTabs()
        if (settings.clearOnExit.value && activity.isFinishing) clearBrowsingData(history = false, cookies = true, cache = true)
        tabs.forEach { destroyWebView(it) }
    }

    fun handleIntentUrl(url: String) {
        val tab = current
        if (tab != null && tab.homeVisible && tab.url.isEmpty()) load(tab, url) else newTab(url)
    }

    companion object {
        const val MAX_LIVE_WEBVIEWS = 6
        const val INCOGNITO_PROFILE = "bastion-incognito"
    }
}
