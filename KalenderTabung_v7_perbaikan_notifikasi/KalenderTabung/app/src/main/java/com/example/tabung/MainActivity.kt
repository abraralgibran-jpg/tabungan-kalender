package com.example.tabung

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var wv: WebView
    private var cb: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, c: android.webkit.ValueCallback<Array<android.net.Uri>>?, p: android.webkit.WebChromeClient.FileChooserParams?): Boolean {
                cb?.onReceiveValue(null); cb = c
                return try {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 7)
                    true
                } catch (e: Exception) { cb?.onReceiveValue(null); cb = null; false }
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, u: String?) { applyTheme(); fill(intent) }
        }
        wv.setBackgroundColor(if (isNight()) Color.parseColor("#121212") else Color.WHITE)
        setContentView(wv)
        wv.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        // App bisa dibuka lagi setelah lewat tengah malam: segarkan tanggal hari ini dan sinkron ke widget
        if (::wv.isInitialized) wv.evaluateJavascript("render()", null)
    }

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        if (rq == 7) { cb?.onReceiveValue(if (rs == RESULT_OK && d?.data != null) arrayOf(d.data!!) else null); cb = null }
        if (rq == 8) {
            val u = d?.data
            if (rs != RESULT_OK || u == null) return
            // Decode di thread terpisah supaya UI tidak macet, lalu kirim hasilnya ke halaman
            Thread {
                val r = try { photo(u) } catch (e: Throwable) { null }
                runOnUiThread {
                    if (r != null) wv.evaluateJavascript("onPhoto(" + JSONObject.quote(r) + ",'')", null)
                    else wv.evaluateJavascript("onPhoto('','Foto tidak bisa dibaca. Coba foto lain.')", null)
                }
            }.start()
        }
    }

    // Kecilkan foto (maks 400px), putar sesuai EXIF, simpan sebagai JPEG data URL
    private fun photo(u: Uri): String? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bo) }
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        var ss = 1
        while (maxOf(bo.outWidth, bo.outHeight) / (ss * 2) >= 400) ss *= 2
        val src = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) } ?: return null
        val ori = try {
            contentResolver.openInputStream(u)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (e: Exception) { 1 }
        val mx = Matrix()
        when (ori) { 6 -> mx.postRotate(90f); 3 -> mx.postRotate(180f); 8 -> mx.postRotate(270f) }
        val k = 400f / maxOf(src.width, src.height)
        if (k < 1f) mx.postScale(k, k)
        val rot = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mx, true)
        val out = Bitmap.createBitmap(rot.width, rot.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(rot, 0f, 0f, null) }
        val bs = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 75, bs)
        return "data:image/jpeg;base64," + Base64.encodeToString(bs.toByteArray(), Base64.NO_WRAP)
    }

    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Samakan tema halaman dengan mode gelap/terang HP (WebView tidak selalu mengikutinya)
    private fun applyTheme() {
        wv.setBackgroundColor(if (isNight()) Color.parseColor("#121212") else Color.WHITE)
        wv.evaluateJavascript("document.documentElement.setAttribute('data-theme','" + (if (isNight()) "dark" else "light") + "')", null)
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); if (::wv.isInitialized) applyTheme() }

    // Tombol Back: tutup sheet dulu, baru keluar aplikasi
    @Deprecated("dipakai agar kompatibel dengan minSdk 24")
    override fun onBackPressed() {
        wv.evaluateJavascript("(function(){if(isOpen()){cl();return 1}return 0})()") { if (it != "1") finish() }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); intent = i; fill(i) }

    // Dibuka dari notifikasi atau tombol "Isi hari ini" di widget: langsung buka sheet nominal hari ini
    private fun fill(i: Intent) {
        if (i.getBooleanExtra("fill", false)) {
            i.removeExtra("fill") // hanya sekali, supaya tidak terbuka lagi saat activity dibuat ulang
            wv.evaluateJavascript("refreshTK();am(TK)", null)
        }
    }

    // Notifikasi harus diizinkan (Android 13+) dan tidak dimatikan user di pengaturan HP
    private fun notifOk(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) runOnUiThread { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9) }
    }

    inner class Bridge {
        // Tombol "Upload foto": buka galeri/file langsung dari aplikasi (tidak lewat file chooser WebView)
        @JavascriptInterface
        fun pickImage() {
            runOnUiThread {
                try {
                    startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), "Pilih foto"), 8)
                } catch (e: Exception) {
                    wv.evaluateJavascript("onPhoto('','Tidak ada aplikasi galeri atau file di HP ini.')", null)
                }
            }
        }

        // Simpan jam, aktif/nonaktif, dan hari pengingat (bitmask, bit 0 = Minggu)
        @JavascriptInterface
        fun remind(on: Boolean, h: Int, m: Int, days: Int) { Remind.plan(this@MainActivity, on, h, m, days) }

        // Dipanggil saat user mengaktifkan pengingat
        @JavascriptInterface
        fun ask() { askNotifPermission() }

        @JavascriptInterface
        fun canNotify(): Boolean { return notifOk() }

        // Tombol "Coba notifikasi sekarang"
        @JavascriptInterface
        fun test(): Boolean {
            if (notifOk()) { Remind.show(this@MainActivity); return true }
            askNotifPermission()
            return false
        }

        @JavascriptInterface
        fun sync(j: String) {
            getSharedPreferences("w", MODE_PRIVATE).edit().putString("j", j).apply()
            val m = AppWidgetManager.getInstance(this@MainActivity)
            val ids = m.getAppWidgetIds(ComponentName(this@MainActivity, TabungWidget::class.java))
            TabungWidget.draw(this@MainActivity, m, ids)
        }
    }
}
