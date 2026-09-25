package io.github.jsys12.bastion.adblock

/** One network (URL) filter such as `||ads.example.com^$script,third-party`. */
class NetworkFilter(
    /** Pattern without anchors; lowercased unless [MATCH_CASE]. For regex filters the regex source. */
    @JvmField val pattern: String,
    @JvmField val flags: Int,
    @JvmField val typeMask: Int,
    @JvmField val domains: DomainConstraint?,
    @JvmField val toDomains: DomainConstraint?,
    @JvmField val methods: Int,
    /** Redirect resource, removeparam value or urlskip spec depending on [flags]. */
    @JvmField val option: String?,
    /** Page-level exception flags (see [PageFlags]) for `@@...$elemhide`-like filters. */
    @JvmField val pageFlags: Int,
    @JvmField val listId: Int,
) {
    private var compiled: Regex? = null
    private var regexFailed = false

    /** Compiled lazily so snapshot loading stays cheap. */
    var regex: Regex?
        get() {
            if (compiled == null && !regexFailed && flags and REGEX != 0) {
                compiled = try {
                    if (flags and MATCH_CASE != 0) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    regexFailed = true
                    null
                }
            }
            return compiled
        }
        set(value) { compiled = value }

    /** Length of the literal prefix of [pattern] (up to the first `*` or `^`). */
    @JvmField val literalPrefix: Int = run {
        if (flags and REGEX != 0) 0 else {
            var i = 0
            while (i < pattern.length && pattern[i] != '*' && pattern[i] != '^') i++
            i
        }
    }

    private val literal: String = pattern.substring(0, literalPrefix)

    /** For host-indexed filters: length of the complete hostname at the start of [pattern]. */
    @JvmField var hostLen: Int = 0

    val isException get() = flags and EXCEPTION != 0
    val isImportant get() = flags and IMPORTANT != 0

    fun matchesUrl(ctx: RequestContext): Boolean {
        val url = if (flags and MATCH_CASE != 0) ctx.url else ctx.urlLower
        val right = flags and RIGHT_ANCHOR != 0
        return when {
            flags and REGEX != 0 -> regex?.containsMatchIn(url) == true
            flags and HOST_ANCHOR != 0 -> matchHostAnchored(url, ctx.hostStart, ctx.hostEnd, right)
            flags and LEFT_ANCHOR != 0 -> Glob.matchAt(url, 0, pattern, 0, right, false)
            pattern.isEmpty() -> true
            else -> find(url, right)
        }
    }

    /** Called when the request host is known to end with this filter's hostname at [hostEnd]. */
    fun matchesAfterHost(ctx: RequestContext): Boolean {
        val url = if (flags and MATCH_CASE != 0) ctx.url else ctx.urlLower
        return Glob.matchAt(url, ctx.hostEnd, pattern, hostLen, flags and RIGHT_ANCHOR != 0, false)
    }

    private fun matchHostAnchored(url: String, hostStart: Int, hostEnd: Int, right: Boolean): Boolean {
        if (hostEnd <= hostStart) return false
        var pos = hostStart
        while (true) {
            if (Glob.matchAt(url, pos, pattern, 0, right, false)) return true
            var next = -1
            for (i in pos until hostEnd) if (url[i] == '.') { next = i + 1; break }
            if (next < 0 || next >= hostEnd) return false
            pos = next
        }
    }

    private fun find(url: String, right: Boolean): Boolean {
        val lp = literalPrefix
        if (lp == 0) return Glob.matchAt(url, 0, pattern, 0, right, true)
        var i = url.indexOf(literal)
        while (i >= 0) {
            if (Glob.matchAt(url, i + lp, pattern, lp, right, false)) return true
            i = url.indexOf(literal, i + 1)
        }
        return false
    }

    fun matchesOptions(ctx: RequestContext): Boolean {
        if (typeMask and ctx.type == 0) return false
        if (ctx.thirdParty) {
            if (flags and THIRD_PARTY == 0) return false
        } else if (flags and FIRST_PARTY == 0) return false
        if (flags and (STRICT_1P or STRICT_3P) != 0) {
            val sameHost = ctx.host.host == ctx.page.host
            if (flags and STRICT_1P != 0 && !sameHost) return false
            if (flags and STRICT_3P != 0 && sameHost) return false
        }
        if (methods != 0 && methods and ctx.method == 0) return false
        if (domains != null && !domains.matches(ctx.page)) return false
        if (toDomains != null && !toDomains.matches(ctx.host)) return false
        return true
    }

    fun matches(ctx: RequestContext) = matchesOptions(ctx) && matchesUrl(ctx)

    /** Human-readable approximation of the original filter text (for logs). */
    fun describe(): String {
        val sb = StringBuilder()
        if (isException) sb.append("@@")
        when {
            flags and HOST_ANCHOR != 0 -> sb.append("||")
            flags and LEFT_ANCHOR != 0 -> sb.append('|')
        }
        if (flags and REGEX != 0) sb.append('/').append(pattern).append('/') else sb.append(pattern)
        if (flags and RIGHT_ANCHOR != 0) sb.append('|')
        val opts = ArrayList<String>()
        if (flags and FIRST_PARTY == 0) opts.add("3p") else if (flags and THIRD_PARTY == 0) opts.add("1p")
        if (typeMask != RequestType.ALL_REQUESTS && typeMask != RequestType.ALL_REQUESTS or RequestType.DOCUMENT) {
            for ((name, bit) in TYPE_NAMES) if (typeMask and bit != 0) opts.add(name)
        }
        if (flags and IMPORTANT != 0) opts.add("important")
        domains?.let { opts.add("domain=" + it.includeList.joinToString("|").ifEmpty { "~…" }) }
        if (flags and REDIRECT != 0) opts.add("redirect=$option")
        if (flags and REMOVEPARAM != 0) opts.add("removeparam" + (option?.let { "=$it" } ?: ""))
        if (opts.isNotEmpty()) sb.append('$').append(opts.joinToString(","))
        return sb.toString()
    }

    companion object {
        const val EXCEPTION = 1
        const val HOST_ANCHOR = 1 shl 1
        const val LEFT_ANCHOR = 1 shl 2
        const val RIGHT_ANCHOR = 1 shl 3
        const val REGEX = 1 shl 4
        const val MATCH_CASE = 1 shl 5
        const val IMPORTANT = 1 shl 6
        const val FIRST_PARTY = 1 shl 7
        const val THIRD_PARTY = 1 shl 8
        const val STRICT_1P = 1 shl 9
        const val STRICT_3P = 1 shl 10
        const val REDIRECT = 1 shl 11
        const val REDIRECT_RULE = 1 shl 12
        const val REMOVEPARAM = 1 shl 13
        const val URLSKIP = 1 shl 14
        const val GENERIC = 1 shl 15 // no positive $domain= entries

        private val TYPE_NAMES = listOf(
            "script" to RequestType.SCRIPT, "image" to RequestType.IMAGE, "css" to RequestType.STYLESHEET,
            "object" to RequestType.OBJECT, "frame" to RequestType.SUBDOCUMENT, "xhr" to RequestType.XHR,
            "ping" to RequestType.PING, "media" to RequestType.MEDIA, "font" to RequestType.FONT,
            "websocket" to RequestType.WEBSOCKET, "other" to RequestType.OTHER, "doc" to RequestType.DOCUMENT,
            "popup" to RequestType.POPUP,
        )
    }
}

