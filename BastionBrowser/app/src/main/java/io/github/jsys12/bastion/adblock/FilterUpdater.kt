package io.github.jsys12.bastion.adblock

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jsys12.bastion.BastionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Downloads filter lists (conditional GET) and triggers an engine rebuild when something changed. */
object FilterUpdater {
    private const val TAG = "FilterUpdater"
    private const val MAX_SIZE = 40L * 1024 * 1024

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> get() = _updating

    data class Result(val changed: Int, val failed: Int, val checked: Int)

    suspend fun updateAll(app: BastionApp, force: Boolean): Result =
        update(app, AdBlocker.catalog.enabled().filter { it.url.isNotEmpty() }, force)

    suspend fun updateStale(app: BastionApp, maxAgeMs: Long): Result {
        if (app.settings.updateWifiOnly.value && isMetered(app)) return Result(0, 0, 0)
        val installed = try {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        } catch (e: Exception) { 0L }
        val now = System.currentTimeMillis()
        val stale = AdBlocker.catalog.enabled().filter { list ->
            if (list.url.isEmpty()) return@filter false
            val file = File(AdBlocker.filtersDir, "${list.id}.txt")
            // A list bundled in the APK counts as fresh as of install/update time.
            val updated = maxOf(AdBlocker.catalog.lastUpdated(list.id), if (list.bundled && !file.exists()) installed else 0L)
            now - updated > maxAgeMs
        }
        return update(app, stale, force = false)
    }

    suspend fun updateMissing(app: BastionApp, ids: List<String>): Result =
        update(app, AdBlocker.catalog.all().filter { it.id in ids && it.url.isNotEmpty() }, force = true)

    suspend fun updateOne(app: BastionApp, list: FilterListInfo): Result = update(app, listOf(list), force = true)

    private suspend fun update(app: BastionApp, lists: List<FilterListInfo>, force: Boolean): Result {
        if (lists.isEmpty()) return Result(0, 0, 0)
        _updating.value = true
        try {
            val sem = Semaphore(4)
            val results = withContext(Dispatchers.IO) {
                lists.map { list -> async { sem.withPermit { download(app, list, force) } } }.awaitAll()
            }
            val changed = results.count { it == true }
            val failed = results.count { it == null }
            if (changed > 0) AdBlocker.requestRebuild()
            return Result(changed, failed, lists.size)
        } finally {
            _updating.value = false
        }
    }

    /** true = new content, false = not modified, null = failed. */
    private fun download(app: BastionApp, list: FilterListInfo, force: Boolean): Boolean? {
        val catalog = AdBlocker.catalog
        val target = File(AdBlocker.filtersDir, "${list.id}.txt")
        val builder = Request.Builder().url(list.url).header("User-Agent", "BastionBrowser/1.0 (Android)")
        if (!force && target.exists()) {
            catalog.etag(list.id)?.let { builder.header("If-None-Match", it) }
            catalog.lastModified(list.id)?.let { builder.header("If-Modified-Since", it) }
        }
        return try {
            app.http.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    catalog.recordNotModified(list.id)
                    return false
                }
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("empty body")
                if (body.contentLength() > MAX_SIZE) throw IllegalStateException("too large")
                val tmp = File(target.path + ".part")
                var size = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > MAX_SIZE) throw IllegalStateException("too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
                val head = tmp.inputStream().use { it.readNBytesCompat(256) }.toString(Charsets.UTF_8).trimStart()
                if (size < 64 || head.startsWith("<")) {
                    tmp.delete()
                    throw IllegalStateException("not a filter list")
                }
                if (target.exists() && target.length() == size && target.readBytes().contentEquals(tmp.readBytes())) {
                    tmp.delete()
                    catalog.recordDownload(list.id, resp.header("ETag"), resp.header("Last-Modified"))
                    return false
                }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
                catalog.recordDownload(list.id, resp.header("ETag"), resp.header("Last-Modified"))
                Log.i(TAG, "updated ${list.id}: ${size / 1024} KB")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "update ${list.id} failed: ${e.message}")
            catalog.recordError(list.id, e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

class FilterUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BastionApp
        if (!app.settings.autoUpdate.value) return Result.success()
        FilterUpdater.updateStale(app, maxAgeMs = TimeUnit.HOURS.toMillis(20))
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val app = context.applicationContext as BastionApp
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (app.settings.updateWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<FilterUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            try {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork("filter-updates", ExistingPeriodicWorkPolicy.UPDATE, request)
            } catch (e: IllegalStateException) {
                Log.w("FilterUpdater", "WorkManager unavailable: ${e.message}")
            }
        }
    }
}
