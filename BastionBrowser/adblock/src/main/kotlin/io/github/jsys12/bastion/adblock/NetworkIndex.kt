package io.github.jsys12.bastion.adblock

/** Per-request matching state. Instances are reused per thread. */
class RequestContext {
    var url: String = ""
    var urlLower: String = ""
    var hostStart = 0
    var hostEnd = 0
    var host: HostInfo = HostInfo("")
    var page: HostInfo = HostInfo("")
    var type = 0
    var thirdParty = false
    var method = Methods.ALL
    /** Set when a hostname blocklist entry matched (for logging). */
    var matchedHost: String? = null
    val tokens = LongArray(MAX_TOKENS)
    var tokenCount = 0

    fun reset(url: String, page: HostInfo, type: Int, method: String?): Boolean {
        val range = Urls.hostRange(url)
        if (range < 0) return false
        this.url = url
        this.urlLower = url.lowercase()
        hostStart = (range ushr 32).toInt()
        hostEnd = range.toInt()
        if (hostEnd <= hostStart) return false
        host = HostInfo(urlLower.substring(hostStart, hostEnd))
        this.page = if (page.host.isEmpty()) host else page
        this.type = type
        thirdParty = !host.isSameSite(this.page)
        this.method = if (method == null) Methods.ALL else Methods.bit(method).let { if (it == 0) Methods.ALL else it }
        matchedHost = null
        tokenize()
        return true
    }

    private fun tokenize() {
        val s = urlLower
        var n = 0
        var i = 0
        val len = s.length
        while (i < len && n < MAX_TOKENS) {
            if (!Tokens.isTokenChar(s[i])) {
                i++; continue
            }
            val start = i
            while (i < len && Tokens.isTokenChar(s[i])) i++
            if (i - start >= Tokens.MIN_LEN) tokens[n++] = Tokens.hash(s, start, i)
        }
        tokenCount = n
    }

    companion object {
        const val MAX_TOKENS = 256
    }
}

object Tokens {
    const val MIN_LEN = 2

    fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

    fun hash(s: CharSequence, start: Int, end: Int): Long {
        var h = 1125899906842597L
        for (i in start until end) h = 31 * h + s[i].code
        return if (h == 0L) 7L else h
    }

    /** Tokens so common in URLs that they make poor index keys. */
    val BAD = hashSetOf(
        "http", "https", "www", "com", "net", "org", "js", "html", "htm", "php", "css", "png", "jpg",
        "gif", "jpeg", "webp", "svg", "json", "static", "cdn", "api", "img", "images", "assets", "min",
        "wp", "content", "uploads", "index", "ru", "de", "uk", "co", "io", "v1", "v2", "id", "src",
        "ad", "ads", "js%", "utm", "www2", "media", "files", "scripts", "script", "com%",
    ).mapTo(HashSet()) { hash(it, 0, it.length) }

    /**
     * Candidate tokens of a normalized (lowercased) pattern: runs of token chars that must appear as
     * complete tokens in any URL the pattern matches.
     */
    fun candidates(p: String, leftAnchored: Boolean, rightAnchored: Boolean): LongArray {
        var out = LongArray(4)
        var n = 0
        var i = 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) {
                i++; continue
            }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val end = i
            if (end - start < MIN_LEN) continue
            val leftOk = if (start == 0) leftAnchored else p[start - 1] != '*'
            val rightOk = if (end == p.length) rightAnchored else p[end] != '*'
            if (!leftOk || !rightOk) continue
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = hash(p, start, end)
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /**
     * Best-effort literal tokens of a regex. Only runs bounded by explicit literal separators and
     * outside groups/classes qualify; anything with alternation yields none.
     */
    fun regexCandidates(re: String): LongArray {
        if (re.contains('|') || re.contains("(?")) return LongArray(0)
        val out = ArrayList<Long>()
        var depth = 0
        var i = 0
        val n = re.length
        fun literalBoundaryBefore(pos: Int): Boolean {
            if (pos == 0) return false
            val c = re[pos - 1]
            if (pos >= 2 && re[pos - 2] == '\\') return !c.isLetterOrDigit() // escaped punctuation: \. \/ \?
            return c in "/=&,:;_-~@!#'\""
        }
        while (i < n) {
            val c = re[i]
            when {
                c == '\\' -> { i += 2; continue }
                c == '[' -> {
                    i++
                    while (i < n && re[i] != ']') { if (re[i] == '\\') i++; i++ }
                    i++; continue
                }
                c == '(' -> { depth++; i++; continue }
                c == ')' -> { depth--; i++; continue }
                c in 'a'..'z' || c in '0'..'9' || c == '%' -> {
                    val start = i
                    while (i < n && (re[i] in 'a'..'z' || re[i] in '0'..'9' || re[i] == '%')) i++
                    val end = i
                    if (depth != 0 || end - start < 3) continue
                    if (!literalBoundaryBefore(start)) continue
                    if (end >= n) continue
                    val next = re[end]
                    val boundaryAfter = when {
                        next == '\\' && end + 1 < n -> !re[end + 1].isLetterOrDigit()
                        else -> next in "/=&,:;_-~@!#'\""
                    }
                    // A quantifier right after the boundary char would make it optional.
                    val afterBoundary = if (next == '\\') end + 2 else end + 1
                    val quantified = afterBoundary < n && re[afterBoundary] in "?*{"
                    if (boundaryAfter && !quantified) out.add(hash(re, start, end))
                    continue
                }
                else -> i++
            }
        }
        return out.toLongArray()
    }
}

