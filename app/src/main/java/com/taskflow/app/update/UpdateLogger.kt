package com.taskflow.app.update

import android.util.Log

/** Centralized update-module logging. Toggle [ENABLED] to silence in release. */
object UpdateLogger {
    private const val TAG = "TaskFlow/Update"
    var ENABLED = true

    fun i(msg: String) { if (ENABLED) Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { if (ENABLED) Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) { if (ENABLED) Log.e(TAG, msg, t) }
}
