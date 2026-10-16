package me.parham1995.passwords.unlock

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * The platform prompt, bound to [cipher] so that only a strong biometric --
 * not a PIN, not a weak face unlock -- releases the key.
 *
 * Returns the authenticated cipher, or null when cancelled or failed.
 */
suspend fun authenticate(
    context: Context,
    title: String,
    subtitle: String,
    cipher: Cipher,
): Cipher? =
    suspendCancellableCoroutine { continuation ->
        val cancel = CancellationSignal()
        continuation.invokeOnCancellation { cancel.cancel() }
        BiometricPrompt
            .Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton("Use passphrase", context.mainExecutor) { _, _ ->
                if (continuation.isActive) continuation.resume(null)
            }.build()
            .authenticate(
                BiometricPrompt.CryptoObject(cipher),
                cancel,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
    }
