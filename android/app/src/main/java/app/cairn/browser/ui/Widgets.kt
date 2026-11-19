package app.cairn.browser.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.cairn.browser.R

/** Home-screen widgets: a search pill (4×1) and search + quick actions (4×2). Each tap opens Cairn exactly where it should. */
abstract class CairnWidget(private val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val v = RemoteViews(ctx.packageName, layout)
            v.setOnClickPendingIntent(R.id.wg_bar, pi(ctx, BrowserActivity.ACTION_FOCUS_SEARCH, 1))
            v.setOnClickPendingIntent(R.id.wg_mic, pi(ctx, BrowserActivity.ACTION_VOICE, 2))
            v.setOnClickPendingIntent(R.id.wg_ask, pi(ctx, BrowserActivity.ACTION_ASSISTANT, 3))
            if (layout == R.layout.widget_actions) {
                v.setOnClickPendingIntent(R.id.wg_tab, pi(ctx, BrowserActivity.ACTION_NEW_TAB, 4))
                v.setOnClickPendingIntent(R.id.wg_private, pi(ctx, BrowserActivity.ACTION_PRIVATE_TAB, 5))
                v.setOnClickPendingIntent(R.id.wg_lens, pi(ctx, BrowserActivity.ACTION_ASK_PHOTO, 6))
            }
            mgr.updateAppWidget(id, v)
        }
    }

    private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, Intent(ctx, BrowserActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        /** Asks the launcher to place a widget (Android 8+ launchers that support pinning). Returns false if unsupported. */
        fun pin(ctx: Context, big: Boolean): Boolean {
            val m = AppWidgetManager.getInstance(ctx)
            if (!m.isRequestPinAppWidgetSupported) return false
            return m.requestPinAppWidget(ComponentName(ctx, if (big) ActionsWidget::class.java else SearchWidget::class.java), null, null)
        }
    }
}

class SearchWidget : CairnWidget(R.layout.widget_search)
class ActionsWidget : CairnWidget(R.layout.widget_actions)