/**
 * Index over network filters. Pure hostname filters live in hash sets; filters anchored to a
 * complete hostname are keyed by that hostname; the rest by their rarest token.
 */
class NetworkIndex {
    /** `||host^` with no options: block everything under host (including documents). */
    private var pureHosts = LongHashSet(1024)
    /** `||host^$type,party` without other options: host -> packed type mask + party bits. */
    private var simpleHosts = LongIntMap(1024)
    private val hostFilters = LongObjMap<Any>(1024)
    private val tokenFilters = LongObjMap<Any>(1024)
    private val pageDomainFilters = LongObjMap<Any>(256)
    private var untokenized = ArrayList<NetworkFilter>()

    private val pending = ArrayList<Pair<NetworkFilter, LongArray>>()
    private val tokenFreq = LongIntMap(4096)
    private var frozen = false

    var filterCount = 0
        private set

    fun addPureHost(host: String) {
        pureHosts.add(Hash.of(host)); filterCount++
    }

    fun removePureHost(host: String) {
        pureHosts.remove(Hash.of(host))
    }

    /** Returns false when the combination can't be stored compactly (caller adds a full filter). */
    fun addSimpleHost(host: String, typeMask: Int, firstParty: Boolean, thirdParty: Boolean): Boolean {
        val h = Hash.of(host)
        val party = (if (firstParty) SIMPLE_1P else 0) or (if (thirdParty) SIMPLE_3P else 0)
        val existing = simpleHosts.get(h, 0)
        if (existing != 0 && existing and PARTY_MASK != party) return false
        simpleHosts.put(h, existing or typeMask or party)
        filterCount++
        return true
    }

    fun add(filter: NetworkFilter) {
        check(!frozen)
        filterCount++
        val f = filter.flags
        if (f and NetworkFilter.HOST_ANCHOR != 0 && f and NetworkFilter.REGEX == 0) {
            val hl = completeHostLength(filter.pattern)
            if (hl > 0) {
                filter.hostLen = hl
                append(hostFilters, Hash.of(filter.pattern, 0, hl), filter)
                return
            }
        }
        val tokens = if (f and NetworkFilter.REGEX != 0) Tokens.regexCandidates(filter.pattern)
        else Tokens.candidates(
            filter.pattern,
            leftAnchored = f and (NetworkFilter.HOST_ANCHOR or NetworkFilter.LEFT_ANCHOR) != 0,
            rightAnchored = f and NetworkFilter.RIGHT_ANCHOR != 0,
        )
        if (tokens.isEmpty()) {
            val d = filter.domains
            if (d != null && d.hasIncludes && d.includeList.none { it.endsWith(".*") }) {
                for (dom in d.includeList) append(pageDomainFilters, Hash.of(dom), filter)
            } else {
                untokenized.add(filter)
            }
            return
        }
        for (t in tokens) tokenFreq.put(t, tokenFreq.get(t) + 1)
        pending.add(filter to tokens)
    }

