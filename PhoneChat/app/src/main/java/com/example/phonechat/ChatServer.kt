package com.example.phonechat

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small HTTP server the PC talks to. Every route except /ping needs header  X-Pin.
 *   GET  /ping
 *   GET  /messages?after=ID&wait=SECONDS   (long poll, new messages)
 *   POST /message                          {"text": "..."}
 *   DELETE /message?id=MSGID               (removes the message and its file)
 *   PUT  /file?up=UPLOADID&name=..&mime=.. (raw bytes; X-Offset resumes)  -> creates a file message
 *   GET  /partial?up=UPLOADID              (bytes already received + free space)
 *   GET  /file?id=MSGID                    (raw bytes; Range resumes)
 */
class ChatServer(
    private val ctx: Context,
    private val port: Int,
    private val pinProvider: () -> String
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(16)
    private val fails = HashMap<String, Pair<Int, Long>>()
    private val activeUploads = HashSet<String>()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        Thread {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                } catch (e: Exception) {
                    if (ss.isClosed) break
                }
            }
        }.start()
    }

    fun stop() {
        try { server?.close() } catch (e: Exception) { }
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            process(s)
        } catch (e: Exception) {
            // client disconnected or bad request
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun process(s: Socket) {
        s.soTimeout = 30000
        val input = BufferedInputStream(s.getInputStream(), 65536)
        val out = s.getOutputStream()

        val reqLine = readLine(input) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }

        val qi = target.indexOf('?')
        val route = if (qi >= 0) target.substring(0, qi) else target
        val query = HashMap<String, String>()
        if (qi >= 0) {
            for (kv in target.substring(qi + 1).split('&')) {
                val e = kv.indexOf('=')
                if (e > 0) query[kv.substring(0, e)] = URLDecoder.decode(kv.substring(e + 1), "UTF-8")
            }
        }

        if (route == "/ping") {
            json(out, 200, JSONObject().put("app", "phonechat")
                .put("name", Build.MANUFACTURER + " " + Build.MODEL)
                .put("instance", Util.instanceId(ctx)).put("version", 2))
            return
        }

        val ip = s.inetAddress.hostAddress ?: "?"
        if (!authorized(ip, headers["x-pin"])) {
            json(out, 401, err("Wrong or missing PIN (5 wrong tries locks you out for 5 minutes)"))
            return
        }
        ChatStore.lastPcSeen = System.currentTimeMillis()

        when {
            method == "GET" && route == "/messages" -> messages(out, query)
            method == "DELETE" && route == "/message" -> deleteMessage(out, query)
            method == "POST" && route == "/message" -> postMessage(input, out, headers)
            method == "GET" && route == "/partial" -> partial(out, query)
            method == "PUT" && route == "/file" -> upload(input, out, query, headers)
            method == "GET" && route == "/file" -> download(out, query, headers)
            else -> json(out, 404, err("Not found"))
        }
    }

    @Synchronized
    private fun authorized(ip: String, pin: String?): Boolean {
        val now = System.currentTimeMillis()
        val st = fails[ip]
        if (st != null && st.second > now) return false
        val ok = pin != null &&
            MessageDigest.isEqual(pin.toByteArray(), pinProvider().toByteArray())
        if (ok) {
            fails.remove(ip)
            return true
        }
        val count = (st?.first ?: 0) + 1
        fails[ip] = if (count >= 5) Pair(0, now + 5 * 60_000L) else Pair(count, 0L)
        return false
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16384) return null
        }
        return sb.toString()
    }

    private fun send(out: OutputStream, code: Int, ctype: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable"
            else -> "Error"
        }
        val h = "HTTP/1.1 $code $reason\r\nContent-Type: $ctype\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, code: Int, o: JSONObject) =
        send(out, code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun err(m: String) = JSONObject().put("error", m)

    private fun messages(out: OutputStream, q: Map<String, String>) {
        val after = q["after"]?.toLongOrNull() ?: 0L
        val wait = (q["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, 25L)
        val list = ChatStore.waitSince(after, wait * 1000)
        ChatStore.lastPcSeen = System.currentTimeMillis()
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson(false)) }
        json(out, 200, JSONObject().put("messages", arr).put("last", ChatStore.lastId()))
    }

    private fun deleteMessage(out: OutputStream, q: Map<String, String>) {
        val id = q["id"]?.toLongOrNull()
        if (id == null || !ChatStore.delete(id)) {
            json(out, 404, err("No such message"))
            return
        }
        json(out, 200, JSONObject().put("ok", true))
    }

    private fun postMessage(input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val n = headers["content-length"]?.toIntOrNull() ?: 0
        if (n <= 0 || n > 1_000_000) {
            json(out, 400, err("Bad message size"))
            return
        }
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw EOFException()
            got += r
        }
        val text = JSONObject(String(buf, Charsets.UTF_8)).optString("text", "").trim()
        if (text.isEmpty()) {
            json(out, 400, err("Empty message"))
            return
        }
        json(out, 200, ChatStore.addText("pc", text).toJson(false))
    }

    private fun upId(q: Map<String, String>) =
        (q["up"] ?: "").filter { it.isLetterOrDigit() || it == '-' }.take(64)

    private fun partial(out: OutputStream, q: Map<String, String>) {
        val up = upId(q)
        if (up.isEmpty()) {
            json(out, 400, err("Missing upload id"))
            return
        }
        synchronized(activeUploads) {
            if (activeUploads.contains(up)) {
                json(out, 409, err("Previous upload still closing, retry"))
                return
            }
        }
        val tmp = File(ChatStore.dir, "$up.part")
        json(out, 200, JSONObject().put("size", if (tmp.isFile) tmp.length() else 0L)
            .put("free", ChatStore.dir.usableSpace))
    }

    private fun download(out: OutputStream, q: Map<String, String>, headers: Map<String, String>) {
        val id = q["id"]?.toLongOrNull()
        val m = if (id != null) ChatStore.byId(id) else null
        val p = m?.path
        val f = if (p != null) File(p) else null
        if (f == null || !f.isFile) {
            json(out, 404, err("No such file"))
            return
        }
        val total = f.length()
        var start = 0L
        val range = headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            start = range.substring(6).substringBefore('-').toLongOrNull() ?: 0L
            if (start < 0 || start >= total) {
                json(out, 416, err("Range not satisfiable"))
                return
            }
        }
        val h = StringBuilder()
        h.append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        h.append("Content-Type: application/octet-stream\r\n")
        h.append("Content-Length: ${total - start}\r\n")
        if (start > 0) h.append("Content-Range: bytes $start-${total - 1}/$total\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray())
        FileInputStream(f).use { fis ->
            if (start > 0) fis.channel.position(start)
            fis.copyTo(out, 262144)
        }
        out.flush()
    }

    private fun upload(input: InputStream, out: OutputStream, q: Map<String, String>, headers: Map<String, String>) {
        val up = upId(q)
        val name = (q["name"] ?: "").trim()
        val len = headers["content-length"]?.toLongOrNull()
        if (up.isEmpty() || name.isEmpty() || len == null || len < 0) {
            json(out, 400, err("Missing upload id, name or Content-Length"))
            return
        }
        val tmp = File(ChatStore.dir, "$up.part")
        val offset = headers["x-offset"]?.toLongOrNull() ?: 0L
        if (offset > 0) {
            val have = if (tmp.isFile) tmp.length() else -1L
            if (have != offset) {
                json(out, 409, err("Resume mismatch: phone has $have bytes"))
                return
            }
        }
        if (ChatStore.dir.usableSpace < len + 50L * 1024 * 1024) {
            json(out, 400, err("Not enough free space on the phone"))
            return
        }
        synchronized(activeUploads) {
            if (!activeUploads.add(up)) {
                json(out, 409, err("Another upload with this id is running"))
                return
            }
        }
        try {
            // If the connection drops, the .part file is kept so the PC can resume.
            var left = len
            FileOutputStream(tmp, offset > 0).buffered(262144).use { o ->
                val buf = ByteArray(262144)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException()
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        } finally {
            synchronized(activeUploads) { activeUploads.remove(up) }
        }
        val msg = ChatStore.commitFile("pc", name, q["mime"], tmp)
        if (msg == null) {
            json(out, 500, err("Could not save file"))
            return
        }
        json(out, 200, msg.toJson(false))
    }
}
