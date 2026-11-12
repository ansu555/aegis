package app.tapvault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.tapvault.core.P256
import app.tapvault.core.P256Sig
import app.tapvault.core.P256Signer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The phone signer: a P-256 key generated inside the Android Keystore's
 * hardware (TEE) that can only sign after a fresh biometric or screen-lock
 * check, every single time.
 *
 * [sign] blocks its (background) thread while the system prompt is shown.
 */
class KeystoreSigner(private val activity: () -> FragmentActivity?) : P256Signer {
    /** What the prompt says the user is approving. Set it before each operation. */
    @Volatile var reason: String = "Approve with Tapvault"

    private val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override val publicKey: ByteArray
        get() = P256.compress(ks.getCertificate(ALIAS).publicKey as ECPublicKey)

    val hardwareLabel: String
        get() = runCatching {
            val key = ks.getKey(ALIAS, null) as PrivateKey
            val info = java.security.KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            when {
                Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox secure chip"
                Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted Execution Environment"
                @Suppress("DEPRECATION") info.isInsideSecureHardware -> "secure hardware"
                else -> "software (no secure hardware!)"
            }
        }.getOrDefault("unknown")

    override fun sign(message: ByteArray): P256Sig {
        val sig = try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(ALIAS, null) as PrivateKey) }
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            throw IllegalStateException("This phone's Tapvault key no longer works (the screen lock was removed or changed). The wallet needs its recovery path; on testnet, forget it in Settings and create a new one.")
        } catch (e: NullPointerException) {
            throw IllegalStateException("This phone's Tapvault key is missing. On testnet, forget the wallet in Settings and create a new one.")
        }
        // A prompt shown while the app is in the background never appears, so wait until it's on screen.
        val act = awaitForeground() ?: throw IllegalStateException("Open Tapvault to approve with your fingerprint or screen lock.")
        val result = CompletableFuture<Signature>()
        act.runOnUiThread {
            try {
                val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        val s = r.cryptoObject?.signature
                        if (s != null) result.complete(s) else result.completeExceptionally(IllegalStateException("The phone didn't unlock its key. Try again."))
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        result.completeExceptionally(Cancelled("Approval cancelled: $msg"))
                    }
                })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Tapvault")
                    .setSubtitle(reason)
                    .setDescription("Your phone key signs only what you approve here.")
                    .apply {
                        if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                        else setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText("Cancel")
                    }
                    .setConfirmationRequired(true)
                    .build()
                prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        val authed = try {
            result.get(3, TimeUnit.MINUTES)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: java.util.concurrent.TimeoutException) {
            throw Cancelled("Approval timed out")
        }
        authed.update(message)
        return P256Sig(P256.derToRaw(authed.sign()))
    }

    /** The current activity once it is resumed (up to 3 minutes), or null. */
    private fun awaitForeground(): FragmentActivity? {
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until) {
            val a = activity()
            if (a != null && !a.isFinishing && a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return a
            Thread.sleep(250)
        }
        return null
    }

    class Cancelled(msg: String) : Exception(msg)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "tapvault_phone_p256"

        fun exists(): Boolean = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(ALIAS)

        fun delete() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)

        /**
         * Create the key in the phone's hardware keystore (TEE). Requires a
         * screen lock; on Android 9/10 also an enrolled fingerprint, because
         * those versions can't unlock a per-use key with the PIN.
         *
         * StrongBox is not used: on several phones a StrongBox key is created
         * fine and then fails or stalls when it signs, which would strand the
         * wallet. The TEE is still isolated hardware.
         */
        fun create(context: android.content.Context) {
            val bm = androidx.biometric.BiometricManager.from(context)
            if (Build.VERSION.SDK_INT < 30 && bm.canAuthenticate(BIOMETRIC_STRONG) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                throw IllegalStateException("On this Android version Tapvault needs a fingerprint. Add one in Settings → Security, then try again.")
            }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                // Adding a fingerprint later must not destroy the wallet's phone key.
                .setInvalidatedByBiometricEnrollment(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
    }
}
