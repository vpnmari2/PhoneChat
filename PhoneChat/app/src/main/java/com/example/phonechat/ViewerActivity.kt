package com.example.phonechat

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/** Full-screen viewer: images, videos, and a details page for other files. */
class ViewerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        val name = intent.getStringExtra("name") ?: "file"
        val kind = intent.getStringExtra("kind") ?: "other"
        val mime = intent.getStringExtra("mime")
        val msgId = intent.getLongExtra("id", -1L)
        if (path == null || !File(path).isFile) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val f = File(path)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 72, 16, 8)
        }
        bar.addView(Button(this).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            setPadding(24, 0, 24, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Save to phone"
            isAllCaps = false
            setOnClickListener { saveToPhone(f, name, mime, kind) }
        })
        if (msgId > 0) {
            bar.addView(Button(this).apply {
                text = "Delete"
                isAllCaps = false
                setOnClickListener { confirmDelete(msgId) }
            })
        }
        root.addView(bar)

        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        when (kind) {
            "image" -> {
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                body.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                Thread {
                    try {
                        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                            d.setTargetSampleSize(maxOf(1, info.size.width / 2048))
                        }
                        runOnUiThread { iv.setImageBitmap(bmp) }
                    } catch (e: Throwable) {
                        runOnUiThread { Toast.makeText(this, "Cannot show this image", Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
            "video" -> {
                val vv = VideoView(this)
                body.addView(vv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
                vv.setMediaController(MediaController(this).also { it.setAnchorView(vv) })
                vv.setVideoPath(path)
                vv.setOnPreparedListener { it.start() }
            }
            else -> {
                body.addView(TextView(this).apply {
                    text = "$name\n${Util.fmtSize(f.length())}\n\nThis file is stored in the chat.\n" +
                        "Tap \"Save to phone\" to copy it to Downloads/PhoneChat."
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
        setContentView(root)
    }

    private fun confirmDelete(id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete this file?")
            .setMessage("It is removed from this chat and from the phone. Your PC keeps any copy it already saved.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    ChatStore.delete(id)
                    runOnUiThread { finish() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveToPhone(f: File, name: String, mime: String?, kind: String) {
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = Saver.save(this, f, name, mime, kind)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Saved in ${folderFor(kind)}/PhoneChat" else "Could not save",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun folderFor(kind: String) = when (kind) {
        "image" -> "Pictures"
        "video" -> "Movies"
        else -> "Downloads"
    }
}

/** Copies a chat file into the phone's normal gallery / Downloads using MediaStore. */
object Saver {
    fun save(ctx: Context, f: File, name: String, mime: String?, kind: String): Boolean {
        val coll = when (kind) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val base = when (kind) {
            "image" -> Environment.DIRECTORY_PICTURES
            "video" -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val rel = "$base/PhoneChat"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(coll, cv) ?: return false
        return try {
            r.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 262144) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            r.update(uri, done, null, null)
            true
        } catch (e: Exception) {
            r.delete(uri, null, null)
            false
        }
    }
}
