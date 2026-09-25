package io.github.jsys12.bastion

import android.app.SearchManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.adblock.FilterUpdateWorker
import io.github.jsys12.bastion.browser.BrowserController
import io.github.jsys12.bastion.browser.FaviconCache
import io.github.jsys12.bastion.browser.SearchEngines
import io.github.jsys12.bastion.browser.UrlUtil
import io.github.jsys12.bastion.ui.BastionTheme
import io.github.jsys12.bastion.ui.BrowserApp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    lateinit var controller: BrowserController
        private set

    private var fileCallback: ((Int, Intent?) -> Unit)? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.invoke(r.resultCode, r.data)
        fileCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permissionCallback?.invoke(r.values.any { it })
        permissionCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FaviconCache.init(this)
        AdBlocker.start()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
            ) {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        AdBlocker.interceptServiceWorker(request)
                })
            }
        } catch (t: Throwable) {
            // No usable WebView provider (being updated, or a test environment).
        }

        controller = BrowserController(this)
        val restored = controller.restoreTabs()
        if (!handleIntent(intent) && !restored) controller.newTab()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.onBack()) moveTaskToBack(true)
            }
        })

        val s = controller.settings
        lifecycleScope.launch {
            merge(
                s.javascript.flow, s.loadImages.flow, s.textZoom.flow, s.thirdPartyCookies.flow,
                s.webDarkMode.flow, s.safeBrowsing.flow, s.blockPopups.flow, s.adblock.flow,
            ).drop(8).collect { controller.applySettingsToAll() }
        }
        lifecycleScope.launch {
            s.updateWifiOnly.flow.drop(1).collect { FilterUpdateWorker.schedule(this@MainActivity) }
        }

        setContent {
            BastionTheme(s) {
                BrowserApp(controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val engine = SearchEngines.byId(controller.settings.searchEngine.value)
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) return false
                controller.handleIntentUrl(url)
                return true
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> {
                val q = intent.getStringExtra(SearchManager.QUERY) ?: return false
                controller.handleIntentUrl(UrlUtil.fromInput(q, engine))
                return true
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return false
                val url = Regex("https?://\\S+").find(text)?.value ?: UrlUtil.fromInput(text, engine)
                controller.handleIntentUrl(url)
                return true
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: return false
                controller.handleIntentUrl(UrlUtil.fromInput(text, engine))
                return true
            }
        }
        return false
    }

    fun launchFileChooser(intent: Intent, callback: (Int, Intent?) -> Unit) {
        fileCallback = callback
        try {
            fileLauncher.launch(intent)
        } catch (e: Exception) {
            fileCallback = null
            callback(RESULT_CANCELED, null)
        }
    }

    fun requestPermissions(permissions: List<String>, callback: (Boolean) -> Unit) {
        val missing = permissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callback(true); return
        }
        permissionCallback = callback
        permissionLauncher.launch(missing.toTypedArray())
    }

    fun setFullscreen(on: Boolean) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onPause() {
        super.onPause()
        controller.onPause()
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onDestroy() {
        controller.onDestroy()
        super.onDestroy()
    }
}
