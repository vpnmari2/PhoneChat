package com.example.phonechat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private val blue = Color.parseColor("#0084FF")
    private val gray = Color.parseColor("#E4E6EB")

    private lateinit var statusV: TextView
    private lateinit var emptyV: TextView
    private lateinit var tabChat: TextView
    private lateinit var tabGallery: TextView
    private lateinit var chatPage: LinearLayout
    private lateinit var galleryPage: LinearLayout
    private lateinit var chatList: ListView
    private lateinit var grid: GridView
    private lateinit var input: EditText
    private val filterViews = ArrayList<TextView>()
    private val chatAdapter = ChatAdapter()
    private val galleryAdapter = GalleryAdapter()
    private var filter = "all"
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, r: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChatStore.init(this)
        buildUi()
        selectTab(0)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (Util.prefs(this).getBoolean("autostart", true) && !TransferService.running) startSvc()
        ChatStore.listener = { runOnUiThread { refreshLists() } }
        refreshLists()
        handler.post(ticker)
        askBatteryOnce()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        ChatStore.listener = null
        super.onPause()
    }

    private fun startSvc() {
        try {
            startForegroundService(Intent(this, TransferService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun askBatteryOnce() {
        val p = Util.prefs(this)
        if (p.getBoolean("asked_batt", false)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        p.edit().putBoolean("asked_batt", true).apply()
        handler.postDelayed({ requestBattery() }, 1500)
    }

    private fun requestBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ UI
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(blue)
            setPadding(dp(16), dp(34), dp(6), dp(10))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("My PC", 20f, Color.WHITE, true))
        statusV = tv("", 12f, Color.parseColor("#DDEBFF"))
        titles.addView(statusV)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(tv("\u22EE", 28f, Color.WHITE, true).apply {
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { showInfo() }
        })
        root.addView(header)

        // tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabChat = tv("Chat", 15f, blue, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { selectTab(0) }
        }
        tabGallery = tv("Gallery", 15f, blue, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { selectTab(1) }
        }
        tabs.addView(tabChat, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tabs.addView(tabGallery, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(tabs)

        val pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // chat page
        chatPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listBox = FrameLayout(this)
        chatList = ListView(this).apply {
            divider = null
            adapter = chatAdapter
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
        }
        emptyV = tv("", 14f, Color.GRAY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(30), 0, dp(30), 0)
        }
        listBox.addView(chatList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listBox.addView(emptyV, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        chatList.emptyView = emptyV
        chatPage.addView(listBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.addView(tv("+", 28f, blue, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { pickFiles() }
        })
        input = EditText(this).apply {
            hint = "Message"
            background = round(gray, 22)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 4
            textSize = 16f
        }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(tv("Send", 16f, blue, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { sendText() }
        })
        chatPage.addView(bar)
        pages.addView(chatPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // gallery page
        galleryPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        for ((label, key) in listOf("All" to "all", "Images" to "image", "Videos" to "video", "Files" to "other")) {
            val chip = tv(label, 14f, Color.BLACK).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                tag = key
                setOnClickListener { filter = key; refreshLists() }
            }
            filterViews.add(chip)
            chips.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        galleryPage.addView(chips)
        grid = GridView(this).apply {
            numColumns = 3
            adapter = galleryAdapter
            horizontalSpacing = 0
            verticalSpacing = 0
        }
        galleryPage.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        pages.addView(galleryPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)
    }

    private fun selectTab(i: Int) {
        chatPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        galleryPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabChat.background = round(if (i == 0) Color.parseColor("#E7F3FF") else Color.WHITE, 0)
        tabGallery.background = round(if (i == 1) Color.parseColor("#E7F3FF") else Color.WHITE, 0)
        refreshLists()
    }

    private fun refreshLists() {
        chatAdapter.items = ChatStore.all()
        chatAdapter.notifyDataSetChanged()
        galleryAdapter.items = ChatStore.all()
            .filter { it.isFile && (filter == "all" || it.kind == filter) }
            .reversed()
        galleryAdapter.notifyDataSetChanged()
        for (v in filterViews) {
            val sel = v.tag == filter
            v.background = round(if (sel) blue else gray, 20)
            v.setTextColor(if (sel) Color.WHITE else Color.BLACK)
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val on = TransferService.running
        val seen = System.currentTimeMillis() - ChatStore.lastPcSeen < 45000
        val n = ChatStore.pending.get()
        statusV.text = when {
            !on -> "Offline - open the menu to start"
            n > 0 -> "Preparing $n file(s)..."
            seen -> "PC connected"
            else -> "Waiting for PC - open the menu for address and PIN"
        }
        emptyV.text = "No messages yet.\n\nOn your PC run phone_chat.py and enter:\n" +
            "Address: ${Util.localIp() ?: "(connect to Wi-Fi)"}\nPIN: ${Util.pin(this)}"
    }

    private fun showInfo() {
        val on = TransferService.running
        AlertDialog.Builder(this)
            .setTitle("Connect your PC")
            .setMessage(
                "Address: ${Util.localIp() ?: "no Wi-Fi"}\nPort: ${TransferService.PORT}\n" +
                    "PIN: ${Util.pin(this)}\n\n" +
                    "Run phone_chat.py on the PC. It finds this phone by itself; just type the PIN.\n\n" +
                    "Use only on Wi-Fi you trust. Don't put this app to sleep in Device care."
            )
            .setPositiveButton("New PIN") { _, _ -> Util.newPin(this); refreshStatus() }
            .setNeutralButton("Battery") { _, _ -> requestBattery() }
            .setNegativeButton(if (on) "Stop service" else "Start service") { _, _ ->
                if (on) {
                    startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_STOP))
                } else {
                    Util.prefs(this).edit().putBoolean("autostart", true).apply()
                    startSvc()
                }
                handler.postDelayed({ refreshStatus() }, 600)
            }
            .show()
    }

    // ------------------------------------------------------------------ sending
    private fun sendText() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        ChatStore.addText("phone", t)
        input.setText("")
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 7)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        addFiles(uris)
    }

    private fun addFiles(uris: List<Uri>) {
        ChatStore.pending.addAndGet(uris.size)
        refreshStatus()
        Thread {
            for (u in uris) {
                var tmp: File? = null
                try {
                    var name = "file"
                    val cur = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    if (cur != null) {
                        try {
                            if (cur.moveToFirst()) name = cur.getString(0) ?: name
                        } finally {
                            cur.close()
                        }
                    }
                    val mime = contentResolver.getType(u)
                    val t = File(ChatStore.dir, "tmp_" + UUID.randomUUID())
                    tmp = t
                    contentResolver.openInputStream(u)!!.use { i -> t.outputStream().use { o -> i.copyTo(o, 262144) } }
                    ChatStore.commitFile("phone", name, mime, t)
                } catch (e: Exception) {
                    tmp?.delete()
                    runOnUiThread { Toast.makeText(this, "Could not add file: ${e.message}", Toast.LENGTH_LONG).show() }
                } finally {
                    ChatStore.pending.decrementAndGet()
                    runOnUiThread { refreshLists() }
                }
            }
        }.start()
    }

    private fun openViewer(m: Msg) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra("path", m.path).putExtra("name", m.name)
                .putExtra("kind", m.kind).putExtra("mime", m.mime).putExtra("id", m.id)
        )
    }

    private fun confirmDelete(m: Msg) {
        val what = if (m.isFile) "\"${m.name}\"" else "this message"
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Delete $what from this chat and from the phone? Your PC keeps any copy it already saved.")
            .setPositiveButton("Delete") { _, _ -> Thread { ChatStore.delete(m.id) }.start() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ adapters
    inner class ChatAdapter : BaseAdapter() {
        var items: List<Msg> = emptyList()
        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = items[position]
            val mine = m.from == "phone"
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (mine) Gravity.END else Gravity.START
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val bubble = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = round(if (mine) blue else gray)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val fg = if (mine) Color.WHITE else Color.BLACK
            val maxW = (resources.displayMetrics.widthPixels * 0.75f).toInt()

            if (m.isFile) {
                val kind = m.kind ?: "other"
                if (kind == "image" || kind == "video") {
                    val iv = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    bubble.addView(iv, LinearLayout.LayoutParams(dp(210), dp(210)))
                    Thumbs.load(m.path ?: "", kind, iv, 420)
                }
                val prefix = when (kind) { "video" -> "\u25B6 "; "image" -> ""; else -> "\uD83D\uDCC4 " }
                bubble.addView(tv(prefix + m.name, 14f, fg).apply {
                    maxWidth = maxW
                    setPadding(0, dp(4), 0, 0)
                })
                bubble.addView(tv(Util.fmtSize(m.size), 11f, fg).apply { alpha = 0.8f })
                bubble.setOnClickListener { openViewer(m) }
            } else {
                bubble.addView(tv(m.text, 16f, fg).apply { maxWidth = maxW })
            }
            bubble.setOnLongClickListener { confirmDelete(m); true }
            row.addView(bubble, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            row.addView(tv(timeFmt.format(Date(m.ts)), 10f, Color.GRAY).apply {
                setPadding(dp(6), dp(2), dp(6), 0)
            })
            return row
        }
    }

    inner class GalleryAdapter : BaseAdapter() {
        var items: List<Msg> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = items[position]
            val size = resources.displayMetrics.widthPixels / 3
            val kind = m.kind ?: "other"
            val cell = FrameLayout(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(size, size)
                setPadding(2, 2, 2, 2)
                setOnClickListener { openViewer(m) }
                setOnLongClickListener { confirmDelete(m); true }
            }
            if (kind == "image" || kind == "video") {
                val iv = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(gray)
                }
                cell.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                Thumbs.load(m.path ?: "", kind, iv, 360)
                if (kind == "video") {
                    cell.addView(tv("\u25B6", 22f, Color.WHITE, true).apply {
                        setShadowLayer(6f, 0f, 0f, Color.BLACK)
                    }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                        leftMargin = dp(6)
                    })
                }
            } else {
                val ext = (m.name ?: "").substringAfterLast('.', "FILE").uppercase().take(5)
                cell.addView(tv(ext + "\n" + (m.name ?: "").take(18), 12f, Color.DKGRAY).apply {
                    gravity = Gravity.CENTER
                    background = round(gray, 0)
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            return cell
        }
    }
}
