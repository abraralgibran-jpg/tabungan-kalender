package com.example.tabung

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TabungWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = draw(c, m, ids)

    companion object {
        fun draw(c: Context, m: AppWidgetManager, ids: IntArray) {
            val j = JSONObject(c.getSharedPreferences("w", Context.MODE_PRIVATE).getString("j", "{}")!!)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val done = j.optBoolean("d") && j.optString("dt") == today
            val has = j.optString("n").isNotEmpty()
            val p = j.optInt("p")
            val r = RemoteViews(c.packageName, R.layout.widget)
            r.setTextViewText(R.id.t, if (has) j.optString("n") else "Kalender Tabung")
            r.setTextViewText(R.id.p, if (has) "$p%" else "-")
            r.setProgressBar(R.id.b, 100, p, false)
            r.setTextViewText(R.id.s, "🔥 ${j.optInt("k")} hari · " + if (done) "✅ sudah nabung" else "⏳ belum nabung")
            r.setOnClickPendingIntent(R.id.root, pi(c, 0, false))
            r.setOnClickPendingIntent(R.id.f, pi(c, 1, true))
            ids.forEach { m.updateAppWidget(it, r) }
        }

        private fun pi(c: Context, code: Int, fill: Boolean) = PendingIntent.getActivity(
            c, code, Intent(c, MainActivity::class.java).putExtra("fill", fill),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
