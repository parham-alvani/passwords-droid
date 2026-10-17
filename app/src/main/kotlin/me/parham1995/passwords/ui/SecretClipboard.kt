package me.parham1995.passwords.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies a secret so that the keyboard's clipboard strip and the system
 * preview never show it, and takes it back after `seconds`.
 *
 * Since Android 10 an app in the background cannot read the clipboard, so the
 * clear cannot check what is on it; it clears without reading. What keeps a
 * later copy from this app safe is a generation token: each copy bumps it, and
 * a clear meant for an older copy does nothing.
 *
 * The clear is scheduled twice: a coroutine in the process's scope, which is
 * on time while the process lives, and an alarm, which survives the process
 * being killed. Whichever comes first clears; the other finds nothing to do.
 */
object SecretClipboard {
    private var clearing: Job? = null

    /** The token of the copy waiting to be cleared; null when none is, or the process is new. */
    @Volatile
    private var pending: Long? = null

    fun copy(
        context: Context,
        label: String,
        value: String,
        seconds: Int,
    ) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip =
            ClipData.newPlainText(label, value).apply {
                description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
        manager.setPrimaryClip(clip)
        clearing?.cancel()
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (seconds <= 0) {
            // Something copied to keep: an older copy's clear must not take it.
            pending = null
            alarms.cancel(expiry(context, 0L))
            return
        }
        val token = SystemClock.elapsedRealtimeNanos()
        pending = token
        // Inexact is fine: it is the fallback for a killed process, not the timer.
        alarms.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + seconds * MILLIS,
            expiry(context, token),
        )
        clearing =
            ProcessLifecycleOwner.get().lifecycleScope.launch {
                delay(seconds * MILLIS)
                expire(context, token)
            }
    }

    /**
     * Clears the clipboard if [token] is still the latest copy. A new process
     * knows of no later copy -- one would have replaced the alarm -- so it clears.
     */
    internal fun expire(
        context: Context,
        token: Long,
    ) {
        val current = pending
        if (current != null && current != token) return
        pending = null
        clearing?.cancel()
        context.getSystemService(AlarmManager::class.java).cancel(expiry(context, token))
        context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
    }

    /** One PendingIntent for the whole app, so a new copy's alarm replaces the old one. */
    private fun expiry(
        context: Context,
        token: Long,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ClipboardExpiry::class.java).putExtra(ClipboardExpiry.EXTRA_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val MILLIS = 1000L
}

/** The alarm's end of [SecretClipboard]: not exported, only this app's alarm reaches it. */
class ClipboardExpiry : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        SecretClipboard.expire(context, intent.getLongExtra(EXTRA_TOKEN, 0L))
    }

    internal companion object {
        const val EXTRA_TOKEN = "token"
    }
}
