package io.github.jsys12.bastion.adblock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.jsys12.bastion.BastionApp
import io.github.jsys12.bastion.BuildConfig
import io.github.jsys12.bastion.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class EngineStatus(
    val loading: Boolean = true,
    val networkRules: Int = 0,
    val cosmeticRules: Int = 0,
    /** Rules per catalog list id. */
    val perList: Map<String, Int> = emptyMap(),
    val missing: List<String> = emptyList(),
    val loadMillis: Long = 0,
    val fromSnapshot: Boolean = false,
)

/** Owns the compiled filter engine and answers blocking / cosmetic questions for WebViews. */
object AdBlocker {
    private const val TAG = "AdBlocker"
    private const val ENGINE_FORMAT = 3

    private lateinit var app: BastionApp
    lateinit var catalog: FilterCatalog
        private set
    val settings: Settings get() = app.settings

    @Volatile
    var engine: FilterEngine? = null
        private set
    private val readyLatch = CountDownLatch(1)

    /** Engine list ids (positions) to catalog ids. */
    @Volatile
    private var listIds: List<String> = emptyList()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> get() = _status

    private val buildMutex = Mutex()
    private var rebuildJob: Job? = null
    @Volatile private var started = false

    val filtersDir: File get() = File(app.filesDir, "filters").apply { mkdirs() }
    val userRulesFile: File get() = File(app.filesDir, "user_rules.txt")
    private val snapshotFile: File get() = File(app.noBackupFilesDir, "engine.bin")

    fun init(app: BastionApp) {
        this.app = app
        catalog = FilterCatalog(app)
        app.settings.onChange = { key -> onSettingChanged(key) }
    }

    /** Loads the engine once (called when the browser UI starts, not for background work). */
    fun start() {
        if (started) return
        started = true
        app.appScope.launch {
            load()
            if (settings.autoUpdate.value) FilterUpdater.updateStale(app, maxAgeMs = TimeUnit.HOURS.toMillis(24))
        }
    }

    fun isStarted() = started

    fun awaitEngine(timeoutMs: Long): FilterEngine? {
        engine?.let { return it }
        if (!started) start()
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return engine
    }

    fun requestRebuild() {
        if (!started) return
        rebuildJob?.cancel()
        rebuildJob = app.appScope.launch {
            delay(300)
            load()
        }
    }

    @Volatile
    private var listTitles: List<String> = emptyList()

    fun listTitle(listId: Int): String? = listTitles.getOrNull(listId)

    // ------------------------------------------------------------ building

    private class Src(val catalogId: String, val key: String, val trusted: Boolean, val open: () -> java.io.BufferedReader)

    private fun collectSources(): Pair<List<Src>, List<String>> {
        val assetNames = app.assets.list("filters")?.toSet() ?: emptySet()
        val out = ArrayList<Src>()
        val missing = ArrayList<String>()
        for (list in catalog.enabled()) {
            if (list.id == "bastion") {
                out.add(Src(list.id, "bastion:${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}", true) {
                    app.assets.open("filters/bastion.txt").bufferedReader()
                })
                continue
            }
            val file = File(filtersDir, "${list.id}.txt")
            if (file.isFile && file.length() > 0) {
                out.add(Src(list.id, "${list.id}:${file.lastModified()}:${file.length()}", list.trusted) { file.bufferedReader() })
            } else if ("${list.id}.txt" in assetNames) {
                out.add(Src(list.id, "${list.id}:asset:${BuildConfig.VERSION_CODE}", list.trusted) {
                    app.assets.open("filters/${list.id}.txt").bufferedReader()
                })
            } else {
                missing.add(list.id)
            }
        }
        val user = userRulesFile
        if (user.isFile) {
            val text = user.readText()
            if (text.isNotBlank()) out.add(Src("user", "user:${sha1(text)}", true) { text.reader().buffered() })
        }
        return out to missing
    }

