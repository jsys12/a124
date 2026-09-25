package io.github.jsys12.bastion.adblock

/**
 * Parses ABP / uBlock Origin / AdGuard filter syntax into an [EngineBuilder].
 * Unsupported constructs are skipped rather than approximated when approximating could over-block.
 */
internal class FilterParser(private val b: EngineBuilder) {

    var listId = 0
    var trusted = false

    // !#if preprocessor state
    private val condStack = ArrayList<Boolean>()
    private var active = true

    fun resetList(id: Int, trusted: Boolean) {
        listId = id
        this.trusted = trusted
        condStack.clear()
        active = true
    }

    fun parseLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val c0 = line[0]
        if (c0 == '!') {
            if (line.startsWith("!#")) directive(line)
            return
        }
        if (!active) return
        if (c0 == '[' && line.startsWith("[Adblock", ignoreCase = true)) return
        if (c0 == '#' && (line.length == 1 || line[1] == ' ' || line[1] == '#' && line.length > 2 && line[2] == ' ')) return
        if (c0 == '[' && line.startsWith("[$")) return // AdGuard cosmetic modifiers: unsupported

        if (hostsLine(line)) return
        if (plainHostname(line)) return

        val sep = findCosmeticSeparator(line)
        if (sep != null) {
            parseCosmetic(line, sep)
            return
        }
        parseNetwork(line)
    }

    // ---------------------------------------------------------------- preprocessor

    private fun directive(line: String) {
        when {
            line.startsWith("!#if ") -> {
                val v = evalCondition(line.substring(5).trim())
                condStack.add(active)
                active = active && v
            }
            line.startsWith("!#else") -> {
                if (condStack.isNotEmpty()) {
                    val parent = condStack.last()
                    active = parent && !active
                }
            }
            line.startsWith("!#endif") -> {
                if (condStack.isNotEmpty()) active = condStack.removeAt(condStack.size - 1)
            }
        }
    }

    private fun evalCondition(expr: String): Boolean {
        val tokens = Regex("""\s*(&&|\|\||!|\(|\)|[A-Za-z0-9_]+)""").findAll(expr).map { it.groupValues[1] }.toList()
        var pos = 0
        fun or(): Boolean {
            fun unary(): Boolean {
                if (pos >= tokens.size) return false
                val t = tokens[pos++]
                return when (t) {
                    "!" -> !unary()
                    "(" -> { val v = or(); if (pos < tokens.size && tokens[pos] == ")") pos++; v }
                    else -> t in ENV_TRUE
                }
            }
            fun and(): Boolean {
                var v = unary()
                while (pos < tokens.size && tokens[pos] == "&&") { pos++; val r = unary(); v = v && r }
                return v
            }
            var v = and()
            while (pos < tokens.size && tokens[pos] == "||") { pos++; val r = and(); v = v || r }
            return v
        }
        return try { or() } catch (e: Exception) { false }
    }

    // ---------------------------------------------------------------- hostname lists

    private fun hostsLine(line: String): Boolean {
        val c = line[0]
        if (!(c == '0' || c == '1' || c == ':')) return false
        val prefixes = arrayOf("0.0.0.0 ", "0.0.0.0\t", "127.0.0.1 ", "127.0.0.1\t", ":: ", "::1 ", "::0 ")
        val p = prefixes.firstOrNull { line.startsWith(it) } ?: return false
        var rest = line.substring(p.length)
        val hash = rest.indexOf('#')
        if (hash >= 0) rest = rest.substring(0, hash)
        for (h in rest.trim().split(' ', '\t')) {
            val host = h.trim().lowercase()
            if (host.isEmpty() || host in HOSTS_IGNORE || !isHostname(host)) continue
            b.addPureHost(host, listId)
        }
        return true
    }

    private fun plainHostname(line: String): Boolean {
        if (!isHostname(line)) return false
        b.addPureHost(line.lowercase(), listId)
        return true
    }

    // ---------------------------------------------------------------- cosmetic

    private class Sep(val index: Int, val length: Int, val type: Int, val exception: Boolean)

    private fun findCosmeticSeparator(line: String): Sep? {
        var i = line.indexOf('#')
        while (i >= 0 && i + 1 < line.length) {
            val sep = matchSepAt(line, i)
            if (sep != null) {
                val domains = line.substring(0, i)
                return if (domains.all { it.isLetterOrDigit() || it in ".,~*-_:[]" || it.code > 127 }) sep else null
            }
            i = line.indexOf('#', i + 1)
        }
        return null
    }

    private fun matchSepAt(s: String, i: Int): Sep? {
        fun at(t: String) = s.startsWith(t, i)
        return when {
            at("##") -> Sep(i, 2, T_HIDE, false)
            at("#@#") -> Sep(i, 3, T_HIDE, true)
            at("#?#") -> Sep(i, 3, T_EXT, false)
            at("#@?#") -> Sep(i, 4, T_EXT, true)
            at("#$#") -> Sep(i, 3, T_CSS, false)
            at("#@$#") -> Sep(i, 4, T_CSS, true)
            at("#$?#") -> Sep(i, 4, T_CSS_EXT, false)
            at("#@$?#") -> Sep(i, 5, T_CSS_EXT, true)
            at("#%#") -> Sep(i, 3, T_JS, false)
            at("#@%#") -> Sep(i, 4, T_JS, true)
            else -> null
        }
    }

    private fun parseCosmetic(line: String, sep: Sep) {
        val domainPart = line.substring(0, sep.index)
        var body = line.substring(sep.index + sep.length).trim()
        if (body.isEmpty()) return

        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        if (domainPart.isNotEmpty() && domainPart != "*") {
            for (raw in domainPart.split(',')) {
                var d = raw.trim().lowercase()
                if (d.isEmpty()) continue
                val neg = d.startsWith("~")
                if (neg) d = d.substring(1)
                if (!DomainConstraint.isValidDomainEntry(d)) return
                if (neg) exclude.add(d) else include.add(d)
            }
        }

        var kind: Int
        var args: Array<String>? = null
        when (sep.type) {
            T_HIDE, T_EXT -> {
                if (body.startsWith("+js(")) {
                    if (!body.endsWith(")")) return
                    val parsed = parseUboScriptletArgs(body.substring(4, body.length - 1)) ?: return
                    if (!sep.exception && !scriptletAllowed(parsed, include)) return
                    kind = CosmeticRule.SCRIPTLET
                    args = parsed.toTypedArray()
                    body = if (parsed.isEmpty()) "" else parsed.joinToString("\u0001")
                } else if (body.startsWith("^")) {
                    // HTML filtering needs response rewriting; the common `^script:has-text(x)` form is
                    // equivalent to removing matching inline scripts before they run.
                    val m = HTML_SCRIPT_RE.matchEntire(body.substring(1)) ?: return
                    val parsed = listOf("remove-node-text", "script", m.groupValues[1])
                    if (!sep.exception && !scriptletAllowed(parsed, include)) return
                    kind = CosmeticRule.SCRIPTLET
                    args = parsed.toTypedArray()
                    body = parsed.joinToString("\u0001")
                } else {
                    val styled = splitStyle(body)
                    if (styled != null) {
                        val (sel, decl) = styled
                        if (!cssDeclarationAllowed(decl)) return
                        if (isProcedural(sel)) {
                            kind = CosmeticRule.PROCEDURAL
                        } else {
                            if (!selectorSafe(sel, hiding = false)) return
                            kind = CosmeticRule.CSS
                            body = "$sel{$decl}"
                        }
                    } else if (isProcedural(body)) {
                        kind = CosmeticRule.PROCEDURAL
                    } else {
                        if (!selectorSafe(body)) return
                        kind = CosmeticRule.HIDE
                    }
                }
            }
            T_CSS, T_CSS_EXT -> {
                // AdGuard: selector { declarations }
                val open = body.lastIndexOf('{')
                if (open <= 0 || !body.endsWith("}")) return
                val sel = body.substring(0, open).trim()
                var decl = body.substring(open + 1, body.length - 1).trim()
                if (sel.isEmpty() || decl.isEmpty()) return
                if (decl.replace(" ", "").startsWith("remove:true")) {
                    kind = CosmeticRule.PROCEDURAL
                    body = "$sel:remove()"
                } else {
                    if (!cssDeclarationAllowed(decl)) return
                    if (!decl.endsWith(";")) decl += ";"
                    if (isProcedural(sel) || sep.type == T_CSS_EXT && isProcedural(sel)) {
                        kind = CosmeticRule.PROCEDURAL
                        body = "$sel:style(${decl.trimEnd(';')})"
                    } else {
                        if (!selectorSafe(sel, hiding = false)) return
                        kind = CosmeticRule.CSS
                        body = "$sel{$decl}"
                    }
                }
            }
            T_JS -> {
                if (body.startsWith("//scriptlet(")) {
                    if (!body.endsWith(")")) return
                    val parsed = parseAdgScriptletArgs(body.substring(12, body.length - 1)) ?: return
                    if (!sep.exception && !scriptletAllowed(parsed, include)) return
                    kind = CosmeticRule.SCRIPTLET
                    args = parsed.toTypedArray()
                    body = if (parsed.isEmpty()) "" else parsed.joinToString("\u0001")
                } else {
                    if (!trusted || include.isEmpty()) return
                    kind = CosmeticRule.JS
                }
            }
            else -> return
        }

        b.countList(listId)
        if (sep.exception) {
            val rule = CosmeticRule(kind or CosmeticRule.EXCEPTION, body, args, null)
            if (include.isEmpty()) {
                if (kind == CosmeticRule.HIDE) b.cosmetic.addGlobalException(body)
                // Generic procedural/scriptlet exceptions are ignored.
            } else {
                for (d in include) b.cosmetic.addSpecific(d, rule)
            }
            return
        }

        if (include.isEmpty()) {
            // Generic rule (possibly with exclusions).
            when (kind) {
                CosmeticRule.HIDE -> b.cosmetic.addGenericHide(body)
                CosmeticRule.PROCEDURAL -> if (trusted) b.cosmetic.addGenericProcedural(body) else return
                CosmeticRule.CSS -> b.cosmetic.addGenericCss(body)
                else -> return // generic scriptlets / JS are not allowed
            }
            if (exclude.isNotEmpty()) {
                val exc = CosmeticRule(kind or CosmeticRule.EXCEPTION, body, null, null)
                for (d in exclude) b.cosmetic.addSpecific(d, exc)
            }
            return
        }
        val rule = CosmeticRule(kind, body, args, if (exclude.isEmpty()) null else exclude.toTypedArray())
        for (d in include) b.cosmetic.addSpecific(d, rule)
    }

    private fun scriptletAllowed(args: List<String>, include: List<String>): Boolean {
        if (args.isEmpty()) return false
        if (include.isEmpty()) return false
        val name = args[0]
        if (name.startsWith("trusted-") || name in TRUSTED_ONLY) return trusted
        return true
    }

    /** `sel:style(decl)` -> (sel, decl) */
    private fun splitStyle(body: String): Pair<String, String>? {
        if (!body.endsWith(")")) return null
        val idx = body.lastIndexOf(":style(")
        if (idx <= 0) return null
        return body.substring(0, idx) to body.substring(idx + 7, body.length - 1).trim()
    }

    private fun cssDeclarationAllowed(decl: String): Boolean {
        val d = decl.lowercase()
        if (d.contains('{') || d.contains('}') || d.contains("/*")) return false
        if (!trusted && (d.contains("url(") || d.contains("image-set(") || d.contains("@import") || d.contains("expression("))) return false
        return true
    }

    private fun selectorSafe(sel: String, hiding: Boolean = true): Boolean {
        if (sel.isEmpty() || sel.length > 4096) return false
        if (sel.contains('{') || sel.contains('}') || sel.contains("/*")) return false
        if (!hiding) return true
        // ##body / ##html would blank the page.
        val low = sel.lowercase()
        if (low == "body" || low == "html" || low == "*" || low == ":root") return false
        return true
    }

    // ---------------------------------------------------------------- network

    fun parseNetwork(line0: String) {
        var line = line0
        if (b.badFilters.isNotEmpty() && line in b.badFilters) return
        var exception = false
        if (line.startsWith("@@")) {
            exception = true
            line = line.substring(2)
        }

        var pattern: String
        var optionsText: String? = null
        var isRegex = false
        if (line.length > 2 && line[0] == '/') {
            var end = -1
            var j = line.indexOf("/$", 1)
            while (j > 0) {
                if (looksLikeOptions(line, j + 2)) { end = j; break }
                j = line.indexOf("/$", j + 1)
            }
            if (end > 0) {
                pattern = line.substring(1, end)
                optionsText = line.substring(end + 2)
                isRegex = true
            } else if (line.endsWith("/")) {
                pattern = line.substring(1, line.length - 1)
                isRegex = true
            } else {
                pattern = line
                val d = optionsIndex(line)
                if (d >= 0) {
                    pattern = line.substring(0, d); optionsText = line.substring(d + 1)
                }
            }
            if (isRegex && pattern.none { it in REGEX_META }) {
                // "/ads/" is a path fragment, not a regex.
                isRegex = false
                pattern = "/$pattern/"
            }
        } else {
            pattern = line
            val d = optionsIndex(line)
            if (d >= 0) {
                pattern = line.substring(0, d)
                optionsText = line.substring(d + 1)
            }
        }

        // ---- options
        var typeMask = 0
        var negTypes = 0
        var flags = NetworkFilter.FIRST_PARTY or NetworkFilter.THIRD_PARTY
        var domains: DomainConstraint? = null
        var toDomains: DomainConstraint? = null
        var methods = 0
        var option: String? = null
        var pageFlags = 0
        var matchCase = false
        var hasNonTypeOption = false

        if (optionsText != null) {
            for (rawOpt in splitOptions(optionsText)) {
                var opt = rawOpt.trim()
                if (opt.isEmpty()) continue
                var neg = false
                if (opt.startsWith("~")) { neg = true; opt = opt.substring(1) }
                val eq = opt.indexOf('=')
                val name = (if (eq >= 0) opt.substring(0, eq) else opt).lowercase()
                val value = if (eq >= 0) opt.substring(eq + 1) else null

                val t = RequestType.fromOptionName(name)
                if (t != 0 && value == null) {
                    if (neg) negTypes = negTypes or t else typeMask = typeMask or t
                    continue
                }
                when (name) {
                    "third-party", "3p" -> {
                        flags = flags and (if (neg) NetworkFilter.THIRD_PARTY else NetworkFilter.FIRST_PARTY).inv()
                    }
                    "first-party", "1p" -> {
                        flags = flags and (if (neg) NetworkFilter.FIRST_PARTY else NetworkFilter.THIRD_PARTY).inv()
                    }
                    "strict1p", "strict-first-party" -> { flags = flags or NetworkFilter.STRICT_1P; hasNonTypeOption = true }
                    "strict3p", "strict-third-party" -> { flags = flags or NetworkFilter.STRICT_3P; hasNonTypeOption = true }
                    "domain", "from" -> {
                        domains = DomainConstraint.parse(value ?: return, '|') ?: return
                        hasNonTypeOption = true
                    }
                    "to" -> {
                        toDomains = DomainConstraint.parse(value ?: return, '|') ?: return
                        hasNonTypeOption = true
                    }
                    "denyallow" -> {
                        val v = value ?: return
                        toDomains = DomainConstraint.parse(v.split('|').joinToString("|") { "~$it" }, '|') ?: return
                        hasNonTypeOption = true
                    }
                    "method" -> {
                        var inc = 0
                        var exc = 0
                        for (m in (value ?: return).split('|')) {
                            if (m.startsWith("~")) exc = exc or Methods.bit(m.substring(1)) else inc = inc or Methods.bit(m)
                        }
                        methods = if (inc != 0) inc else Methods.ALL and exc.inv()
                        hasNonTypeOption = true
                    }
                    "all" -> typeMask = typeMask or RequestType.ALL
                    "popunder" -> typeMask = typeMask or RequestType.POPUP
                    "important" -> { flags = flags or NetworkFilter.IMPORTANT; hasNonTypeOption = true }
                    "match-case" -> { matchCase = true; hasNonTypeOption = true }
                    "badfilter" -> return
                    "redirect", "rewrite", "redirect-rule" -> {
                        var v = value ?: return
                        if (v.startsWith("abp-resource:")) v = v.substring(13)
                        val colon = v.lastIndexOf(':')
                        if (colon > 0 && v.substring(colon + 1).all { it.isDigit() || it == '-' }) v = v.substring(0, colon)
                        if (exception) return // redirect exceptions are not supported
                        option = Redirects.canonical(v) ?: return
                        flags = flags or if (name == "redirect-rule") NetworkFilter.REDIRECT_RULE else NetworkFilter.REDIRECT
                        hasNonTypeOption = true
                    }
                    "empty" -> {
                        option = "empty"; flags = flags or NetworkFilter.REDIRECT; hasNonTypeOption = true
                    }
                    "mp4" -> {
                        option = "noopmp4-1s"; flags = flags or NetworkFilter.REDIRECT; hasNonTypeOption = true
                        typeMask = typeMask or RequestType.MEDIA
                    }
                    "removeparam", "queryprune" -> {
                        option = value
                        flags = flags or NetworkFilter.REMOVEPARAM
                        hasNonTypeOption = true
                    }
                    "urlskip" -> {
                        if (exception) return
                        option = value ?: return
                        flags = flags or NetworkFilter.URLSKIP
                        hasNonTypeOption = true
                    }
                    "elemhide", "ehide" -> if (exception) pageFlags = pageFlags or PageFlags.ELEMHIDE else return
                    "generichide", "ghide" -> if (exception) pageFlags = pageFlags or PageFlags.GENERICHIDE else return
                    "specifichide", "shide" -> if (exception) pageFlags = pageFlags or PageFlags.SPECIFICHIDE else return
                    "genericblock" -> if (exception) pageFlags = pageFlags or PageFlags.GENERICBLOCK else return
                    "jsinject" -> if (exception) pageFlags = pageFlags or PageFlags.JSINJECT else return
                    "urlblock" -> if (exception) pageFlags = pageFlags or PageFlags.URLBLOCK else return
                    "content" -> if (!exception) return
                    "extension", "stealth" -> if (!exception) return // exception-only no-ops for us
                    "reason", "_" -> {}
                    else -> return // unsupported option: drop the whole filter
                }
            }
        }

        if (typeMask == 0) {
            // removeparam / urlskip are applied to navigations, so they cover documents by default.
            typeMask = if (flags and (NetworkFilter.REMOVEPARAM or NetworkFilter.URLSKIP) != 0) RequestType.ALL
            else RequestType.ALL_REQUESTS
        }
        if (negTypes != 0) typeMask = typeMask and negTypes.inv()
        if (typeMask == 0) return

        // ---- pattern
        if (!isRegex) {
            if (pattern.startsWith("||")) {
                flags = flags or NetworkFilter.HOST_ANCHOR
                pattern = pattern.substring(2)
            } else if (pattern.startsWith("|")) {
                flags = flags or NetworkFilter.LEFT_ANCHOR
                pattern = pattern.substring(1)
            }
            else if (isHostname(pattern) && pattern.substringAfterLast('.') !in FILE_EXTENSIONS) {
                // "tracker.example.com$image" is meant as a hostname; anchor it for exactness and speed.
                flags = flags or NetworkFilter.HOST_ANCHOR
                pattern += "^"
            }
            if (pattern.endsWith("|") && !pattern.endsWith("\\|")) {
                flags = flags or NetworkFilter.RIGHT_ANCHOR
                pattern = pattern.substring(0, pattern.length - 1)
            }
            if (flags and (NetworkFilter.HOST_ANCHOR or NetworkFilter.LEFT_ANCHOR) == 0) {
                pattern = pattern.trimStart('*')
            }
            if (flags and NetworkFilter.RIGHT_ANCHOR == 0) pattern = pattern.trimEnd('*')
            if (pattern.isEmpty() || pattern == "*" || pattern == "^") {
                pattern = ""
                flags = flags and (NetworkFilter.HOST_ANCHOR or NetworkFilter.LEFT_ANCHOR or NetworkFilter.RIGHT_ANCHOR).inv()
            }
            if (!matchCase) pattern = pattern.lowercase()
            // "|http" / "|https://" anchored scheme-only patterns match nearly everything; keep them.
        }
        if (matchCase) flags = flags or NetworkFilter.MATCH_CASE
        if (isRegex) flags = flags or NetworkFilter.REGEX
        if (exception) flags = flags or NetworkFilter.EXCEPTION
        if (domains == null || !domains.hasIncludes) flags = flags or NetworkFilter.GENERIC

        // A blanket filter without any constraint would block the whole web.
        if (!exception && pattern.isEmpty() && domains == null && toDomains == null &&
            flags and (NetworkFilter.REMOVEPARAM or NetworkFilter.REDIRECT_RULE) == 0 &&
            typeMask and RequestType.POPUP == 0
        ) return

        // ---- page-level exceptions
        if (exception && pageFlags != 0 || exception && typeMask and RequestType.DOCUMENT != 0 && typeMask == RequestType.DOCUMENT) {
            if (typeMask and RequestType.DOCUMENT != 0 && pageFlags == 0) pageFlags = PageFlags.ALLOW_ALL
            val f = makeFilter(pattern, flags, RequestType.ALL, domains, toDomains, methods, null, pageFlags) ?: return
            b.pageExceptions.add(f)
            return
        }
        if (exception && typeMask == RequestType.ALL) {
            // @@...$all : treat as a full document allow.
            makeFilter(pattern, flags, RequestType.ALL, domains, toDomains, methods, null, PageFlags.ALLOW_ALL)?.let { b.pageExceptions.add(it) }
        }

        // Plain hostname filters that block whole documents (uBO "strict blocking").
        val pureHostCandidate = !exception && !isRegex && flags and NetworkFilter.HOST_ANCHOR != 0 &&
            flags and NetworkFilter.RIGHT_ANCHOR == 0 && pattern.endsWith("^") &&
            NetworkIndex.completeHostLength(pattern) == pattern.length - 1
        if (pureHostCandidate && optionsText == null) {
            b.addPureHost(pattern.substring(0, pattern.length - 1), listId)
            return
        }
        if (pureHostCandidate && !hasNonTypeOption && typeMask and RequestType.POPUP == 0) {
            val host = pattern.substring(0, pattern.length - 1)
            if (b.blocks.addSimpleHost(host, typeMask, flags and NetworkFilter.FIRST_PARTY != 0, flags and NetworkFilter.THIRD_PARTY != 0)) {
                b.countList(listId)
                return
            }
        }

        val f = makeFilter(pattern, flags, typeMask, domains, toDomains, methods, option, 0) ?: return
        b.countList(listId)
        when {
            flags and NetworkFilter.REMOVEPARAM != 0 -> if (exception) b.removeParamExceptions.add(f) else b.removeParams.add(f)
            flags and NetworkFilter.URLSKIP != 0 -> b.urlSkips.add(f)
            flags and NetworkFilter.REDIRECT_RULE != 0 -> b.redirects.add(f)
            exception -> if (flags and NetworkFilter.IMPORTANT != 0) b.importantExceptions.add(f) else b.exceptions.add(f)
            else -> {
                if (flags and NetworkFilter.REDIRECT != 0) {
                    b.redirects.add(f)
                }
                if (flags and NetworkFilter.IMPORTANT != 0) b.important.add(f) else b.blocks.add(f)
            }
        }
    }

    private fun makeFilter(
        pattern: String, flags: Int, typeMask: Int, domains: DomainConstraint?, toDomains: DomainConstraint?,
        methods: Int, option: String?, pageFlags: Int,
    ): NetworkFilter? {
        val f = NetworkFilter(pattern, flags, typeMask, domains, toDomains, methods, option, pageFlags, listId)
        if (flags and NetworkFilter.REGEX != 0) {
            f.regex = try {
                if (flags and NetworkFilter.MATCH_CASE != 0) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return null
            }
        }
        return f
    }

    private fun optionsIndex(line: String): Int {
        var i = line.indexOf('$')
        while (i >= 0) {
            if (looksLikeOptions(line, i + 1)) return i
            i = line.indexOf('$', i + 1)
        }
        return -1
    }

    private fun looksLikeOptions(s: String, from: Int): Boolean {
        if (from >= s.length) return false
        var i = from
        if (s[i] == '~') i++
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
        if (i == start) return false
        if (i < s.length && s[i] != ',' && s[i] != '=') return false
        return s.substring(start, i).lowercase() in KNOWN_OPTIONS
    }

    private fun splitOptions(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == ',' -> { cur.append(','); i += 2; continue }
                c == ',' -> {
                    // A regex value (removeparam=/a,b/) may contain commas.
                    val eq = cur.indexOf("=")
                    if (eq >= 0 && eq + 1 < cur.length && cur[eq + 1] == '/' && !regexClosed(cur, eq + 1)) {
                        cur.append(c)
                    } else {
                        out.add(cur.toString()); cur.setLength(0)
                    }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun regexClosed(sb: CharSequence, start: Int): Boolean {
        var i = start + 1
        while (i < sb.length) {
            if (sb[i] == '\\') { i += 2; continue }
            if (sb[i] == '/') return true
            i++
        }
        return false
    }

    companion object {
        private const val T_HIDE = 1
        private const val T_EXT = 2
        private const val T_CSS = 3
        private const val T_CSS_EXT = 4
        private const val T_JS = 5

        private const val REGEX_META = "\\^$.*+?()[]{}|"

        val ENV_TRUE = setOf("ext_ublock", "env_chromium", "env_mobile", "cap_user_stylesheet")

        private val HTML_SCRIPT_RE = Regex("""script(?:\[[^\]]*\])*:has-text\((.+)\)""")

        private val FILE_EXTENSIONS = setOf(
            "js", "mjs", "css", "php", "html", "htm", "gif", "jpg", "jpeg", "png", "swf", "json", "xml", "txt",
            "aspx", "asp", "cgi", "jsp", "mp4", "mp3", "svg", "webp", "ico", "woff", "woff2", "ttf", "min",
            "ashx", "pl", "vtt", "m3u8", "gz", "zip", "exe", "apk", "wasm", "map",
        )

        private val HOSTS_IGNORE = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost",
            "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        )

        private val TRUSTED_ONLY = setOf("rpnt", "replace-node-text")

        private val KNOWN_OPTIONS = setOf(
            "third-party", "3p", "first-party", "1p", "strict1p", "strict3p", "strict-first-party",
            "strict-third-party", "domain", "from", "to", "denyallow", "method", "all", "script", "image",
            "stylesheet", "css", "object", "object-subrequest", "subdocument", "frame", "xmlhttprequest", "xhr",
            "fetch", "ping", "beacon", "media", "font", "websocket", "other", "webtransport", "document", "doc",
            "popup", "popunder", "important", "match-case", "badfilter", "redirect", "redirect-rule", "rewrite",
            "removeparam", "queryprune", "urlskip", "elemhide", "ehide", "generichide", "ghide", "specifichide",
            "shide", "genericblock", "content", "jsinject", "urlblock", "extension", "stealth", "cookie", "csp",
            "header", "permissions", "cname", "ipaddress", "inline-script", "inline-font", "empty", "mp4",
            "replace", "hls", "jsonprune", "network", "app", "reason", "uritransform", "_", "webrtc",
            "referrerpolicy", "specifichide", "responseheader",
        )

        fun isHostname(s: String): Boolean {
            if (s.length < 3 || s.length > 253) return false
            var dots = 0
            for (i in s.indices) {
                val c = s[i]
                when {
                    c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> {}
                    c in 'A'..'Z' -> {}
                    c == '.' -> { if (i == 0 || s[i - 1] == '.') return false; dots++ }
                    else -> return false
                }
            }
            return dots > 0 && s[0] != '-' && s.last() != '.' && s.last() != '-'
        }

        fun isProcedural(sel: String): Boolean = PROCEDURAL_RE.containsMatchIn(sel)

        private val PROCEDURAL_RE = Regex(
            ":(has-text|-abp-has|-abp-contains|contains|upward|xpath|matches-css|matches-css-before|" +
                "matches-css-after|min-text-length|watch-attr|matches-path|matches-attr|matches-prop|others|" +
                "nth-ancestor|remove|remove-attr|remove-class|style|if|if-not|matches-media|shadow)\\("
        )

        /** uBO `+js(name, arg, ...)` arguments; commas may be escaped or arguments quoted. */
        fun parseUboScriptletArgs(inner: String): List<String>? {
            val out = ArrayList<String>()
            if (inner.isBlank()) return out
            var i = 0
            val n = inner.length
            while (i <= n) {
                while (i < n && inner[i] == ' ') i++
                val sb = StringBuilder()
                if (i < n && (inner[i] == '\'' || inner[i] == '"' || inner[i] == '`')) {
                    val q = inner[i]
                    var j = i + 1
                    var closed = -1
                    while (j < n) {
                        if (inner[j] == '\\' && j + 1 < n && inner[j + 1] == q) { sb.append(q); j += 2; continue }
                        if (inner[j] == q) { closed = j; break }
                        sb.append(inner[j]); j++
                    }
                    // Only treat as quoted if the closing quote ends the argument.
                    var k = closed + 1
                    while (closed >= 0 && k < n && inner[k] == ' ') k++
                    if (closed >= 0 && (k >= n || inner[k] == ',')) {
                        out.add(sb.toString())
                        i = k + 1
                        if (k >= n) break
                        continue
                    }
                    sb.setLength(0)
                }
                var j = i
                while (j < n) {
                    val c = inner[j]
                    if (c == '\\' && j + 1 < n && inner[j + 1] == ',') { sb.append(','); j += 2; continue }
                    if (c == ',') break
                    sb.append(c); j++
                }
                out.add(sb.toString().trim())
                if (j >= n) break
                i = j + 1
                if (i == n) { out.add(""); break }
            }
            if (out.isEmpty()) return null
            out[0] = normalizeScriptletName(out[0])
            return out
        }

        /** AdGuard `//scriptlet('name', 'arg')` arguments. */
        fun parseAdgScriptletArgs(inner: String): List<String>? {
            val out = ArrayList<String>()
            var i = 0
            val n = inner.length
            while (i < n) {
                while (i < n && (inner[i] == ' ' || inner[i] == ',')) i++
                if (i >= n) break
                val q = inner[i]
                if (q != '\'' && q != '"') return null
                val sb = StringBuilder()
                var j = i + 1
                while (j < n && inner[j] != q) {
                    if (inner[j] == '\\' && j + 1 < n) { sb.append(inner[j + 1]); j += 2; continue }
                    sb.append(inner[j]); j++
                }
                if (j >= n) return null
                out.add(sb.toString())
                i = j + 1
            }
            if (out.isEmpty()) return out
            out[0] = normalizeScriptletName(out[0])
            return out
        }

        fun normalizeScriptletName(name: String): String {
            var s = name.trim()
            if (s.startsWith("ubo-")) s = s.substring(4)
            if (s.endsWith(".js")) s = s.substring(0, s.length - 3)
            return s
        }
    }
}
