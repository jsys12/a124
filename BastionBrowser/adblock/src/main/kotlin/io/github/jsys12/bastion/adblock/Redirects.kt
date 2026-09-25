package io.github.jsys12.bastion.adblock

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Neutered stand-ins served instead of blocked resources so pages keep working. */
object Redirects {
    class Resource(val mime: String, val data: ByteArray)

    private class Def(val mime: String, val file: String? = null, val base64: String? = null, val gen: (() -> ByteArray)? = null)

    private const val GIF_1X1 = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"

    private val defs = mapOf(
        "noop.js" to Def("application/javascript", file = "noop.js"),
        "noop.txt" to Def("text/plain", file = "noop.txt"),
        "noop.html" to Def("text/html", file = "noop.html"),
        "noop.css" to Def("text/css", file = "noop.css"),
        "noop.json" to Def("application/json", file = "noop.json"),
        "empty" to Def("text/plain", file = "noop.txt"),
        "1x1.gif" to Def("image/gif", base64 = GIF_1X1),
        "2x2.png" to Def("image/png", base64 = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR42mNgQAcAABIAAeRVjecAAAAASUVORK5CYII="),
        "3x2.png" to Def("image/png", base64 = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAC0lEQVR42mNgwAUAABoAAS+Yl6YAAAAASUVORK5CYII="),
        "32x32.png" to Def("image/png", base64 = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGklEQVR42u3BAQEAAACCIP+vbkhAAQAAAO8GECAAAcm1w7EAAAAASUVORK5CYII="),
        "noop-0.1s.mp3" to Def("audio/mpeg", gen = { silentMp3(4) }),
        "noop-0.5s.mp3" to Def("audio/mpeg", gen = { silentMp3(20) }),
        "noop-1s.mp4" to Def("video/mp4", file = "noop.txt"),
        "noop-vast2.xml" to Def("application/xml", file = "noop-vast2.xml"),
        "noop-vast3.xml" to Def("application/xml", file = "noop-vast3.xml"),
        "noop-vast4.xml" to Def("application/xml", file = "noop-vast4.xml"),
        "noop-vmap1.0.xml" to Def("application/xml", file = "noop-vmap1.xml"),
        "google-analytics_analytics.js" to Def("application/javascript", file = "google-analytics_analytics.js"),
        "google-analytics_ga.js" to Def("application/javascript", file = "google-analytics_ga.js"),
        "googletagmanager_gtm.js" to Def("application/javascript", file = "googletagmanager_gtm.js"),
        "googlesyndication_adsbygoogle.js" to Def("application/javascript", file = "googlesyndication_adsbygoogle.js"),
        "googletagservices_gpt.js" to Def("application/javascript", file = "googletagservices_gpt.js"),
        "google-ima.js" to Def("application/javascript", file = "google-ima.js"),
        "fuckadblock.js-3.2.0" to Def("application/javascript", file = "fuckadblock.js"),
        "prebid-ads.js" to Def("application/javascript", file = "prebid-ads.js"),
        "amazon_apstag.js" to Def("application/javascript", file = "amazon_apstag.js"),
        "fingerprint2.js" to Def("application/javascript", file = "fingerprint2.js"),
        "fingerprint3.js" to Def("application/javascript", file = "fingerprint3.js"),
        "chartbeat.js" to Def("application/javascript", file = "chartbeat.js"),
        "scorecardresearch_beacon.js" to Def("application/javascript", file = "scorecardresearch_beacon.js"),
        "ampproject_v0.js" to Def("application/javascript", file = "ampproject_v0.js"),
        "popads.js" to Def("application/javascript", file = "popads.js"),
        "metrika-yandex.js" to Def("application/javascript", file = "metrika-yandex.js"),
    )

    private val aliases = mapOf(
        "noopjs" to "noop.js", "blank-js" to "noop.js", "noop-js" to "noop.js",
        "nooptext" to "noop.txt", "blank-text" to "noop.txt",
        "noopframe" to "noop.html", "blank-html" to "noop.html", "click2load.html" to "noop.html",
        "noopcss" to "noop.css", "blank-css" to "noop.css",
        "noopjson" to "noop.json",
        "1x1-transparent.gif" to "1x1.gif", "1x1-transparent-gif" to "1x1.gif",
        "2x2-transparent.png" to "2x2.png", "2x2-transparent-png" to "2x2.png",
        "3x2-transparent.png" to "3x2.png", "3x2-transparent-png" to "3x2.png",
        "32x32-transparent.png" to "32x32.png", "32x32-transparent-png" to "32x32.png",
        "noopmp3-0.1s" to "noop-0.1s.mp3", "blank-mp3" to "noop-0.1s.mp3", "noopmp3" to "noop-0.1s.mp3",
        "noop-0.5s.mp3" to "noop-0.5s.mp3",
        "noopmp4-1s" to "noop-1s.mp4", "blank-mp4" to "noop-1s.mp4", "noopmp4" to "noop-1s.mp4",
        "noopvast-2.0" to "noop-vast2.xml", "noopvast-3.0" to "noop-vast3.xml", "noopvast-4.0" to "noop-vast4.xml",
        "noopvmap-1.0" to "noop-vmap1.0.xml", "noop-vmap1.0.xml" to "noop-vmap1.0.xml",
        "google-analytics.com/analytics.js" to "google-analytics_analytics.js", "google-analytics" to "google-analytics_analytics.js",
        "googletagmanager_gtm" to "googletagmanager_gtm.js",
        "google-analytics.com/ga.js" to "google-analytics_ga.js", "google-analytics-ga" to "google-analytics_ga.js",
        "googletagmanager.com/gtm.js" to "googletagmanager_gtm.js", "googletagmanager-gtm" to "googletagmanager_gtm.js",
        "googlesyndication.com/adsbygoogle.js" to "googlesyndication_adsbygoogle.js",
        "googlesyndication-adsbygoogle" to "googlesyndication_adsbygoogle.js",
        "googletagservices.com/gpt.js" to "googletagservices_gpt.js", "googletagservices-gpt" to "googletagservices_gpt.js",
        "google-ima3" to "google-ima.js", "google-ima" to "google-ima.js",
        "fuckadblock.js" to "fuckadblock.js-3.2.0", "nofab.js" to "fuckadblock.js-3.2.0", "nofab" to "fuckadblock.js-3.2.0",
        "prevent-fab-3.2.0" to "fuckadblock.js-3.2.0", "nobab.js" to "fuckadblock.js-3.2.0", "nobab2.js" to "fuckadblock.js-3.2.0",
        "nobab" to "fuckadblock.js-3.2.0", "prevent-bab" to "fuckadblock.js-3.2.0", "prevent-bab2" to "fuckadblock.js-3.2.0",
        "prebid" to "prebid-ads.js", "prebid-ads" to "prebid-ads.js",
        "amazon-apstag" to "amazon_apstag.js", "amazon-adsystem.com/aax2/amzn_ads.js" to "amazon_apstag.js",
        "fingerprintjs2" to "fingerprint2.js", "fingerprintjs3" to "fingerprint3.js",
        "chartbeat" to "chartbeat.js",
        "scorecardresearch-beacon" to "scorecardresearch_beacon.js",
        "ampproject.org/v0.js" to "ampproject_v0.js",
        "popads.net.js" to "popads.js", "prevent-popads-net" to "popads.js", "popads-dummy.js" to "popads.js",
        "metrika-yandex-watch" to "metrika-yandex.js", "metrika-yandex-tag" to "metrika-yandex.js",
        "gemius" to "noop.js", "matomo" to "noop.js", "naver-wcslog" to "noop.js", "ati-smarttag" to "noop.js",
        "pardot-1.0" to "noop.js", "outbrain-widget.js" to "noop.js", "hd-main.js" to "noop.js",
    )

    private val cache = ConcurrentHashMap<String, Resource>()

    /** Canonical resource name for a filter's `redirect=` value, or null if unknown. */
    fun canonical(name: String): String? {
        val n = name.trim().lowercase()
        if (n in defs) return n
        return aliases[n]
    }

    fun get(name: String): Resource? {
        cache[name]?.let { return it }
        val def = defs[name] ?: return null
        val data = when {
            def.base64 != null -> Base64.getDecoder().decode(def.base64)
            def.gen != null -> def.gen.invoke()
            def.file != null -> Redirects::class.java.getResourceAsStream("/bastion/redirects/${def.file}")?.use { it.readBytes() }
                ?: ByteArray(0)
            else -> ByteArray(0)
        }
        return Resource(def.mime, data).also { cache[name] = it }
    }

    /** The response served for a blocked request of the given type when no redirect is specified. */
    fun forBlockedType(type: Int): Resource = when {
        type == RequestType.IMAGE -> get("1x1.gif")!!
        type == RequestType.SCRIPT -> get("noop.js")!!
        type == RequestType.STYLESHEET -> get("noop.css")!!
        type == RequestType.SUBDOCUMENT -> get("noop.html")!!
        else -> get("noop.txt")!!
    }

    /** MPEG-1 Layer III mono 128 kbps frames whose data is all zeros, which decodes to silence. */
    private fun silentMp3(frames: Int): ByteArray {
        val frameLen = 417
        val out = ByteArray(frameLen * frames)
        for (f in 0 until frames) {
            val o = f * frameLen
            out[o] = 0xFF.toByte(); out[o + 1] = 0xFB.toByte(); out[o + 2] = 0x90.toByte(); out[o + 3] = 0xC0.toByte()
        }
        return out
    }
}