    /** Assigns pending filters to their best token bucket. Call once after all adds. */
    fun freeze() {
        for ((filter, tokens) in pending) {
            var best = tokens[0]
            var bestScore = Int.MAX_VALUE
            for (t in tokens) {
                var score = tokenFreq.get(t)
                if (t in Tokens.BAD) score += 100_000
                if (score < bestScore) {
                    bestScore = score; best = t
                }
            }
            append(tokenFilters, best, filter)
        }
        pending.clear()
        untokenized.trimToSize()
        compact(hostFilters)
        compact(tokenFilters)
        compact(pageDomainFilters)
        frozen = true
    }

    fun debugStats(): String {
        var maxToken = 0
        var maxTokenKey = 0L
        var totalToken = 0
        tokenFilters.forEachValue { v -> val n = (v as Array<*>).size; totalToken += n; if (n > maxToken) { maxToken = n } }
        var hostTotal = 0
        hostFilters.forEachValue { v -> hostTotal += (v as Array<*>).size }
        var pd = 0
        pageDomainFilters.forEachValue { v -> pd += (v as Array<*>).size }
        return "pure=${pureHosts.size} simple=${simpleHosts.size} hostFilters=$hostTotal/${hostFilters.size} " +
            "token=$totalToken/${tokenFilters.size} maxBucket=$maxToken pageDomain=$pd untokenized=${untokenized.size}"
    }

    fun largestBuckets(n: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        tokenFilters.forEachValue { v ->
            @Suppress("UNCHECKED_CAST") val arr = v as Array<NetworkFilter>
            out.add(arr.size to arr.take(3).joinToString(" ") { it.describe() })
        }
        return out.sortedByDescending { it.first }.take(n)
    }

    fun untokenizedSample(n: Int) = untokenized.take(n).map { it.describe() }

    /** First matching filter, [PURE_HOST] / [SIMPLE_HOST] sentinels for hostname-set hits, or null. */
    fun match(ctx: RequestContext, genericAllowed: Boolean = true): NetworkFilter? {
        val hashes = ctx.host.suffixHashes
        for (i in hashes.indices) {
            val h = hashes[i]
            if (genericAllowed) {
                if (h in pureHosts) {
                    ctx.matchedHost = suffixAt(ctx.host.host, i)
                    return PURE_HOST
                }
                val packed = simpleHosts.get(h, 0)
                if (packed != 0 && packed and ctx.type != 0 &&
                    packed and (if (ctx.thirdParty) SIMPLE_3P else SIMPLE_1P) != 0
                ) {
                    ctx.matchedHost = suffixAt(ctx.host.host, i)
                    return SIMPLE_HOST
                }
            }
            val hf = hostFilters[h]
            if (hf != null) {
                @Suppress("UNCHECKED_CAST")
                for (f in hf as Array<NetworkFilter>) {
                    if ((genericAllowed || f.flags and NetworkFilter.GENERIC == 0) &&
                        f.matchesOptions(ctx) && f.matchesAfterHost(ctx)
                    ) return f
                }
            }
        }
        for (i in 0 until ctx.tokenCount) {
            val bucket = tokenFilters[ctx.tokens[i]] ?: continue
            @Suppress("UNCHECKED_CAST")
            for (f in bucket as Array<NetworkFilter>) {
                if ((genericAllowed || f.flags and NetworkFilter.GENERIC == 0) && f.matches(ctx)) return f
            }
        }
        if (pageDomainFilters.size > 0) {
            for (h in ctx.page.suffixHashes) {
                val bucket = pageDomainFilters[h] ?: continue
                @Suppress("UNCHECKED_CAST")
                for (f in bucket as Array<NetworkFilter>) if (f.matches(ctx)) return f
            }
        }
        for (f in untokenized) {
            if ((genericAllowed || f.flags and NetworkFilter.GENERIC == 0) && f.matches(ctx)) return f
        }
        return null
    }

    /** All matching filters (used for removeparam). Hostname sets are not consulted. */
    fun matchAll(ctx: RequestContext, out: MutableList<NetworkFilter>) {
        for (h in ctx.host.suffixHashes) {
            val hf = hostFilters[h] ?: continue
            @Suppress("UNCHECKED_CAST")
            for (f in hf as Array<NetworkFilter>) if (f.matchesOptions(ctx) && f.matchesAfterHost(ctx)) out.add(f)
        }
        val seen = HashSet<Long>()
        for (i in 0 until ctx.tokenCount) {
            if (!seen.add(ctx.tokens[i])) continue
            val bucket = tokenFilters[ctx.tokens[i]] ?: continue
            @Suppress("UNCHECKED_CAST")
            for (f in bucket as Array<NetworkFilter>) if (f.matches(ctx)) out.add(f)
        }
        for (h in ctx.page.suffixHashes) {
            val bucket = pageDomainFilters[h] ?: continue
            @Suppress("UNCHECKED_CAST")
            for (f in bucket as Array<NetworkFilter>) if (f.matches(ctx) && f !in out) out.add(f)
        }
        for (f in untokenized) if (f.matches(ctx)) out.add(f)
    }

