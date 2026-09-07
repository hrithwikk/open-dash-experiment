package com.example.opendash.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.opendash.util.DebugLog

class CallController(private val context: Context) {
    private val telecom = context.getSystemService(TelecomManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    fun answer(): Boolean {
        if (!hasPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            @Suppress("MissingPermission")
            telecom?.acceptRingingCall()
            true
        }.onFailure { DebugLog.w(TAG) { "Call answer failed: ${it.javaClass.simpleName}" } }
            .getOrDefault(false)
    }

    fun hangup(): Boolean {
        if (!hasPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            @Suppress("MissingPermission")
            telecom?.endCall() ?: false
        }.onFailure { DebugLog.w(TAG) { "Call end failed: ${it.javaClass.simpleName}" } }
            .getOrDefault(false)
    }

    companion object {
        private const val TAG = "CallController"
    }
}