object PageFlags {
    /** `@@...$document`: everything allowed on the page. */
    const val ALLOW_ALL = 1
    const val ELEMHIDE = 1 shl 1
    const val GENERICHIDE = 1 shl 2
    const val SPECIFICHIDE = 1 shl 3
    const val GENERICBLOCK = 1 shl 4
    /** No scriptlets / JS rules. */
    const val JSINJECT = 1 shl 5
    /** No network blocking. */
    const val URLBLOCK = 1 shl 6
    /** `$popup` exception on the opener page. */
    const val POPUP = 1 shl 7
}

object Methods {
    fun bit(name: String): Int = when (name.uppercase()) {
        "GET" -> 1; "POST" -> 2; "HEAD" -> 4; "PUT" -> 8; "DELETE" -> 16
        "PATCH" -> 32; "OPTIONS" -> 64; "CONNECT" -> 128; "TRACE" -> 256
        else -> 0
    }
    const val ALL = 511
}

/** ABP-style wildcard matching: `*` any run, `^` separator or end of input. */
object Glob {
    fun isSeparator(c: Char): Boolean =
        !(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '%')

    /**
     * Matches p[pStart..] against s starting at sStart. Without [rightAnchor] the pattern only has
     * to match a prefix of the remaining input. [floating] behaves like a leading `*`.
     */
    fun matchAt(s: String, sStart: Int, p: String, pStart: Int, rightAnchor: Boolean, floating: Boolean): Boolean {
        var si = sStart
        var pi = pStart
        var hasStar = floating
        var resumeP = pStart
        var resumeS = sStart
        val sl = s.length
        val pl = p.length
        while (true) {
            if (pi == pl) {
                if (!rightAnchor || si == sl) return true
            } else {
                val pc = p[pi]
                if (pc == '*') {
                    pi++
                    hasStar = true
                    resumeP = pi
                    resumeS = si
                    continue
                }
                if (si < sl) {
                    val sc = s[si]
                    if (if (pc == '^') isSeparator(sc) else pc == sc) {
                        pi++; si++; continue
                    }
                } else if (pc == '^') {
                    pi++; continue
                }
            }
            if (!hasStar || resumeS >= sl) return false
            resumeS++
            si = resumeS
            pi = resumeP
        }
    }
}
