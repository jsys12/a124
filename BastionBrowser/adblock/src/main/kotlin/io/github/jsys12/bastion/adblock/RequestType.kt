package io.github.jsys12.bastion.adblock

/**
 * Resource types as bit flags. A request carries one bit when its type is known and
 * [AMBIGUOUS] when WebView gives us no reliable hint (scripts, XHR and fetch all look alike).
 */
object RequestType {
    const val OTHER = 1
    const val SCRIPT = 1 shl 1
    const val IMAGE = 1 shl 2
    const val STYLESHEET = 1 shl 3
    const val OBJECT = 1 shl 4
    const val SUBDOCUMENT = 1 shl 5
    const val XHR = 1 shl 6
    const val PING = 1 shl 7
    const val MEDIA = 1 shl 8
    const val FONT = 1 shl 9
    const val WEBSOCKET = 1 shl 10
    const val DOCUMENT = 1 shl 11
    const val POPUP = 1 shl 12

    /** Every sub-resource type; the default for filters without type options. */
    const val ALL_REQUESTS = OTHER or SCRIPT or IMAGE or STYLESHEET or OBJECT or SUBDOCUMENT or
        XHR or PING or MEDIA or FONT or WEBSOCKET

    const val ALL = ALL_REQUESTS or DOCUMENT or POPUP

    /** What a request with no usable hints might be. */
    const val AMBIGUOUS = SCRIPT or XHR or OTHER or PING or OBJECT

    fun fromOptionName(name: String): Int = when (name) {
        "script" -> SCRIPT
        "image" -> IMAGE
        "stylesheet", "css" -> STYLESHEET
        "object", "object-subrequest" -> OBJECT
        "subdocument", "frame" -> SUBDOCUMENT
        "xmlhttprequest", "xhr", "fetch" -> XHR
        "ping", "beacon" -> PING
        "media" -> MEDIA
        "font" -> FONT
        "websocket" -> WEBSOCKET
        "other", "webtransport" -> OTHER
        "document", "doc" -> DOCUMENT
        "popup" -> POPUP
        else -> 0
    }

    private val EXT_TYPES = hashMapOf(
        "js" to SCRIPT, "mjs" to SCRIPT,
        "css" to STYLESHEET,
        "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE, "gif" to IMAGE, "webp" to IMAGE,
        "svg" to IMAGE, "ico" to IMAGE, "bmp" to IMAGE, "avif" to IMAGE,
        "woff" to FONT, "woff2" to FONT, "ttf" to FONT, "otf" to FONT, "eot" to FONT,
        "mp4" to MEDIA, "webm" to MEDIA, "mp3" to MEDIA, "m4a" to MEDIA, "ogg" to MEDIA,
        "m3u8" to MEDIA, "mpd" to MEDIA, "ts" to MEDIA, "m4s" to MEDIA, "aac" to MEDIA,
        "html" to SUBDOCUMENT, "htm" to SUBDOCUMENT,
        "json" to XHR,
    )

    /**
     * Infers the request type from what WebView exposes: the frame flag, request headers
     * (Sec-Fetch-Dest / Accept / X-Requested-With) and the URL's file extension.
     */
    fun infer(url: String, isMainFrame: Boolean, headers: Map<String, String>?): Int {
        if (isMainFrame) return DOCUMENT
        var accept: String? = null
        if (headers != null) {
            for ((k, v) in headers) {
                when {
                    k.equals("Sec-Fetch-Dest", ignoreCase = true) -> {
                        when (v.lowercase()) {
                            "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> return SCRIPT
                            "style" -> return STYLESHEET
                            "image" -> return IMAGE
                            "font" -> return FONT
                            "iframe", "frame", "fencedframe" -> return SUBDOCUMENT
                            "audio", "video", "track" -> return MEDIA
                            "object", "embed" -> return OBJECT
                            "report" -> return PING
                            "empty" -> return XHR or PING or OTHER
                        }
                    }
                    k.equals("Accept", ignoreCase = true) -> accept = v
                    k.equals("X-Requested-With", ignoreCase = true) && v.equals("XMLHttpRequest", ignoreCase = true) -> return XHR
                }
            }
        }
        if (accept != null) {
            when {
                accept.startsWith("text/css") -> return STYLESHEET
                accept.startsWith("image/") -> return IMAGE
                accept.startsWith("text/html") -> return SUBDOCUMENT
                accept.startsWith("video/") || accept.startsWith("audio/") -> return MEDIA
                accept.startsWith("application/json") -> return XHR
            }
        }
        val ext = extensionOf(url)
        if (ext != null) EXT_TYPES[ext]?.let { return it }
        return AMBIGUOUS
    }

    private fun extensionOf(url: String): String? {
        var end = url.length
        val q = url.indexOf('?')
        if (q in 0 until end) end = q
        val h = url.indexOf('#')
        if (h in 0 until end) end = h
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot <= slash || dot < 0 || end - dot > 6) return null
        return url.substring(dot + 1, end).lowercase()
    }
}