    private suspend fun load() = buildMutex.withLock {
        val t0 = SystemClock.elapsedRealtime()
        val (sources, missing) = collectSources()
        val key = sha1("$ENGINE_FORMAT|" + sources.joinToString("|") { it.key })
        var fromSnapshot = true
        var e = try { Snapshot.load(snapshotFile, key) } catch (t: Throwable) { null }
        if (e == null) {
            fromSnapshot = false
            e = try {
                EngineBuilder.compile(sources.mapIndexed { i, s -> FilterSource(i, s.trusted, s.open) })
            } catch (t: Throwable) {
                Log.e(TAG, "compile failed", t)
                EngineBuilder.compile(emptyList())
            }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        listIds = sources.map { it.catalogId }
        val titles = catalog.all().associate { it.id to it.title }
        listTitles = listIds.map { if (it == "user") "Мои правила" else titles[it] ?: it }
        engine = e
        synchronized(configCache) { configCache.clear() }
        val perList = HashMap<String, Int>()
        for ((idx, count) in e.listCounts) listIds.getOrNull(idx)?.let { perList[it] = count }
        _status.value = EngineStatus(
            loading = false, networkRules = e.networkFilterCount, cosmeticRules = e.cosmeticFilterCount,
            perList = perList, missing = missing, loadMillis = ms, fromSnapshot = fromSnapshot,
        )
        readyLatch.countDown()
        Log.i(TAG, "engine ready in $ms ms (snapshot=$fromSnapshot, network=${e.networkFilterCount}, cosmetic=${e.cosmeticFilterCount})")
        if (!fromSnapshot) {
            try { Snapshot.save(e, snapshotFile, key) } catch (t: Throwable) { Log.w(TAG, "snapshot save failed", t) }
        }
        if (missing.isNotEmpty()) {
            app.appScope.launch { FilterUpdater.updateMissing(app, missing) }
        }
    }

    // ------------------------------------------------------------ service workers

    private val serviceWorkerPage = HostInfo("service-worker.invalid")

    /** Requests made by service workers bypass WebViewClient; filter them here (treated as third-party). */
    fun interceptServiceWorker(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
        if (!settings.adblock.value) return null
        val url = request.url?.toString() ?: return null
        if (!url.startsWith("http")) return null
        val e = engine ?: return null
        val type = RequestType.infer(url, false, request.requestHeaders)
        val r = e.match(url, serviceWorkerPage, type, request.method, 0)
        if (!r.blocked) return null
        app.stats.blockedRequest()
        val res = r.redirect?.let { Redirects.get(it) } ?: Redirects.forBlockedType(type)
        return android.webkit.WebResourceResponse(
            res.mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(res.data),
        )
    }

    // ------------------------------------------------------------ user rules

    fun readUserRules(): String = if (userRulesFile.isFile) userRulesFile.readText() else ""

    fun writeUserRules(text: String) {
        userRulesFile.writeText(text)
        requestRebuild()
    }

    fun addUserRule(rule: String) {
        val cur = readUserRules()
        if (cur.lines().any { it.trim() == rule.trim() }) return
        val sep = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
        writeUserRules(cur + sep + rule.trim() + "\n")
    }

    // ------------------------------------------------------------ content config for pages

    private class ConfigEntry(val json: String, val exceptions: Set<String>)

    private val configCache = object : LinkedHashMap<String, ConfigEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConfigEntry>?) = size > 48
    }

    private const val OFF = "{\"on\":false}"

    private fun onSettingChanged(key: String) {
        synchronized(configCache) { configCache.clear() }
    }

    fun contentConfig(frameUrl: String, topHost: String?): String {
        if (!settings.adblock.value) return OFF
        val host = Urls.host(frameUrl) ?: return OFF
        if (settings.isAllowlisted(topHost) || settings.isAllowlisted(host)) return OFF
        val e = awaitEngine(5000) ?: return OFF
        val flags = e.pageFlags(frameUrl)
        if (flags and PageFlags.ALLOW_ALL != 0) return OFF
        val cacheKey = "$host|$flags"
        synchronized(configCache) { configCache[cacheKey]?.let { return it.json } }
        val cosmeticOn = settings.cosmetic.value
        val c = e.cosmetics(frameUrl, flags, allowScripts = true)
        val css = StringBuilder()
        if (cosmeticOn) {
            css.append(CosmeticIndex.buildHideCss(c.hide))
            for (r in c.css) css.append(r).append('\n')
            if (c.generic) css.append(e.cosmetic.unkeyedCss(c.exceptions))
        }
        val info = HostInfo(host)
        val json = JSONObject()
            .put("on", true)
            .put("cosm", cosmeticOn)
            .put("gen", cosmeticOn && c.generic)
            .put("css", css.toString())
            .put("proc", JSONArray(if (cosmeticOn) c.procedural else emptyList<String>()))
            .put("sl", JSONArray(c.scriptlets.map { JSONArray(it.toList()) }))
            .put("js", JSONArray(c.js))
            .put("yt", settings.youtube.value && isYouTube(host))
            .put("aggr", settings.strictMode.value && cosmeticOn)
            .put("gpc", settings.gpc.value)
            .put("attr", ContentScripts.attrToken)
            .put("site", info.registrable)
            .toString()
        synchronized(configCache) { configCache[cacheKey] = ConfigEntry(json, c.exceptions) }
        return json
    }

    /** CSS for generic class/id selectors present in a frame. */
    fun genericCss(frameUrl: String, keys: String): String {
        val e = engine ?: return ""
        if (!settings.cosmetic.value) return ""
        val host = Urls.host(frameUrl) ?: return ""
        val flags = e.pageFlags(frameUrl)
        val exceptions = synchronized(configCache) { configCache["$host|$flags"]?.exceptions }
            ?: e.cosmetics(frameUrl, flags, false).exceptions
        val list = keys.split('\n').asSequence().filter { it.length in 2..256 }.take(5000).toList()
        val selectors = e.cosmetic.selectorsForKeys(list, exceptions)
        return if (selectors.isEmpty()) "" else CosmeticIndex.buildHideCss(selectors)
    }

    private fun isYouTube(host: String) =
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com") || host == "youtubekids.com" || host.endsWith(".youtubekids.com")

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun context(): Context = app
}
