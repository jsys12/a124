package io.github.jsys12.bastion.adblock

class CosmeticRule(
    @JvmField val kind: Int,
    /** Selector, CSS rule, raw JS, or the scriptlet key (name + args joined by \u0001). */
    @JvmField val body: String,
    /** Scriptlet name and arguments (only for [SCRIPTLET]). */
    @JvmField val args: Array<String>?,
    /** Hostnames/entities negated in the rule's own domain list. */
    @JvmField val excluded: Array<String>?,
) {
    companion object {
        const val HIDE = 1
        const val PROCEDURAL = 2
        const val CSS = 3
        const val SCRIPTLET = 4
        const val JS = 5
        const val EXCEPTION = 16
    }
}

/** What a page (frame) needs injected. */
class PageCosmetics(
    val hide: List<String>,
    val css: List<String>,
    val procedural: List<String>,
    val scriptlets: List<Array<String>>,
    val js: List<String>,
    /** Hide exceptions that also cancel matching generic selectors on this page. */
    val exceptions: Set<String>,
    val generic: Boolean,
)

class CosmeticIndex {
    private val specific = HashMap<String, ArrayList<CosmeticRule>>(16384)
    private val genericKeyed = HashMap<String, ArrayList<String>>(16384)
    private val genericUnkeyed = LinkedHashSet<String>()
    private val genericProcedural = ArrayList<String>()
    private val genericCssRules = ArrayList<String>()
    private val globalExceptions = HashSet<String>()

    @Volatile
    private var unkeyedCssCache: String? = null

    var ruleCount = 0
        private set

    fun addSpecific(domain: String, rule: CosmeticRule) {
        specific.getOrPut(domain) { ArrayList(2) }.add(rule)
        ruleCount++
    }

    fun addGenericHide(selector: String) {
        ruleCount++
        val key = keyOf(selector)
        if (key == null) genericUnkeyed.add(selector)
        else genericKeyed.getOrPut(key) { ArrayList(1) }.add(selector)
    }

    fun addGenericProcedural(selector: String) {
        ruleCount++; genericProcedural.add(selector)
    }

    fun addGenericCss(css: String) {
        ruleCount++; genericCssRules.add(css)
    }

    fun addGlobalException(selector: String) {
        globalExceptions.add(selector)
    }

    /** Applies `#@#selector` (no domain) exceptions. Call once after all rules are added. */
    fun freeze() {
        if (globalExceptions.isNotEmpty()) {
            genericUnkeyed.removeAll(globalExceptions)
            val it = genericKeyed.values.iterator()
            while (it.hasNext()) {
                val list = it.next()
                list.removeAll(globalExceptions)
                if (list.isEmpty()) it.remove()
            }
            genericProcedural.removeAll(globalExceptions)
        }
        for (list in genericKeyed.values) list.trimToSize()
        for (list in specific.values) list.trimToSize()
    }

