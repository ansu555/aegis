package app.tapvault

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        nfc = NfcAdapter.getDefaultAdapter(this)
        vm.attach(this)
        setContent { TapvaultApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.attach(this)
        // The reader stays on whenever Tapvault is on screen, so a card placed
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

    /** A short buzz: the card has been read and can be taken away. */
    fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { v?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE)) }
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
