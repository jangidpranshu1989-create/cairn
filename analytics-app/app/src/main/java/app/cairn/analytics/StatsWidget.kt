package app.cairn.analytics

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx); refreshNow(ctx); schedule(ctx)
    }
    override fun onEnabled(ctx: Context) = schedule(ctx)
    override fun onDisabled(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC) }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) { render(ctx, loading = true); refreshNow(ctx) }
    }

    companion object {
        const val ACTION_REFRESH = "app.cairn.analytics.REFRESH"
        private const val PERIODIC = "stats-periodic"; private const val ONCE = "stats-once"
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Android allows background work every 15 minutes at most; tap the widget's refresh icon for a live number. */
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        }
        fun refreshNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build())
        }

        fun render(ctx: Context, loading: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java)); if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_stats)
            val nf = NumberFormat.getIntegerInstance(Locale("en", "IN"))
            val s = Stats.cached(ctx)
            if (Stats.key(ctx).isBlank()) {
                v.setTextViewText(R.id.active, "–"); v.setTextViewText(R.id.today, "Not signed in"); v.setTextViewText(R.id.fresh, "")
                v.setTextViewText(R.id.web, ""); v.setTextViewText(R.id.goal, "Open Cairn Analytics and enter your key"); v.setProgressBar(R.id.progress, 1000, 0, false)
                v.setTextViewText(R.id.updated, "")
            } else if (s != null) {
                v.setTextViewText(R.id.active, nf.format(s.active))
                v.setTextViewText(R.id.title, "Live now · ${nf.format(s.apps)} app · ${nf.format(s.web)} web")
                v.setTextViewText(R.id.today, "Today ${nf.format(s.today)} users")
                v.setTextViewText(R.id.fresh, "New ${nf.format(s.fresh)} installs")
                v.setTextViewText(R.id.web, "Website ${nf.format(s.webToday)} visitors")
                val goal = s.goal.coerceAtLeast(1)
                v.setTextViewText(R.id.goal, "${nf.format(s.total)} / ${nf.format(goal)} users · ${String.format(Locale.US, "%.1f", s.total * 100.0 / goal)}%")
                v.setProgressBar(R.id.progress, 1000, (s.total * 1000L / goal).toInt().coerceIn(if (s.total > 0) 5 else 0, 1000), false)
                v.setTextViewText(R.id.updated, if (loading) "Updating" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.at)))
            } else v.setTextViewText(R.id.updated, if (loading) "Updating" else "Waiting for server")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(ctx, 1,
                Intent(ctx, StatsWidget::class.java).setAction(ACTION_REFRESH), flags))
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 2,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            mgr.updateAppWidget(ids, v)
        }
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ok = Stats.fetch(applicationContext) != null
        StatsWidget.render(applicationContext)
        if (ok || Stats.key(applicationContext).isBlank()) Result.success() else Result.retry()
    }
}
