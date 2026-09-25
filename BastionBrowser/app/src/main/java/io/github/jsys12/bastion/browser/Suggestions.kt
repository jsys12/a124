package io.github.jsys12.bastion.browser

import io.github.jsys12.bastion.BastionApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Suggestions {
    private val client by lazy {
        BastionApp.instance.http.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    }

    /** Search suggestions in the OpenSearch JSON format: ["query", ["s1", "s2", ...]]. */
    suspend fun fetch(engine: SearchEngine, query: String): List<String> = withContext(Dispatchers.IO) {
        val template = engine.suggest ?: return@withContext emptyList()
        if (query.isBlank() || query.length > 200) return@withContext emptyList()
        try {
            val url = template.replace("%s", URLEncoder.encode(query, "UTF-8"))
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string() ?: return@withContext emptyList())
                val list = arr.optJSONArray(1) ?: return@withContext emptyList()
                (0 until minOf(list.length(), 6)).mapNotNull { i -> list.optString(i).takeIf { it.isNotBlank() } }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
