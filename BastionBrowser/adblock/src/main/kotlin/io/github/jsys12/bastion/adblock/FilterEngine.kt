package io.github.jsys12.bastion.adblock

import java.io.BufferedReader
import java.net.URLDecoder
import java.util.Base64

/** A filter list to compile. */
class FilterSource(
    val id: Int,
    /** Lists maintained by trusted parties may use trusted scriptlets, `#%#` JS and `url()` in CSS. */
    val trusted: Boolean,
    val open: () -> BufferedReader,
)

class MatchResult(
    val blocked: Boolean,
    /** The filter that decided (block or exception); null when nothing matched. */
    val filter: NetworkFilter?,
    /** Redirect resource name when a neutered stand-in should be served. */
    val redirect: String?,
    /** Blocklist hostname when a hostname set matched. */
    val matchedHost: String?,
) {
    val listId get() = filter?.listId ?: -1

    fun describe(): String = when {
        matchedHost != null -> "||$matchedHost^"
        filter != null -> filter.describe()
        else -> ""
    }

    companion object {
        val NONE = MatchResult(false, null, null, null)
    }
}

class EngineBuilder {
    internal val blocks = NetworkIndex()
    internal val exceptions = NetworkIndex()
    internal val important = NetworkIndex()
    internal val importantExceptions = NetworkIndex()
    internal val redirects = NetworkIndex()
    internal val removeParams = NetworkIndex()
    internal val removeParamExceptions = NetworkIndex()
    internal val urlSkips = NetworkIndex()
    internal val pageExceptions = NetworkIndex()
    internal val cosmetic = CosmeticIndex()
    internal val badFilters = HashSet<String>()
    val listCounts = HashMap<Int, Int>()

    private val parser = FilterParser(this)

    internal fun addPureHost(host: String, listId: Int) {
        blocks.addPureHost(host)
        countList(listId)
    }

    internal fun countList(listId: Int) {
        listCounts[listId] = (listCounts[listId] ?: 0) + 1
    }

    /** First pass: `$badfilter` filters disable their counterparts across all lists. */
    fun collectBadFilters(lines: Sequence<String>) {
        for (raw in lines) {
            if (!raw.contains("badfilter")) continue
            val line = raw.trim()
            val d = line.lastIndexOf('$')
            if (d < 0) continue
            val opts = line.substring(d + 1).split(',')
            if (opts.none { it == "badfilter" }) continue
            val rest = opts.filter { it != "badfilter" }
            badFilters.add(if (rest.isEmpty()) line.substring(0, d) else line.substring(0, d + 1) + rest.joinToString(","))
        }
    }

    fun addList(id: Int, trusted: Boolean, lines: Sequence<String>) {
        parser.resetList(id, trusted)
        for (line in lines) {
            try {
                parser.parseLine(line)
            } catch (e: Exception) {
                // A malformed line never breaks the whole list.
            }
        }
    }

    fun build(): FilterEngine {
        for (h in badFilters) {
            if (h.startsWith("||") && h.endsWith("^")) blocks.removePureHost(h.substring(2, h.length - 1))
        }
        listOf(blocks, exceptions, important, importantExceptions, redirects, removeParams,
            removeParamExceptions, urlSkips, pageExceptions).forEach { it.freeze() }
        cosmetic.freeze()
        return FilterEngine(this)
    }

    companion object {
        fun compile(sources: List<FilterSource>): FilterEngine {
            val b = EngineBuilder()
            for (s in sources) s.open().use { r -> b.collectBadFilters(r.lineSequence()) }
            for (s in sources) s.open().use { r -> b.addList(s.id, s.trusted, r.lineSequence()) }
            return b.build()
        }
    }
}

