package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskflow.app.data.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delivered by the system after [AppWidgetManager.requestPinAppWidget]. On success the
 * intent carries the new widget ids; we mark the widget as added so the in-app guide
 * never shows again, and force an initial refresh.
 */
class WidgetPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        val singleId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val added = (ids != null && ids.isNotEmpty()) || singleId != AppWidgetManager.INVALID_APPWIDGET_ID
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (added) {
                    UserPreferences.get(context).setWidgetAdded(true)
                }
                WidgetHelper.refresh(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
