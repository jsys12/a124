package io.github.jsys12.bastion.adblock

import android.content.Context
import java.security.SecureRandom

/**
 * Assembles the document-start script (scriptlet library + surrogate resources + content runtime)
 * and the element picker. Global names are random per process so pages can't target them.
 */
object ContentScripts {
    lateinit var documentStart: String
        private set
    lateinit var picker: String
        private set

    val bridgeName = randomName()
    val guardName = randomName()
    val pickerName = randomName()
    val blobName = randomName()
    val attrToken = randomName().lowercase()

    /** Scriptlets that are really surrogate resources (uBO `##+js(nofab)` etc.). */
    private val RESOURCE_SCRIPTLETS = mapOf(
        "nofab" to "fuckadblock.js-3.2.0",
        "nobab" to "fuckadblock.js-3.2.0",
        "popads-dummy" to "popads.js",
        "google-ima" to "google-ima.js",
        "googlesyndication_adsbygoogle" to "googlesyndication_adsbygoogle.js",
        "googletagservices_gpt" to "googletagservices_gpt.js",
        "google-analytics_analytics" to "google-analytics_analytics.js",
        "googletagmanager_gtm" to "googletagmanager_gtm.js",
        "prebid-ads" to "prebid-ads.js",
        "amazon_apstag" to "amazon_apstag.js",
        "fingerprint2" to "fingerprint2.js",
        "fingerprint3" to "fingerprint3.js",
        "ampproject_v0" to "ampproject_v0.js",
        "chartbeat" to "chartbeat.js",
    )

    fun init(context: Context) {
        val assets = context.assets
        val lib = assets.open("bastion/scriptlets.js").bufferedReader().use { it.readText() }
        val content = assets.open("bastion/content.js").bufferedReader().use { it.readText() }
            .replace("%BRIDGE%", bridgeName)
            .replace("%GUARD%", guardName)
        val resources = RESOURCE_SCRIPTLETS.entries.joinToString(",\n") { (name, res) ->
            val src = Redirects.get(res)?.data?.toString(Charsets.UTF_8) ?: "(function(){})();"
            "\"$name\": function() {\n$src\n}"
        }
        documentStart = "(function() {\n$lib\nconst RESOURCES = {\n$resources\n};\n$content\n})();"
        picker = assets.open("bastion/picker.js").bufferedReader().use { it.readText() }
            .replace("%PICKER%", pickerName)
    }

    private fun randomName(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val r = SecureRandom()
        return "_" + (1..14).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
