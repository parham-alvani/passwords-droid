package me.parham1995.passwords

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import me.parham1995.passwords.data.PassphraseCache
import javax.inject.Inject

@HiltAndroidApp
class PasswordsApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var passphrases: PassphraseCache

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** WorkManager starts on demand with Hilt's factory; its default initializer is removed in the manifest. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

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
