package me.parham1995.passwords.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies a secret so that the keyboard's clipboard strip and the system
 * preview never show it, and takes it back after `seconds` -- but only if it
 * is still what is on the clipboard, so something copied since is left alone.
 *
 * The timer runs in the process's scope rather than a screen's, so leaving
 * the screen does not leave the password behind.
 */
object SecretClipboard {
    private var clearing: Job? = null

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
        if (seconds <= 0) return
        clearing =
            ProcessLifecycleOwner.get().lifecycleScope.launch {
                delay(seconds * MILLIS)
                val current =
                    manager.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?.toString()
                if (current == value) manager.clearPrimaryClip()
            }
    }

    private const val MILLIS = 1000L
}