class FilterEngine internal constructor(b: EngineBuilder) {
    internal fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(listCounts.size)
        for ((k, v) in listCounts) {
            out.writeInt(k); out.writeInt(v)
        }
        val indexes = arrayOf(blocks, exceptions, important, importantExceptions, redirects, removeParams,
            removeParamExceptions, urlSkips, pageExceptions)
        val table = Snapshot.FilterTable()
        for (idx in indexes) idx.collectFilters(table)
        out.writeInt(table.list.size)
        for (f in table.list) Snapshot.writeFilter(out, f)
        for (idx in indexes) idx.writeTo(out, table)
        cosmetic.writeTo(out)
    }

    private val blocks = b.blocks
    private val exceptions = b.exceptions
    private val important = b.important
    private val importantExceptions = b.importantExceptions
    private val redirects = b.redirects
    private val removeParams = b.removeParams
    private val removeParamExceptions = b.removeParamExceptions
    private val urlSkips = b.urlSkips
    private val pageExceptions = b.pageExceptions
    val cosmetic = b.cosmetic
    val listCounts: Map<Int, Int> = HashMap(b.listCounts)

    companion object {
        internal fun readFrom(inp: java.io.DataInputStream): FilterEngine {
            val b = EngineBuilder()
            val lc = inp.readInt()
            repeat(lc) { b.listCounts[inp.readInt()] = inp.readInt() }
            val n = inp.readInt()
            val filters = Array(n) { Snapshot.readFilter(inp) }
            for (idx in arrayOf(b.blocks, b.exceptions, b.important, b.importantExceptions, b.redirects,
                b.removeParams, b.removeParamExceptions, b.urlSkips, b.pageExceptions)) idx.readFrom(inp, filters)
            b.cosmetic.readFrom(inp)
            return FilterEngine(b)
        }
    }

    val networkFilterCount = blocks.filterCount + exceptions.filterCount + important.filterCount +
        redirects.filterCount + removeParams.filterCount + urlSkips.filterCount + pageExceptions.filterCount
    val cosmeticFilterCount get() = cosmetic.ruleCount

    private val ctxLocal = ThreadLocal.withInitial { RequestContext() }

    fun debugStats(): String = buildString {
        appendLine("blocks: " + blocks.debugStats())
        appendLine("exceptions: " + exceptions.debugStats())
        appendLine("important: " + important.debugStats())
        appendLine("redirects: " + redirects.debugStats())
        appendLine("removeparams: " + removeParams.debugStats())
        appendLine("pageExceptions: " + pageExceptions.debugStats())
        appendLine("largest block buckets: " + blocks.largestBuckets(8).joinToString("\n   "))
        appendLine("largest exception buckets: " + exceptions.largestBuckets(5).joinToString("\n   "))
        appendLine("untokenized blocks: " + blocks.untokenizedSample(15).joinToString("  "))
        appendLine("untokenized exceptions: " + exceptions.untokenizedSample(15).joinToString("  "))
    }

    /**
     * Decides a sub-resource or document request.
     * @param page the top-level document's host; [pageFlags] from [pageFlags] for that document.
     */
    fun match(url: String, page: HostInfo, type: Int, method: String? = null, pageFlags: Int = 0): MatchResult {
        if (pageFlags and (PageFlags.ALLOW_ALL or PageFlags.URLBLOCK) != 0) return MatchResult.NONE
        val ctx = ctxLocal.get()!!
        if (!ctx.reset(url, page, type, method)) return MatchResult.NONE
        val genericAllowed = pageFlags and PageFlags.GENERICBLOCK == 0

        val imp = important.match(ctx, genericAllowed)
        if (imp != null) {
            val ex = importantExceptions.match(ctx)
            if (ex != null) return MatchResult(false, ex, null, null)
            return MatchResult(true, imp, redirectFor(ctx, imp), ctx.matchedHost)
        }
        val block = blocks.match(ctx, genericAllowed) ?: return MatchResult.NONE
        val matchedHost = ctx.matchedHost
        val ex = exceptions.match(ctx) ?: importantExceptions.match(ctx)
        if (ex != null) return MatchResult(false, ex, null, null)
        if (type == RequestType.DOCUMENT || type and RequestType.POPUP != 0) {
            // A document exception for the target itself (e.g. @@||site^$document) also wins.
            if (pageFlagsFor(ctx) and PageFlags.ALLOW_ALL != 0) return MatchResult(false, null, null, null)
            // So does an exception without type restrictions (typically an "unbreak" fix for a domain list).
            ctx.type = RequestType.OTHER
            val general = exceptions.match(ctx)
            ctx.type = type
            if (general != null && general.typeMask and RequestType.ALL_REQUESTS == RequestType.ALL_REQUESTS) {
                return MatchResult(false, general, null, null)
            }
        }
        return MatchResult(true, block, redirectFor(ctx, block), matchedHost)
    }

    private fun redirectFor(ctx: RequestContext, block: NetworkFilter): String? {
        if (block.flags and NetworkFilter.REDIRECT != 0) return block.option
        val r = redirects.match(ctx) ?: return null
        return r.option
    }

    /** Page-level exception flags (`$document`, `$elemhide`, `$generichide`, ...) for a document URL. */
    fun pageFlags(url: String): Int {
        val ctx = ctxLocal.get()!!
        if (!ctx.reset(url, HostInfo(""), RequestType.DOCUMENT, null)) return 0
        return pageFlagsFor(ctx)
    }

    private fun pageFlagsFor(ctx: RequestContext): Int {
        val list = ArrayList<NetworkFilter>(2)
        pageExceptions.matchAll(ctx, list)
        var flags = 0
        for (f in list) flags = flags or f.pageFlags
        return flags
    }

    /** Should a popup to [url] opened by [opener] be blocked? */
    fun matchPopup(url: String, opener: HostInfo, openerFlags: Int): MatchResult {
        if (openerFlags and (PageFlags.ALLOW_ALL or PageFlags.URLBLOCK) != 0) return MatchResult.NONE
        return match(url, opener, RequestType.POPUP or RequestType.DOCUMENT, "GET", openerFlags)
    }

    /**
     * Removes tracking parameters from a document URL using `$removeparam` filters.
     * Returns the cleaned URL, or null when nothing changed.
     */
    fun removeParams(url: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val ctx = ctxLocal.get()!!
        if (!ctx.reset(url, HostInfo(""), RequestType.DOCUMENT, "GET")) return null
        val matched = ArrayList<NetworkFilter>()
        removeParams.matchAll(ctx, matched)
        if (matched.isEmpty()) return null
        val excepted = ArrayList<NetworkFilter>()
        removeParamExceptions.matchAll(ctx, excepted)
        if (excepted.any { it.option == null }) return null
        val exceptedValues = excepted.mapNotNull { it.option }.toSet()
        val active = matched.filter { it.option == null || it.option !in exceptedValues }
        if (active.isEmpty()) return null
        return ParamCleaner.apply(url, active.map { it.option })
    }

    /** `$urlskip`: the destination embedded in a tracking redirect URL, or null. */
    fun urlSkip(url: String): String? {
        val ctx = ctxLocal.get()!!
        if (!ctx.reset(url, HostInfo(""), RequestType.DOCUMENT, "GET")) return null
        val f = urlSkips.match(ctx) ?: return null
        return UrlSkip.apply(url, f.option ?: return null)
    }

    fun cosmetics(frameUrl: String, pageFlags: Int, allowScripts: Boolean): PageCosmetics {
        val info = HostInfo.of(frameUrl)
        return cosmetic.forPage(info, pageFlags, allowScripts)
    }
}

