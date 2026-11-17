package com.example.phonechat

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

object Util {
    fun prefs(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)

    fun newPin(c: Context): String {
        val s = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        prefs(c).edit().putString("pin", s).apply()
        return s
    }

    fun pin(c: Context): String = prefs(c).getString("pin", null) ?: newPin(c)

    fun instanceId(c: Context): String {
        val p = prefs(c)
        val cur = p.getString("iid", null)
        if (cur != null) return cur
        val n = UUID.randomUUID().toString().take(8)
        p.edit().putString("iid", n).apply()
        return n
    }

    fun fmtSize(b: Long): String = when {
        b >= 1073741824L -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1048576L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    fun kindOf(name: String, mime: String?): String {
        val m = (mime ?: "").lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") -> "image"
            m.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "wmv", "flv") -> "video"
            else -> "other"
        }
    }

    /** Best guess at this phone's Wi-Fi / hotspot IPv4 address. */
    fun localIp(): String? {
        val cands = ArrayList<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val h = a.hostAddress ?: continue
                        cands.add(Pair(ni.name, h))
                    }
                }
            }
        } catch (e: Exception) { }
        val wifi = cands.firstOrNull {
            it.first.startsWith("wlan") || it.first.startsWith("swlan") ||
                it.first.startsWith("ap") || it.first.startsWith("eth")
        }
        val other = cands.firstOrNull {
            !it.first.startsWith("rmnet") && !it.first.startsWith("tun") &&
                !it.first.startsWith("ccmni") && !it.first.startsWith("dummy")
        }
        return (wifi ?: other)?.second
    }
}