    internal fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(ruleCount)
        out.writeInt(specific.size)
        for ((domain, rules) in specific) {
            Snapshot.writeString(out, domain)
            out.writeInt(rules.size)
            for (r in rules) {
                out.writeInt(r.kind)
                Snapshot.writeString(out, r.body)
                out.writeInt(r.args?.size ?: -1)
                r.args?.forEach { Snapshot.writeString(out, it) }
                out.writeInt(r.excluded?.size ?: -1)
                r.excluded?.forEach { Snapshot.writeString(out, it) }
            }
        }
        out.writeInt(genericKeyed.size)
        for ((k, list) in genericKeyed) {
            Snapshot.writeString(out, k)
            Snapshot.writeStrings(out, list)
        }
        Snapshot.writeStrings(out, genericUnkeyed)
        Snapshot.writeStrings(out, genericProcedural)
        Snapshot.writeStrings(out, genericCssRules)
    }

    internal fun readFrom(inp: java.io.DataInputStream) {
        ruleCount = inp.readInt()
        val n = inp.readInt()
        // Rules shared between domains were written once per domain; intern bodies to save memory.
        val intern = HashMap<String, String>()
        repeat(n) {
            val domain = Snapshot.readString(inp)!!
            val m = inp.readInt()
            val list = ArrayList<CosmeticRule>(m)
            repeat(m) {
                val kind = inp.readInt()
                val body = Snapshot.readString(inp)!!.let { b -> intern.getOrPut(b) { b } }
                val argc = inp.readInt()
                val args = if (argc < 0) null else Array(argc) { Snapshot.readString(inp)!! }
                val exc = inp.readInt()
                val excluded = if (exc < 0) null else Array(exc) { Snapshot.readString(inp)!! }
                list.add(CosmeticRule(kind, body, args, excluded))
            }
            specific[domain] = list
        }
        val g = inp.readInt()
        repeat(g) {
            val k = Snapshot.readString(inp)!!
            genericKeyed[k] = Snapshot.readStrings(inp)
        }
        genericUnkeyed.addAll(Snapshot.readStrings(inp))
        genericProcedural.addAll(Snapshot.readStrings(inp))
        genericCssRules.addAll(Snapshot.readStrings(inp))
    }

    fun genericKeyCount() = genericKeyed.size
    fun genericUnkeyedCount() = genericUnkeyed.size

    fun forPage(info: HostInfo, pageFlags: Int, allowJs: Boolean): PageCosmetics {
        val elemhide = pageFlags and (PageFlags.ELEMHIDE or PageFlags.ALLOW_ALL) != 0
        val generic = !elemhide && pageFlags and PageFlags.GENERICHIDE == 0
        val specificAllowed = !elemhide && pageFlags and PageFlags.SPECIFICHIDE == 0
        val scriptsAllowed = allowJs && pageFlags and (PageFlags.JSINJECT or PageFlags.ALLOW_ALL) == 0

        val rules = ArrayList<CosmeticRule>()
        val host = info.host
        if (host.isNotEmpty()) {
            var start = 0
            while (true) {
                specific[host.substring(start)]?.let { addApplicable(it, info, rules) }
                if (info.suffixStart > start) {
                    specific[host.substring(start, info.suffixStart) + "*"]?.let { addApplicable(it, info, rules) }
                }
                val dot = host.indexOf('.', start)
                if (dot < 0) break
                start = dot + 1
            }
        }

        val exceptions = HashSet<String>()
        val scriptletExceptions = HashSet<String>()
        var allScriptletsDisabled = false
        val procExceptions = HashSet<String>()
        val cssExceptions = HashSet<String>()
        val jsExceptions = HashSet<String>()
        for (r in rules) {
            if (r.kind and CosmeticRule.EXCEPTION == 0) continue
            when (r.kind and CosmeticRule.EXCEPTION.inv()) {
                CosmeticRule.HIDE -> exceptions.add(r.body)
                CosmeticRule.PROCEDURAL -> procExceptions.add(r.body)
                CosmeticRule.CSS -> cssExceptions.add(r.body)
                CosmeticRule.JS -> jsExceptions.add(r.body)
                CosmeticRule.SCRIPTLET -> if (r.body.isEmpty()) allScriptletsDisabled = true else scriptletExceptions.add(r.body)
            }
        }

        val hide = ArrayList<String>()
        val css = ArrayList<String>()
        val procedural = ArrayList<String>()
        val scriptlets = ArrayList<Array<String>>()
        val js = ArrayList<String>()
        val seen = HashSet<String>()
        for (r in rules) {
            if (r.kind and CosmeticRule.EXCEPTION != 0) continue
            if (!seen.add(r.kind.toString() + r.body)) continue
            when (r.kind) {
                CosmeticRule.HIDE -> if (specificAllowed && r.body !in exceptions) hide.add(r.body)
                CosmeticRule.PROCEDURAL -> if (specificAllowed && r.body !in procExceptions) procedural.add(r.body)
                CosmeticRule.CSS -> if (specificAllowed && r.body !in cssExceptions) css.add(r.body)
                CosmeticRule.SCRIPTLET -> if (scriptsAllowed && !allScriptletsDisabled && r.body !in scriptletExceptions) scriptlets.add(r.args!!)
                CosmeticRule.JS -> if (scriptsAllowed && r.body !in jsExceptions) js.add(r.body)
            }
        }
        if (generic) {
            for (p in genericProcedural) if (p !in procExceptions) procedural.add(p)
            for (c in genericCssRules) if (c !in cssExceptions) css.add(c)
        }
        return PageCosmetics(hide, css, procedural, scriptlets, js, exceptions, generic)
    }

    private fun addApplicable(list: List<CosmeticRule>, info: HostInfo, out: MutableList<CosmeticRule>) {
        for (r in list) {
            val ex = r.excluded
            if (ex != null && isExcluded(ex, info)) continue
            out.add(r)
        }
    }

    private fun isExcluded(excluded: Array<String>, info: HostInfo): Boolean {
        val host = info.host
        for (e in excluded) {
            if (e.endsWith("*")) {
                // entity: compare against host minus public suffix
                if (info.suffixStart <= 0) continue
                val stem = e.substring(0, e.length - 1) // "google."
                val hostStem = host.substring(0, info.suffixStart)
                if (hostStem == stem || hostStem.endsWith(".$stem")) return true
            } else if (host == e || host.endsWith(".$e")) return true
        }
        return false
    }

    /** CSS for generic selectors that can't be keyed by class/id, minus [exceptions]. */
    fun unkeyedCss(exceptions: Set<String>): String {
        if (exceptions.isEmpty()) {
            unkeyedCssCache?.let { return it }
            val css = buildHideCss(genericUnkeyed)
            unkeyedCssCache = css
            return css
        }
        return buildHideCss(genericUnkeyed.filter { it !in exceptions })
    }

    /** Generic selectors keyed by the given ".class" / "#id" keys. */
    fun selectorsForKeys(keys: Collection<String>, exceptions: Set<String>): List<String> {
        val out = ArrayList<String>()
        for (k in keys) {
            val list = genericKeyed[k] ?: continue
            for (s in list) if (s !in exceptions) out.add(s)
        }
        return out
    }

    companion object {
        fun buildHideCss(selectors: Collection<String>): String {
            val sb = StringBuilder(selectors.size * 40)
            // One rule per selector: an invalid selector then only drops itself.
            for (s in selectors) sb.append(s).append("{display:none!important}\n")
            return sb.toString()
        }

        /**
         * ".class" or "#id" from the leftmost compound selector, when the selector can only match if
         * an element with that class/id exists in the document; null otherwise.
         */
        fun keyOf(selector: String): String? {
            if (selector.indexOf(',') >= 0) return null
            var i = 0
            val n = selector.length
            // optional type selector
            while (i < n && (selector[i].isLetterOrDigit() || selector[i] == '-' || selector[i] == '_')) i++
            if (i < n && selector[i] == '*' && i == 0) i++
            if (i >= n) return null
            val c = selector[i]
            if (c != '.' && c != '#') return null
            val start = i + 1
            var j = start
            while (j < n) {
                val ch = selector[j]
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch.code > 127) j++ else break
            }
            if (j == start) return null
            if (j < n) {
                val next = selector[j]
                if (next == '\\' || next == '(') return null
            }
            if (selector[start] in '0'..'9') return null
            return c + selector.substring(start, j)
        }
    }
}