    internal fun collectFilters(table: Snapshot.FilterTable) {
        val visit = { v: Any -> @Suppress("UNCHECKED_CAST") for (f in v as Array<NetworkFilter>) table.id(f) }
        hostFilters.forEachValue(visit)
        tokenFilters.forEachValue(visit)
        pageDomainFilters.forEachValue(visit)
        for (f in untokenized) table.id(f)
    }

    internal fun writeTo(out: java.io.DataOutputStream, table: Snapshot.FilterTable) {
        check(frozen)
        out.writeInt(filterCount)
        pureHosts.writeTo(out)
        simpleHosts.writeTo(out)
        for (map in arrayOf(hostFilters, tokenFilters, pageDomainFilters)) {
            out.writeInt(map.size)
            map.forEachEntry { k, v ->
                @Suppress("UNCHECKED_CAST") val arr = v as Array<NetworkFilter>
                out.writeLong(k)
                out.writeInt(arr.size)
                for (f in arr) out.writeInt(table.ids[f]!!)
            }
        }
        out.writeInt(untokenized.size)
        for (f in untokenized) out.writeInt(table.ids[f]!!)
    }

    internal fun readFrom(inp: java.io.DataInputStream, filters: Array<NetworkFilter>) {
        filterCount = inp.readInt()
        pureHosts = LongHashSet.readFrom(inp)
        simpleHosts = LongIntMap.readFrom(inp)
        for (map in arrayOf(hostFilters, tokenFilters, pageDomainFilters)) {
            val n = inp.readInt()
            repeat(n) {
                val k = inp.readLong()
                val m = inp.readInt()
                map[k] = Array(m) { filters[inp.readInt()] }
            }
        }
        val n = inp.readInt()
        untokenized = ArrayList(n)
        repeat(n) { untokenized.add(filters[inp.readInt()]) }
        frozen = true
    }

    private fun suffixAt(host: String, index: Int): String {
        var start = 0
        repeat(index) { start = host.indexOf('.', start) + 1 }
        return host.substring(start)
    }

    private fun append(map: LongObjMap<Any>, key: Long, f: NetworkFilter) {
        @Suppress("UNCHECKED_CAST")
        val list = map.getOrPut(key) { ArrayList<NetworkFilter>(2) } as ArrayList<NetworkFilter>
        list.add(f)
    }

    private fun compact(map: LongObjMap<Any>) {
        map.replaceValues { v ->
            @Suppress("UNCHECKED_CAST")
            if (v is ArrayList<*>) (v as ArrayList<NetworkFilter>).toTypedArray() else v
        }
    }

    companion object {
        private const val SIMPLE_1P = 1 shl 20
        private const val SIMPLE_3P = 1 shl 21
        private const val PARTY_MASK = SIMPLE_1P or SIMPLE_3P

        val PURE_HOST = NetworkFilter("", NetworkFilter.HOST_ANCHOR, RequestType.ALL, null, null, 0, null, 0, -1)
        val SIMPLE_HOST = NetworkFilter("", NetworkFilter.HOST_ANCHOR, RequestType.ALL, null, null, 0, null, 0, -1)

        /** Length of a complete hostname prefix (followed by `^`, `/`, `:` or anchored end), else 0. */
        fun completeHostLength(p: String): Int {
            var i = 0
            var dots = 0
            while (i < p.length) {
                val c = p[i]
                if (c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_') {
                    i++
                } else if (c == '.') {
                    dots++; i++
                } else break
            }
            if (i == 0 || dots == 0 || p[0] == '.' || p[i - 1] == '.') return 0
            if (i == p.length) return 0 // `||example.com` alone also matches example.company
            val next = p[i]
            return if (next == '^' || next == '/' || next == ':') i else 0
        }
    }
}
