package app.tapvault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
 * The phone signer: a P-256 key generated inside Android Keystore (StrongBox
 * when the phone has one) that can only sign after a fresh biometric or
 * screen-lock check, every single time.
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
        val act = activity() ?: throw IllegalStateException("app is not in the foreground")
        val sig = try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(ALIAS, null) as PrivateKey) }
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            throw IllegalStateException("The phone key was invalidated (biometrics changed). Recover the wallet with your cards.")
        }
        val result = CompletableFuture<Signature>()
        act.runOnUiThread {
            val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    result.complete(r.cryptoObject!!.signature!!)
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
        }
        val authed = result.get(3, TimeUnit.MINUTES)
        authed.update(message)
        return P256Sig(P256.derToRaw(authed.sign()))
    }

    class Cancelled(msg: String) : Exception(msg)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "tapvault_phone_p256"

        fun exists(): Boolean = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(ALIAS)

        fun delete() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)

        /** Create the key, preferring StrongBox. Requires a screen lock to be set up. */
        fun create() {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
                    }
                    setIsStrongBoxBacked(strongBox)
                }
                .build()

            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            try {
                gen.initialize(spec(strongBox = true))
                gen.generateKeyPair()
            } catch (e: StrongBoxUnavailableException) {
                gen.initialize(spec(strongBox = false))
                gen.generateKeyPair()
            } catch (e: java.security.ProviderException) {
                // Some phones throw a ProviderException instead when StrongBox is missing.
                gen.initialize(spec(strongBox = false))
                gen.generateKeyPair()
            }
        }
    }
}
