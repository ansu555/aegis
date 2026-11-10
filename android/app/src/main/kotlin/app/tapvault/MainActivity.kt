package app.tapvault

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import app.tapvault.core.CardTransceiver
import java.util.concurrent.CompletableFuture

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private var nfc: NfcAdapter? = null
    private var pendingTag: CompletableFuture<Tag>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        vm.attach(this)
        setContent { TapvaultApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.attach(this)
        // Re-arm the reader if a tap was being awaited when we went to the background.
        if (pendingTag?.isDone == false) startReader()
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    val hasNfc get() = nfc != null
    val nfcEnabled get() = nfc?.isEnabled == true

    private fun startReader() {
        nfc?.enableReaderMode(
            this,
            { tag -> pendingTag?.complete(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
        )
    }

    /**
     * Arm the NFC reader and return a future that completes with the next
     * card. The reader stays on until [stopReader]: switching it off while
     * the card is still signing would drop the link.
     */
    fun awaitTag(): CompletableFuture<Tag> {
        val f = CompletableFuture<Tag>()
        pendingTag = f
        runOnUiThread { startReader() }
        return f
    }

    fun stopReader() {
        pendingTag = null
        runOnUiThread { nfc?.disableReaderMode(this) }
    }

    fun cancelTag() {
        pendingTag?.cancel(true)
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
