package io.github.jsys12.bastion.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.ContentScripts
import io.github.jsys12.bastion.adblock.HostInfo
import io.github.jsys12.bastion.adblock.Redirects
import io.github.jsys12.bastion.adblock.RequestType
import io.github.jsys12.bastion.adblock.Urls
import java.io.ByteArrayInputStream

class BrowserWebViewClient(private val c: BrowserController, private val tab: Tab) : WebViewClient() {

    private val documentStartSupported = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    } catch (t: Throwable) { false }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (!url.startsWith("http", ignoreCase = true)) return null
        val settings = c.settings

        if (request.isForMainFrame) {
            if (tab.popupGuard != null && !c.checkPopup(tab, url)) return emptyResponse("text/html")
            tab.onNewPage(url)
            if (!settings.adblock.value || !settings.strictBlocking.value || tab.pageAllowlisted) return null
            if (tab.consumeProceed(url)) return null
            val engine = AdBlocker.awaitEngine(8000) ?: return null
            val r = engine.match(url, HostInfo(""), RequestType.DOCUMENT, request.method, 0)
            if (!r.blocked) return null
            c.stats.blockedNavigation()
            return Pages.blockedPage(url, r, AdBlocker.listTitle(r.listId), tab.createProceedToken(url))
        }

        if (!settings.adblock.value || tab.pageAllowlisted) return null
        val engine = AdBlocker.awaitEngine(8000) ?: return null
        val type = RequestType.infer(url, false, request.requestHeaders)
        val r = engine.match(url, tab.pageInfo, type, request.method, tab.pageFlags)
        if (!r.blocked) return null
        tab.recordBlocked(url, r, type)
        val res = r.redirect?.let { Redirects.get(it) } ?: Redirects.forBlockedType(type)
        return WebResourceResponse(
            res.mime, "UTF-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(res.data),
        )
    }

    private fun emptyResponse(mime: String) =
        WebResourceResponse(mime, "UTF-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val url = uri.toString()
        return when ((uri.scheme ?: "").lowercase()) {
            "http", "https" -> c.onHttpNavigation(tab, view, request, url)
            "about", "data", "blob", "javascript" -> false
            "file", "content" -> true // never let pages open local files
            "bastion" -> {
                c.onInternalUrl(tab, uri)
                true
            }
            else -> {
                c.onExternalScheme(tab, url, request.hasGesture(), request.isForMainFrame)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (tab.popupGuard != null && url.startsWith("http") && !c.checkPopup(tab, url)) return
        tab.url = url
        tab.loading = true
        tab.sslError = false
        tab.homeVisible = false
        if (favicon != null) tab.favicon = favicon else tab.favicon = FaviconCache.get(Urls.host(url))
        if (Urls.host(url) != tab.pageHost) tab.onNewPage(url)
        if (!documentStartSupported) view.evaluateJavascript(ContentScripts.documentStart, null)
        c.onPageStarted(tab)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (!documentStartSupported) view.evaluateJavascript(ContentScripts.documentStart, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        tab.loading = false
        tab.progress = 100
        tab.canGoBack = view.canGoBack() || tab.cameFromHome
        tab.canGoForward = view.canGoForward()
        c.onPageFinished(tab, view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (url.startsWith("data:")) return
        tab.url = url
        tab.canGoBack = view.canGoBack() || tab.cameFromHome
        tab.canGoForward = view.canGoForward()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val url = request.url.toString()
        val http = tab.upgraded.remove(url)
        if (http != null) {
            c.httpFallback(tab, view, http)
            return
        }
        if (error.errorCode == ERROR_UNSUPPORTED_SCHEME) return
        val description = error.description?.toString() ?: "ERROR ${error.errorCode}"
        view.loadDataWithBaseURL(url, Pages.errorPage(url, description), "text/html", "UTF-8", url)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val url = error.url ?: ""
        val http = tab.upgraded.remove(url)
        if (http != null) {
            handler.cancel()
            c.httpFallback(tab, view, http)
            return
        }
        val mainFrame = Urls.host(url) == Urls.host(tab.url) && tab.loading
        if (!mainFrame) {
            handler.cancel()
            return
        }
        tab.sslError = true
        c.askSslError(tab, url, error) { proceed -> if (proceed) handler.proceed() else handler.cancel() }
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        c.askHttpAuth(host, realm) { credentials ->
            if (credentials == null) handler.cancel() else handler.proceed(credentials.first, credentials.second)
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        c.onRendererGone(tab, view)
        return true
    }
}
