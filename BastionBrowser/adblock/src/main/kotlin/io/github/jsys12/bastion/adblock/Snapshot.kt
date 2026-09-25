package io.github.jsys12.bastion.adblock

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * Binary snapshot of a compiled [FilterEngine]. Loading one is several times faster than
 * re-parsing hundreds of thousands of filter lines on every app start.
 */
object Snapshot {
    private const val MAGIC = 0x4253544E // "BSTN"
    private const val VERSION = 3

    fun save(engine: FilterEngine, file: File, key: String) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(key)
            engine.writeTo(out)
            out.writeInt(MAGIC)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        }
    }

    /** Returns null when the file is missing, stale ([key] mismatch) or corrupt. */
    fun load(file: File, key: String): FilterEngine? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
                if (inp.readUTF() != key) return null
                val engine = FilterEngine.readFrom(inp)
                if (inp.readInt() != MAGIC) return null
                engine
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ primitives

    fun writeString(out: DataOutputStream, s: String?) {
        if (s == null) {
            out.writeInt(-1); return
        }
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun readString(inp: DataInputStream): String? {
        val n = inp.readInt()
        if (n < 0) return null
        val bytes = ByteArray(n)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun writeStrings(out: DataOutputStream, list: Collection<String>) {
        out.writeInt(list.size)
        for (s in list) writeString(out, s)
    }

    fun readStrings(inp: DataInputStream): ArrayList<String> {
        val n = inp.readInt()
        val out = ArrayList<String>(n)
        repeat(n) { out.add(readString(inp)!!) }
        return out
    }

    fun writeLongs(out: DataOutputStream, a: LongArray) {
        val buf = ByteBuffer.allocate(8 * 4096)
        var i = 0
        while (i < a.size) {
            buf.clear()
            val n = minOf(4096, a.size - i)
            for (j in 0 until n) buf.putLong(a[i + j])
            out.write(buf.array(), 0, n * 8)
            i += n
        }
    }

    fun readLongs(inp: DataInputStream, count: Int): LongArray {
        val out = LongArray(count)
        val bytes = ByteArray(8 * 4096)
        var i = 0
        while (i < count) {
            val n = minOf(4096, count - i)
            inp.readFully(bytes, 0, n * 8)
            val buf = ByteBuffer.wrap(bytes, 0, n * 8)
            for (j in 0 until n) out[i + j] = buf.getLong()
            i += n
        }
        return out
    }

    fun writeInts(out: DataOutputStream, a: IntArray) {
        val buf = ByteBuffer.allocate(4 * 4096)
        var i = 0
        while (i < a.size) {
            buf.clear()
            val n = minOf(4096, a.size - i)
            for (j in 0 until n) buf.putInt(a[i + j])
            out.write(buf.array(), 0, n * 4)
            i += n
        }
    }

    fun readInts(inp: DataInputStream, count: Int): IntArray {
        val out = IntArray(count)
        val bytes = ByteArray(4 * 4096)
        var i = 0
        while (i < count) {
            val n = minOf(4096, count - i)
            inp.readFully(bytes, 0, n * 4)
            val buf = ByteBuffer.wrap(bytes, 0, n * 4)
            for (j in 0 until n) out[i + j] = buf.getInt()
            i += n
        }
        return out
    }

    fun writeLongArray(out: DataOutputStream, a: LongArray?) {
        if (a == null) {
            out.writeInt(-1); return
        }
        out.writeInt(a.size)
        writeLongs(out, a)
    }

    fun readLongArray(inp: DataInputStream): LongArray? {
        val n = inp.readInt()
        return if (n < 0) null else readLongs(inp, n)
    }

    // ------------------------------------------------------------ filters

    class FilterTable {
        val ids = IdentityHashMap<NetworkFilter, Int>()
        val list = ArrayList<NetworkFilter>()
        fun id(f: NetworkFilter): Int = ids.getOrPut(f) { list.add(f); list.size - 1 }
    }

    fun writeFilter(out: DataOutputStream, f: NetworkFilter) {
        writeString(out, f.pattern)
        out.writeInt(f.flags)
        out.writeInt(f.typeMask)
        out.writeBoolean(f.domains != null)
        f.domains?.writeTo(out)
        out.writeBoolean(f.toDomains != null)
        f.toDomains?.writeTo(out)
        out.writeInt(f.methods)
        writeString(out, f.option)
        out.writeInt(f.pageFlags)
        out.writeInt(f.listId)
        out.writeInt(f.hostLen)
    }

    fun readFilter(inp: DataInputStream): NetworkFilter {
        val pattern = readString(inp)!!
        val flags = inp.readInt()
        val typeMask = inp.readInt()
        val domains = if (inp.readBoolean()) DomainConstraint.readFrom(inp) else null
        val toDomains = if (inp.readBoolean()) DomainConstraint.readFrom(inp) else null
        val methods = inp.readInt()
        val option = readString(inp)
        val pageFlags = inp.readInt()
        val listId = inp.readInt()
        val f = NetworkFilter(pattern, flags, typeMask, domains, toDomains, methods, option, pageFlags, listId)
        f.hostLen = inp.readInt()
        return f
    }
}
