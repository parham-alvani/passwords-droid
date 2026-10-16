package me.parham1995.passwords

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.HiltAndroidApp
import me.parham1995.passwords.data.PassphraseCache
import javax.inject.Inject

@HiltAndroidApp
class PasswordsApplication : Application() {
    @Inject
    lateinit var passphrases: PassphraseCache

    override fun onCreate() {
        super.onCreate()
        // A locked phone forgets every typed passphrase. Sealed ones stay
        // sealed; they need a fingerprint to open anyway.
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) = passphrases.forgetAll()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            RECEIVER_NOT_EXPORTED,
        )
    }
}
