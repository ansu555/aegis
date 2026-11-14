package app.tapvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import app.tapvault.core.CardTransceiver

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars with light icons: the app is dark in every system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // TEMPORARY (demo screenshots): FLAG_SECURE off for the whole app. Seed phrase and key
        // screens still set it themselves (SeedScreens.SecureWindow). Turn back on before release:
        // window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        nfc = NfcAdapter.getDefaultAdapter(this)
        vm.attach(this)
        setContent { NetworkFrame(vm) }
        openAlert(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openAlert(intent)
    }

    /** A watchtower notification was tapped. */
    private fun openAlert(i: Intent?) {
        val target = i?.getStringExtra(Watchtower.EXTRA_TARGET) ?: return
        vm.openFromAlert(i.getStringExtra(Watchtower.EXTRA_WALLET), target)
        i.removeExtra(Watchtower.EXTRA_TARGET)
    }

    override fun onStart() {
        super.onStart()
        vm.lockIfAway()
    }

    override fun onStop() {
        super.onStop()
        // Not when the activity is only recreated, nor while a card / prompt flow runs in the background.
        if (!isChangingConfigurations) vm.wentToBackground()
    }

    /** Android 13+: ask once for the alerts' notifications. */
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.attach(this)
        // The reader stays on whenever Aegis is on screen, so a card placed
        // early or left on the phone never reaches Android's own tag handling
        // ("No supported app for this NFC tag"). Taps nobody is waiting for are ignored.
        runCatching {
            nfc?.enableReaderMode(
                this,
                { tag -> vm.onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
            )
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfc?.disableReaderMode(this) }
    }

    val hasNfc get() = nfc != null
    val nfcEnabled get() = nfc?.isEnabled == true

    /** A short buzz: the card has been read and can be taken away ([ms] = 20 for the hold timer's tick). */
    fun vibrate(ms: Long = 90) {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}

/** ISO 14443-4 (ISO-DEP) link to a contactless card. */
class IsoDepTransceiver(tag: Tag) : CardTransceiver, AutoCloseable {
    private val iso = IsoDep.get(tag) ?: throw IllegalArgumentException(
        "This card doesn't speak ISO-DEP. Transit cards, stickers and key fobs can't sign; use a contactless bank card."
    )

    init {
        iso.connect()
        iso.timeout = 5_000 // RSA signing on the card can take a moment
    }

    override fun transceive(apdu: ByteArray): ByteArray = iso.transceive(apdu)

    override fun close() = runCatching { iso.close() }.let { }
}
