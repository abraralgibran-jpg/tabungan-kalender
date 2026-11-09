package com.example.tabung

import android.app.*
import android.content.*
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class Remind : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = c.getSharedPreferences("w", Context.MODE_PRIVATE)
        val on = p.getBoolean("on", false)
        val h = p.getInt("h", 20); val m = p.getInt("m", 0); val dm = p.getInt("dm", ALL_DAYS)
        // Setelah HP restart atau aplikasi diperbarui, alarm hilang: jadwalkan ulang
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) { plan(c, on, h, m, dm); return }
        if (!on) return
        try {
            val j = JSONObject(p.getString("j", "{}") ?: "{}")
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val sudah = j.optBoolean("d") && j.optString("dt") == today // sudah nabung hari ini
            val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 // 0 = Minggu
            val dipilih = ((dm shr dow) and 1) == 1
            Log.i("Remind", "Alarm berbunyi: sudah nabung=$sudah, hari dipilih=$dipilih")
            if (!sudah && dipilih) show(c)
        } catch (e: Exception) { Log.e("Remind", "Gagal menampilkan pengingat", e) }
        plan(c, on, h, m, dm) // jadwalkan pengingat berikutnya
    }

    companion object {
        // Bit 0 = Minggu ... bit 6 = Sabtu. 127 = semua hari
        const val ALL_DAYS = 127

        fun show(c: Context) {
            val j = try { JSONObject(c.getSharedPreferences("w", Context.MODE_PRIVATE).getString("j", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("r", "Pengingat nabung", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java).putExtra("fill", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "r") else Notification.Builder(c)
            val nama = j.optString("n")
            val isi = if (nama.isNotEmpty()) "Progres “$nama”: ${j.optInt("p")}%. Ketuk untuk isi hari ini." else "Kamu belum nabung hari ini. Ketuk untuk isi."
            nm.notify(1, b.setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle("Waktunya nabung 🐷").setContentText(isi).setStyle(Notification.BigTextStyle().bigText(isi)).setContentIntent(pi).setAutoCancel(true).build())
        }

        fun plan(c: Context, on: Boolean, h: Int, m: Int, days: Int) {
            c.getSharedPreferences("w", Context.MODE_PRIVATE).edit().putBoolean("on", on).putInt("h", h).putInt("m", m).putInt("dm", days).apply()
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 3, Intent(c, Remind::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
            if (!on || (days and ALL_DAYS) == 0) return
            // Cari waktu berikutnya yang jatuh di hari terpilih
            val t = Calendar.getInstance()
            t.set(Calendar.HOUR_OF_DAY, h); t.set(Calendar.MINUTE, m); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0)
            var n = 0
            while ((t.timeInMillis <= System.currentTimeMillis() || ((days shr (t.get(Calendar.DAY_OF_WEEK) - 1)) and 1) == 0) && n++ < 8) t.add(Calendar.DAY_OF_MONTH, 1)
            // Alarm TEPAT WAKTU & tetap jalan saat Doze. Android 12+ mewajibkan izin alarm presisi
            // (USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM di AndroidManifest); tanpa itu setAlarmClock melempar SecurityException.
            try {
                val bukaApp = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(t.timeInMillis, bukaApp), pi)
                Log.i("Remind", "Alarm tepat waktu dijadwalkan: " + Date(t.timeInMillis))
            } catch (e: Exception) {
                // Izin ditolak: pakai alarm biasa supaya pengingat tetap jalan (bisa telat beberapa menit)
                Log.w("Remind", "Alarm tepat waktu gagal, pakai alarm biasa", e)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.timeInMillis, pi)
            }
        }
    }
}
