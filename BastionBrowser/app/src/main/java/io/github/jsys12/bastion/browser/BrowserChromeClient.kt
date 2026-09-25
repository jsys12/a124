package io.github.jsys12.bastion.browser

import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import io.github.jsys12.bastion.adblock.Urls

class BrowserChromeClient(private val c: BrowserController, private val tab: Tab) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tab.progress = newProgress
        if (newProgress < 100) tab.loading = true
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (title.isNullOrBlank() || title.startsWith("data:")) return
        tab.title = title
        c.onTitle(tab, title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        if (icon == null) return
        tab.favicon = icon
        FaviconCache.put(Urls.host(tab.url), icon)
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        c.onCreateWindow(tab, isUserGesture, resultMsg)

    override fun onCloseWindow(window: WebView) {
        c.closeTab(tab)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        c.enterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        c.exitFullscreen()
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<android.net.Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = c.showFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        c.onPermissionRequest(tab, request)
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        c.onGeolocationRequest(tab, origin, callback)
    }

    /** A transparent poster instead of WebView's grey play-button placeholder. */
    override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