object ParamCleaner {
    /** Built-in tracking parameters stripped even without list rules. */
    val DEFAULT = listOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id", "utm_name",
        "utm_cid", "utm_reader", "utm_referrer", "utm_social", "utm_social-type", "utm_brand", "utm_place",
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "ysclid", "twclid",
        "ttclid", "li_fat_id", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "igshid", "igsh", "mkt_tok", "oly_anon_id",
        "oly_enc_id", "vero_id", "vero_conv", "__s", "_openstat", "srsltid", "si", "epik", "s_cid", "rb_clickid",
        "sms_click", "sms_source", "sms_uph", "wickedid", "gad_source", "_ga", "_gl", "ncid", "at_medium",
        "at_campaign", "at_custom1", "at_custom2", "at_custom3", "at_custom4", "stm_source", "stm_medium",
        "stm_campaign", "zanpid", "dm_i", "ref_src", "ref_url", "__twitter_impression", "fb_action_ids",
        "fb_action_types", "fb_ref", "fb_source", "action_object_map", "action_type_map", "action_ref_map",
    ).map { it }

    fun apply(url: String, values: List<String?>): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val hashIdx = url.indexOf('#', q)
        val query = if (hashIdx >= 0) url.substring(q + 1, hashIdx) else url.substring(q + 1)
        val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
        val params = query.split('&').filter { it.isNotEmpty() }
        if (params.isEmpty()) return null
        val kept = params.filter { p ->
            val name = p.substringBefore('=')
            values.none { v -> shouldRemove(v, name, p) }
        }
        if (kept.size == params.size) return null
        val base = url.substring(0, q)
        return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
    }

    private val regexCache = HashMap<String, Regex?>()

    private fun shouldRemove(spec: String?, name: String, pair: String): Boolean {
        if (spec == null || spec.isEmpty()) return true
        if (spec.startsWith("~")) {
            val inner = spec.substring(1)
            return !matchesSpec(inner, name, pair)
        }
        return matchesSpec(spec, name, pair)
    }

    private fun matchesSpec(spec: String, name: String, pair: String): Boolean {
        if (spec.length > 2 && spec.startsWith("/")) {
            val re = synchronized(regexCache) {
                regexCache.getOrPut(spec) {
                    val end = spec.lastIndexOf('/')
                    if (end <= 0) null else try {
                        val flags = spec.substring(end + 1)
                        if (flags.contains('i')) Regex(spec.substring(1, end), RegexOption.IGNORE_CASE)
                        else Regex(spec.substring(1, end))
                    } catch (e: Exception) { null }
                }
            } ?: return false
            return re.containsMatchIn(decode(pair))
        }
        return name == spec
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** Strips [DEFAULT] tracking parameters; null when nothing changed. */
    fun stripDefault(url: String): String? {
        if (url.indexOf('?') < 0) return null
        return apply(url, DEFAULT)
    }
}

