package io.github.jsys12.bastion.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import io.github.jsys12.bastion.R
import java.io.File
import java.net.URLDecoder

object Downloads {
    fun enqueue(
        context: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?,
        referer: String?, name: String,
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            userAgent?.let { addRequestHeader("User-Agent", it) }
            if (referer != null && referer.startsWith("http")) addRequestHeader("Referer", referer)
            setTitle(name)
            setDescription(Uri.parse(url).host)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    fun openDownloads(context: Context) {
        try {
            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { /* no downloads UI */ }
    }

    fun saveDataUrl(c: BrowserController, dataUrl: String, contentDisposition: String?, mimeType: String?) {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return
        val meta = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        val mime = mimeType ?: meta.substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = try {
            if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else URLDecoder.decode(payload, "UTF-8").toByteArray()
        } catch (e: Exception) {
            c.showSnackbar(c.activity.getString(R.string.download_failed)); return
        }
        var name = URLUtil.guessFileName("data:", contentDisposition, mime)
        if (name.startsWith("downloadfile")) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            name = "download_${System.currentTimeMillis()}.$ext"
        }
        val ok = saveBytes(c.activity, name, mime, bytes)
        c.showSnackbar(c.activity.getString(if (ok) R.string.download_saved else R.string.download_failed, name))
    }

    private fun saveBytes(context: Context, name: String, mime: String, bytes: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
        true
    } catch (e: Exception) {
        false
    }
}

/** Receives blob: downloads converted to data URLs by an injected script (nonce-checked). */
class BlobReceiver(private val c: BrowserController) {
    @JavascriptInterface
    fun onData(nonce: String?, mime: String?, dataUrl: String?) {
        if (nonce.isNullOrEmpty() || (dataUrl?.length ?: 0) > 80_000_000) return
        c.onBlobData(nonce, mime ?: "", dataUrl ?: "")
    }
}
