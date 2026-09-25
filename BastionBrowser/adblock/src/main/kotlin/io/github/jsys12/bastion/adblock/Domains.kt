package io.github.jsys12.bastion.adblock

import okhttp3.HttpUrl

object Urls {
    /** Host of an http(s)/ws(s) URL, lowercased, without port or credentials; null for other schemes. */
    fun host(url: String): String? {
        val range = hostRange(url)
        if (range < 0) return null
        val start = (range ushr 32).toInt()
        val end = range.toInt()
        if (end <= start) return null
        return url.substring(start, end).lowercase()
    }

    /** Packs [start, end) of the host into a long, or returns -1 when the URL has no host. */
    fun hostRange(url: String): Long {
        val sep = url.indexOf("://")
        if (sep <= 0 || sep > 10) return -1
        var start = sep + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i; break
            }
        }
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        if (start < end && url[start] == '[') {
            val close = url.indexOf(']', start)
            if (close in start until end) return (start.toLong() shl 32) or (close + 1).toLong()
        }
        val colon = url.indexOf(':', start)
        if (colon in start until end) end = colon
        return (start.toLong() shl 32) or end.toLong()
    }

    fun isHttp(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
}

object PublicSuffix {
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8192
    }

    /** eTLD+1 of [host] ("news.bbc.co.uk" -> "bbc.co.uk"); the host itself for IPs and unknown suffixes. */
    fun registrable(host: String): String {
        synchronized(cache) { cache[host]?.let { return it } }
        val r = compute(host)
        synchronized(cache) { cache[host] = r }
        return r
    }

    private fun compute(host: String): String {
        if (host.isEmpty() || isIp(host)) return host
        return try {
            HttpUrl.Builder().scheme("http").host(host).build().topPrivateDomain() ?: host
        } catch (e: Exception) {
            host
        }
    }

    fun isIp(host: String): Boolean {
        if (host.startsWith("[")) return true
        var dots = 0
        for (c in host) {
            if (c == '.') dots++ else if (c !in '0'..'9') return false
        }
        return dots == 3
    }
}

/** A hostname with its registrable domain and where its public suffix starts. */
class HostInfo(val host: String) {
    val registrable: String = PublicSuffix.registrable(host)

    /** Index in [host] of the public suffix (after the registrable label's dot), or -1. */
    val suffixStart: Int = run {
        val dot = registrable.indexOf('.')
        if (dot < 0 || registrable == host && PublicSuffix.isIp(host)) -1
        else host.length - (registrable.length - dot - 1)
    }

    fun isSameSite(other: HostInfo) = registrable == other.registrable

    /** Hashes of host, then each parent domain ("a.b.c", "b.c", "c"). */
    val suffixHashes: LongArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = ArrayList<Long>(6)
        var start = 0
        while (host.isNotEmpty()) {
            out.add(Hash.of(host, start, host.length))
            val dot = host.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        out.toLongArray()
    }

    /** Entity hashes ("google.*") aligned with [suffixHashes]; 0 where the suffix is not above the public suffix. */
    val entityHashes: LongArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = LongArray(suffixHashes.size)
        var start = 0
        var i = 0
        while (i < out.size) {
            if (suffixStart > start) out[i] = DomainConstraint.entityHash(host, start, suffixStart)
            val dot = host.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
            i++
        }
        out
    }

    companion object {
        private val EMPTY = HostInfo("")
        fun of(url: String?): HostInfo {
            val h = url?.let { Urls.host(it) } ?: return EMPTY
            return HostInfo(h)
        }
    }
}

/**
 * A `$domain=a.com|~b.com` style constraint. Entries may be entities ("google.*").
 * The most specific matching entry decides; with no match the constraint holds only if it
 * has no positive entries.
 */
class DomainConstraint private constructor(
    private val include: LongHashSet?,
    private val exclude: LongHashSet?,
    private val hasEntities: Boolean,
    val includeList: Array<String>,
) {
    val hasIncludes get() = include != null

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeBoolean(hasEntities)
        Snapshot.writeLongArray(out, include?.toArray())
        Snapshot.writeLongArray(out, exclude?.toArray())
        Snapshot.writeStrings(out, includeList.asList())
    }

    fun matches(info: HostInfo): Boolean {
        if (info.host.isEmpty()) return include == null
        val hashes = info.suffixHashes
        val entities = if (hasEntities) info.entityHashes else null
        for (i in hashes.indices) {
            val h = hashes[i]
            if (exclude != null && h in exclude) return false
            if (include != null && h in include) return true
            if (entities != null) {
                val eh = entities[i]
                if (eh != 0L) {
                    if (exclude != null && eh in exclude) return false
                    if (include != null && eh in include) return true
                }
            }
        }
        return include == null
    }

    companion object {
        fun readFrom(inp: java.io.DataInputStream): DomainConstraint {
            val entities = inp.readBoolean()
            val inc = Snapshot.readLongArray(inp)?.let { LongHashSet.of(it) }
            val exc = Snapshot.readLongArray(inp)?.let { LongHashSet.of(it) }
            val list = Snapshot.readStrings(inp).toTypedArray()
            return DomainConstraint(inc, exc, entities, list)
        }

        /** Hash of host[start, suffixStart) + "*", i.e. "google." + "*" for "www.google.co.uk". */
        fun entityHash(host: String, start: Int, suffixStart: Int): Long {
            val sb = StringBuilder(suffixStart - start + 1)
            sb.append(host, start, suffixStart).append('*')
            return Hash.of(sb)
        }

        /** Parses "a.com|~b.com" (separator configurable). Returns null when nothing valid remains. */
        fun parse(spec: String, separator: Char): DomainConstraint? {
            var inc: LongHashSet? = null
            var exc: LongHashSet? = null
            var entities = false
            val incList = ArrayList<String>()
            for (raw in spec.split(separator)) {
                var d = raw.trim().lowercase()
                if (d.isEmpty()) continue
                val negated = d.startsWith("~")
                if (negated) d = d.substring(1)
                if (d.startsWith("/") ) return null // regex domains are not supported
                if (d.endsWith(".*")) entities = true
                val key = d
                if (!isValidDomainEntry(key)) continue
                if (negated) {
                    (exc ?: LongHashSet(4).also { exc = it }).add(Hash.of(key))
                } else {
                    (inc ?: LongHashSet(4).also { inc = it }).add(Hash.of(key))
                    incList.add(d)
                }
            }
            if (inc == null && exc == null) return null
            return DomainConstraint(inc, exc, entities, incList.toTypedArray())
        }

        fun isValidDomainEntry(d: String): Boolean {
            if (d.isEmpty() || d.length > 253) return false
            for (c in d) {
                if (!(c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' || c == '*' ||
                        c == '[' || c == ']' || c == ':' || c.code > 127)) return false
            }
            return true
        }
    }
}
