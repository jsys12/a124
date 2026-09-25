package io.github.jsys12.bastion.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/** Remembers site icons per host for tabs, history and the start page. */
object FaviconCache {
    private val memory = LruCache<String, Bitmap>(128)
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.cacheDir, "favicons").apply { mkdirs() }
    }

    fun get(host: String?): Bitmap? {
        if (host.isNullOrEmpty() || !::dir.isInitialized) return null
        memory.get(host)?.let { return it }
        val f = File(dir, "${host.hashCode()}.png")
        if (!f.isFile) return null
        return BitmapFactory.decodeFile(f.path)?.also { memory.put(host, it) }
    }

    fun put(host: String?, icon: Bitmap?) {
        if (host.isNullOrEmpty() || icon == null || !::dir.isInitialized) return
        val existing = memory.get(host)
        if (existing != null && existing.width >= icon.width) return
        memory.put(host, icon)
        try {
            File(dir, "${host.hashCode()}.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) { /* cache only */ }
    }
}
