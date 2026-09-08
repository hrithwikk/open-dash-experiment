package com.example.opendash.util

import android.util.Log
import com.example.opendash.BuildConfig

object DebugLog {
    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching {
            val m = message()
            Log.d(tag, m)
            RideLogFile.append(tag, "D", m)
        }
    }

    fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching {
            val m = message()
            Log.i(tag, m)
            RideLogFile.append(tag, "I", m)
        }
    }

    fun w(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) runCatching {
            val m = message()
            Log.w(tag, m)
            RideLogFile.append(tag, "W", m)
        }
    }

    fun e(tag: String, message: () -> String, error: Throwable? = null) {
        if (BuildConfig.DEBUG) runCatching {
            val m = message()
            if (error == null) Log.e(tag, m) else Log.e(tag, m, error)
            RideLogFile.append(tag, "E", if (error != null) "$m (${error.javaClass.simpleName}: ${error.message})" else m)
        }
    }
}
