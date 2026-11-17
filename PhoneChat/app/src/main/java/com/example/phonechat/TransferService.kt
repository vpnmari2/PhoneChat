package com.example.phonechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress

/** Keeps the chat server alive in the background (foreground service). */
class TransferService : Service() {

    companion object {
        const val PORT = 8765
        const val DISCOVERY_PORT = 8766
        const val ACTION_STOP = "com.example.phonechat.STOP"
        @Volatile var running = false
    }

    private var server: ChatServer? = null
    private var udp: DatagramSocket? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var mcast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Util.prefs(this).edit().putBoolean("autostart", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (server == null) startAll()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("pc", "Phone Chat", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "pc")
            .setContentTitle("Phone Chat is on")
            .setContentText("Ready for your PC - ${Util.localIp() ?: "no Wi-Fi"}:$PORT")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun startAll() {
        try {
            ChatStore.init(this)
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonechat:srv")
                .apply { setReferenceCounted(false); acquire() }

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phonechat:wifi")
                .apply { setReferenceCounted(false); acquire() }
            mcast = wm.createMulticastLock("phonechat:mc")
                .apply { setReferenceCounted(false); acquire() }

            server = ChatServer(this, PORT) { Util.pin(this) }.also { it.start() }
            startDiscovery()
            running = true
        } catch (e: Exception) {
            running = false
            stopSelf()
        }
    }

    private fun startDiscovery() {
        Thread {
            try {
                val s = DatagramSocket(null as SocketAddress?)
                s.reuseAddress = true
                s.bind(InetSocketAddress(DISCOVERY_PORT))
                udp = s
                val buf = ByteArray(256)
                while (!s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (String(p.data, 0, p.length).startsWith("PHONECHAT_DISCOVER")) {
                        val reply = JSONObject().put("app", "phonechat")
                            .put("name", Build.MANUFACTURER + " " + Build.MODEL)
                            .put("instance", Util.instanceId(this))
                            .put("port", PORT).toString().toByteArray()
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) { }
        }.start()
    }

    override fun onDestroy() {
        running = false
        try { server?.stop() } catch (e: Exception) { }
        try { udp?.close() } catch (e: Exception) { }
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        try { if (wifi?.isHeld == true) wifi?.release() } catch (e: Exception) { }
        try { if (mcast?.isHeld == true) mcast?.release() } catch (e: Exception) { }
        server = null
        super.onDestroy()
    }
}