object UrlSkip {
    /** Applies a uBO `urlskip=` spec such as `?url -base64 +https`. */
    fun apply(url: String, spec: String): String? {
        var current = url
        for (step in spec.trim().split(' ').filter { it.isNotEmpty() }) {
            current = when {
                step.startsWith("?") -> queryParam(current, step.substring(1)) ?: return null
                step == "-base64" -> try {
                    String(Base64.getDecoder().decode(current.trim().replace('-', '+').replace('_', '/').let { padB64(it) }))
                } catch (e: Exception) { return null }
                step == "-uricomponent" -> URLDecoder.decode(current, "UTF-8")
                step == "+https" -> if (current.contains("://")) current else "https://$current"
                step == "-blocked" -> current
                step.startsWith("&i") -> current
                else -> return null
            }
        }
        if (!Urls.isHttp(current)) return null
        return current
    }

    private fun padB64(s: String): String = s + "=".repeat((4 - s.length % 4) % 4)

    fun queryParam(url: String, name: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val end = url.indexOf('#', q).let { if (it < 0) url.length else it }
        for (p in url.substring(q + 1, end).split('&')) {
            val eq = p.indexOf('=')
            if (eq < 0) continue
            if (p.substring(0, eq) == name) {
                return try { URLDecoder.decode(p.substring(eq + 1), "UTF-8") } catch (e: Exception) { null }
            }
        }
        return null
    }
}
