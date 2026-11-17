package com.example.phonechat

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Loads image / video thumbnails off the main thread, with a small memory cache. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(path: String, kind: String, iv: ImageView, px: Int) {
        iv.tag = path
        val hit = cache.get(path)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        pool.execute {
            val bmp: Bitmap? = try {
                val f = File(path)
                if (kind == "video") ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
                else ThumbnailUtils.createImageThumbnail(f, Size(px, px), null)
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(path, bmp)
                main.post { if (iv.tag == path) iv.setImageBitmap(bmp) }
            }
        }
    }
}
