package io.github.jsys12.bastion.adblock

/** 64-bit string hashing used for hostnames and tokens; never returns 0 (the empty-slot marker). */
object Hash {
    fun of(s: CharSequence): Long = of(s, 0, s.length)

    fun of(s: CharSequence, start: Int, end: Int): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (i in start until end) {
            h = h xor s[i].code.toLong()
            h *= 0x100000001b3L
        }
        // Final avalanche (splitmix64 finalizer) so low bits are well distributed for masking.
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        return if (h == 0L) 1L else h
    }

    private fun mix(h: Long): Int {
        val x = h xor (h ushr 32)
        return (x xor (x ushr 16)).toInt()
    }

    fun slot(h: Long, mask: Int): Int = mix(h) and mask
}

/** Open-addressing set of non-zero longs. */
class LongHashSet(expected: Int = 16) {
    private var keys: LongArray
    private var mask: Int
    var size = 0
        private set

    init {
        val cap = tableSizeFor(expected)
        keys = LongArray(cap)
        mask = cap - 1
    }

    fun add(k: Long): Boolean {
        if ((size + 1) * 4 > keys.size * 3) grow()
        var i = Hash.slot(k, mask)
        while (true) {
            val cur = keys[i]
            if (cur == 0L) {
                keys[i] = k; size++; return true
            }
            if (cur == k) return false
            i = (i + 1) and mask
        }
    }

    operator fun contains(k: Long): Boolean {
        if (size == 0) return false
        var i = Hash.slot(k, mask)
        while (true) {
            val cur = keys[i]
            if (cur == 0L) return false
            if (cur == k) return true
            i = (i + 1) and mask
        }
    }

    fun remove(k: Long): Boolean {
        if (!contains(k)) return false
        val old = keys
        keys = LongArray(old.size)
        size = 0
        for (x in old) if (x != 0L && x != k) add(x)
        return true
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2)
        mask = keys.size - 1
        size = 0
        for (k in old) if (k != 0L) add(k)
    }

    fun toArray(): LongArray {
        val out = LongArray(size)
        var n = 0
        for (k in keys) if (k != 0L) out[n++] = k
        return out
    }

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(size)
        out.writeInt(keys.size)
        Snapshot.writeLongs(out, keys)
    }

    companion object {
        fun readFrom(inp: java.io.DataInputStream): LongHashSet {
            val size = inp.readInt()
            val cap = inp.readInt()
            val set = LongHashSet(1)
            set.keys = Snapshot.readLongs(inp, cap)
            set.mask = cap - 1
            set.size = size
            return set
        }

        fun of(values: LongArray): LongHashSet {
            val set = LongHashSet(values.size)
            for (v in values) set.add(v)
            return set
        }
    }
}

/** Open-addressing map from non-zero long keys to ints. */
class LongIntMap(expected: Int = 16) {
    private var keys: LongArray
    private var values: IntArray
    private var mask: Int
    var size = 0
        private set

    init {
        val cap = tableSizeFor(expected)
        keys = LongArray(cap)
        values = IntArray(cap)
        mask = cap - 1
    }

    /** Returns the stored value or [missing]. */
    fun get(k: Long, missing: Int = 0): Int {
        if (size == 0) return missing
        var i = Hash.slot(k, mask)
        while (true) {
            val cur = keys[i]
            if (cur == 0L) return missing
            if (cur == k) return values[i]
            i = (i + 1) and mask
        }
    }

    fun put(k: Long, v: Int) {
        if ((size + 1) * 4 > keys.size * 3) grow()
        var i = Hash.slot(k, mask)
        while (true) {
            val cur = keys[i]
            if (cur == 0L) {
                keys[i] = k; values[i] = v; size++; return
            }
            if (cur == k) {
                values[i] = v; return
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oldK = keys
        val oldV = values
        keys = LongArray(oldK.size * 2)
        values = IntArray(oldK.size * 2)
        mask = keys.size - 1
        size = 0
        for (i in oldK.indices) if (oldK[i] != 0L) put(oldK[i], oldV[i])
    }

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(size)
        out.writeInt(keys.size)
        Snapshot.writeLongs(out, keys)
        Snapshot.writeInts(out, values)
    }

    companion object {
        fun readFrom(inp: java.io.DataInputStream): LongIntMap {
            val size = inp.readInt()
            val cap = inp.readInt()
            val m = LongIntMap(1)
            m.keys = Snapshot.readLongs(inp, cap)
            m.values = Snapshot.readInts(inp, cap)
            m.mask = cap - 1
            m.size = size
            return m
        }
    }
}

/** Open-addressing map from non-zero long keys to objects. */
class LongObjMap<V : Any>(expected: Int = 16) {
    private var keys: LongArray
    private var values: Array<Any?>
    private var mask: Int
    var size = 0
        private set

    init {
        val cap = tableSizeFor(expected)
        keys = LongArray(cap)
        values = arrayOfNulls(cap)
        mask = cap - 1
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(k: Long): V? {
        if (size == 0) return null
        var i = Hash.slot(k, mask)
        while (true) {
            val cur = keys[i]
            if (cur == 0L) return null
            if (cur == k) return values[i] as V
            i = (i + 1) and mask
        }
    }

    operator fun set(k: Long, v: V) {
        if ((size + 1) * 4 > keys.size * 3) grow()
        var i = Hash.slot(k, mask)
        while (true) {
            val cur = keys[i]
            if (cur == 0L) {
                keys[i] = k; values[i] = v; size++; return
            }
            if (cur == k) {
                values[i] = v; return
            }
            i = (i + 1) and mask
        }
    }

    inline fun getOrPut(k: Long, create: () -> V): V = get(k) ?: create().also { set(k, it) }

    @Suppress("UNCHECKED_CAST")
    fun forEachValue(action: (V) -> Unit) {
        for (v in values) if (v != null) action(v as V)
    }

    @Suppress("UNCHECKED_CAST")
    fun forEachEntry(action: (Long, V) -> Unit) {
        for (i in keys.indices) if (keys[i] != 0L) action(keys[i], values[i] as V)
    }

    @Suppress("UNCHECKED_CAST")
    fun replaceValues(transform: (V) -> V) {
        for (i in values.indices) {
            val v = values[i]
            if (v != null) values[i] = transform(v as V)
        }
    }

    private fun grow() {
        val oldK = keys
        val oldV = values
        keys = LongArray(oldK.size * 2)
        values = arrayOfNulls(oldK.size * 2)
        mask = keys.size - 1
        size = 0
        @Suppress("UNCHECKED_CAST")
        for (i in oldK.indices) if (oldK[i] != 0L) set(oldK[i], oldV[i] as V)
    }
}

internal fun tableSizeFor(expected: Int): Int {
    var cap = 16
    val need = (expected.coerceAtLeast(1) * 4L / 3 + 1).coerceAtMost(1L shl 30).toInt()
    while (cap < need) cap = cap shl 1
    return cap
}
