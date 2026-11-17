package com.example.phonechat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One chat message: text, or a file (name != null). */
class Msg(
    val id: Long, val ts: Long, val from: String, val text: String,
    val name: String?, val size: Long, val kind: String?, val mime: String?, val path: String?,
    val del: Long = 0L   // > 0: this entry is a "message <del> was deleted" notice for the PC
) {
    val isFile: Boolean get() = name != null

    fun toJson(withPath: Boolean): JSONObject {
        val o = JSONObject().put("id", id).put("ts", ts).put("from", from).put("text", text)
        if (del > 0) o.put("del", del)
        if (name != null) {
            val f = JSONObject().put("name", name).put("size", size).put("kind", kind).put("mime", mime)
            if (withPath) f.put("path", path)
            o.put("file", f)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Msg {
            val f = o.optJSONObject("file")
            return Msg(
                o.getLong("id"), o.getLong("ts"), o.getString("from"), o.optString("text", ""),
                f?.getString("name"), f?.optLong("size", 0L) ?: 0L,
                f?.optString("kind"), f?.optString("mime"), f?.optString("path"),
                o.optLong("del", 0L)
            )
        }
    }
}

/** The chat log. The phone holds it; the PC syncs from here. Saved to disk, so it survives restarts. */
object ChatStore {
    private val lk = ReentrantLock()
    private val cond = lk.newCondition()
    private val msgs = ArrayList<Msg>()
    private var nextId = 1L
    private var logFile: File? = null
    private var inited = false

    lateinit var dir: File
    @Volatile var lastPcSeen = 0L
    @Volatile var listener: (() -> Unit)? = null
    val pending = AtomicInteger(0)

    fun init(ctx: Context) {
        lk.withLock {
            if (inited) return@withLock
            inited = true
            val app = ctx.applicationContext
            dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "chat").apply { mkdirs() }
            val lf = File(app.filesDir, "chat.jsonl")
            logFile = lf
            if (lf.exists()) {
                lf.forEachLine { l ->
                    try {
                        val m = Msg.fromJson(JSONObject(l))
                        if (m.del > 0) msgs.removeAll { it.id == m.del }
                        msgs.add(m)
                        if (m.id >= nextId) nextId = m.id + 1
                    } catch (e: Exception) { }
                }
            }
            val weekAgo = System.currentTimeMillis() - 7 * 86400000L
            dir.listFiles()?.forEach {
                if ((it.name.endsWith(".part") || it.name.startsWith("tmp_")) && it.lastModified() < weekAgo) it.delete()
            }
        }
    }

    /** Messages for display (delete notices hidden). */
    fun all(): List<Msg> = lk.withLock { msgs.filter { it.del == 0L } }
    fun lastId(): Long = lk.withLock { nextId - 1 }
    fun byId(id: Long): Msg? = lk.withLock { msgs.firstOrNull { it.id == id } }

    /** Messages newer than [after]; waits up to [waitMs] if there are none yet (long poll). */
    fun waitSince(after: Long, waitMs: Long): List<Msg> {
        lk.lock()
        try {
            var res = msgs.filter { it.id > after }
            if (res.isEmpty() && waitMs > 0) {
                cond.await(waitMs, TimeUnit.MILLISECONDS)
                res = msgs.filter { it.id > after }
            }
            return res
        } finally {
            lk.unlock()
        }
    }

    // caller holds the lock
    private fun append(m: Msg) {
        msgs.add(m)
        try { logFile?.appendText(m.toJson(true).toString() + "\n") } catch (e: Exception) { }
        cond.signalAll()
    }

    fun addText(from: String, text: String): Msg {
        val m = lk.withLock {
            val x = Msg(nextId++, System.currentTimeMillis(), from, text, null, 0L, null, null, null)
            append(x)
            x
        }
        listener?.invoke()
        return m
    }

    /** Deletes a message and its file; the PC is told through a delete notice. */
    fun delete(id: Long): Boolean {
        val removed = lk.withLock {
            val m = msgs.firstOrNull { it.id == id && it.del == 0L } ?: return@withLock null
            msgs.remove(m)
            append(Msg(nextId++, System.currentTimeMillis(), "system", "", null, 0L, null, null, null, id))
            m
        }
        if (removed == null) return false
        val p = removed.path
        if (p != null) {
            try { File(p).delete() } catch (e: Exception) { }
        }
        listener?.invoke()
        return true
    }

    /** Moves a finished temp file into the chat folder and posts it as a file message. */
    fun commitFile(from: String, name: String, mime: String?, tmp: File): Msg? {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifEmpty { "file" }
        val m = lk.withLock {
            val id = nextId
            val target = File(dir, "${id}_$clean")
            if (!tmp.renameTo(target)) return@withLock null
            nextId++
            val x = Msg(id, System.currentTimeMillis(), from, "", name, target.length(),
                Util.kindOf(name, mime), mime, target.absolutePath)
            append(x)
            x
        }
        if (m != null) listener?.invoke()
        return m
    }
}
