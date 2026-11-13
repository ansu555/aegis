package app.tapvault

import android.content.Context
import app.tapvault.core.CardBook
import app.tapvault.core.Receivers

/**
 * App-private settings. Testnet only: the gas key just pays fees, and the
 * wallet's temporary key is deleted from the chain (and from here) when
 * setup finishes. Nothing here can move the wallet's funds. Writes are
 * synchronous so a crash right after a setup step can't lose what it created.
 */
class Store(context: Context, file: String = WalletIndex.prefsFile(WalletIndex.FIRST)) {
    private val p = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    var walletAccount: String?
        get() = p.getString("wallet", null)
        set(v) { p.edit().putString("wallet", v).commit() }

    var walletKey: String?
        get() = p.getString("wallet_key", null)
        set(v) { p.edit().putString("wallet_key", v).commit() }

    var gasAccount: String?
        get() = p.getString("gas", null)
        set(v) { p.edit().putString("gas", v).commit() }

    var gasKey: String?
        get() = p.getString("gas_key", null)
        set(v) { p.edit().putString("gas_key", v).commit() }

    /** 0 = nothing, 1 = accounts, 2 = deployed, 3 = card added, 4 = finalized. */
    var setupStep: Int
        get() = p.getInt("step", 0)
        set(v) { p.edit().putInt("step", v).commit() }

    /** Set by builds that had a simulated demo card; such a wallet can't be used with a real card. */
    val legacyDemoWallet: Boolean
        get() = p.getBoolean("demo_card", false) && setupStep > 0

    /** Names and keys picked for the accounts of a setup in progress ([app.tapvault.core.Onboarding.Plan], encoded). */
    var setupPlan: String?
        get() = p.getString("setup_plan", null)
        set(v) { p.edit().putString("setup_plan", v).commit() }

    /** A card read and signed during setup but not yet added on-chain ([PendingCard], encoded). */
    var pendingCard: String?
        get() = p.getString("pending_card", null)
        set(v) { p.edit().putString("pending_card", v).commit() }

    /**
     * The wallet's cards known to this phone (how each signs, network, last
     * four digits), keyed by card key hash. A wallet made before several cards
     * per wallet stored one card under `card_recipe`, `card_network` and
     * `card_last4`; it is read as entry 1 (its key hash is filled in from the
     * chain, [app.tapvault.core.CardBook.bind]) and the old keys are dropped on
     * the first write.
     */
    var cards: CardBook
        get() = CardBook.load(p.getString("cards", null), p.getString("card_recipe", null), p.getString("card_network", null), p.getString("card_last4", null))
        set(v) { p.edit().putString("cards", v.encode()).remove("card_recipe").remove("card_network").remove("card_last4").commit() }

    /** Hold-to-sign: seconds a card must stay on the phone before it signs (0 = off). An app-side pause, not a card feature. */
    var holdSeconds: Int
        get() = p.getInt("hold_seconds", 5)
        set(v) { p.edit().putInt("hold_seconds", v).commit() }

    /** Receivers this phone has paid from this wallet, with when first ([app.tapvault.core.Receivers]); for the review warnings. */
    var receivers: Receivers
        get() = Receivers.decode(p.getString("receivers", null))
        set(v) { p.edit().putString("receivers", v.encode()).commit() }

    /** The last wallet integrity check ([app.tapvault.core.Integrity.Report], encoded). */
    var integrity: String?
        get() = p.getString("integrity", null)
        set(v) { p.edit().putString("integrity", v).commit() }

    /** What the watchtower saw last ([app.tapvault.core.Watch.Memory], encoded). */
    var watchMemory: String?
        get() = p.getString("watch", null)
        set(v) { p.edit().putString("watch", v).commit() }

    // ---- phase 3 ----

    /** The wallet's security key, as a salted hash ([app.tapvault.core.SecurityKey.Record], encoded); null until set. */
    var securityKey: String?
        get() = p.getString("security_key", null)
        set(v) { p.edit().putString("security_key", v).commit() }

    /** Wrong security keys in a row, and when the last one was typed (seconds), for the pause. */
    var keyFailures: Int
        get() = p.getInt("key_failures", 0)
        set(v) { p.edit().putInt("key_failures", v).commit() }

    var keyLastFail: Long
        get() = p.getLong("key_last_fail", 0)
        set(v) { p.edit().putLong("key_last_fail", v).commit() }

    /** Setup option: read and add a second (backup) card before the wallet is locked. */
    var wantBackup: Boolean
        get() = p.getBoolean("want_backup", false)
        set(v) { p.edit().putBoolean("want_backup", v).commit() }

    /** The backup card read during setup but not yet added ([PendingCard], encoded). */
    var pendingCard2: String?
        get() = p.getString("pending_card2", null)
        set(v) { p.edit().putString("pending_card2", v).commit() }

    /** Setup option: create the phone key for fingerprint only (no screen-lock fallback; Android 11+). */
    var strictKey: Boolean
        get() = p.getBoolean("strict_key", false)
        set(v) { p.edit().putBoolean("strict_key", v).commit() }

    /** This phone joins an existing wallet: "recover" (new phone for a lost one) or "guardian". Null for a wallet made here. */
    var joinMode: String?
        get() = p.getString("join_mode", null)
        set(v) { p.edit().putString("join_mode", v).commit() }

    /** A guardian phone's code, shown until the owner has added it. */
    var guardianCode: String?
        get() = p.getString("guardian_code", null)
        set(v) { p.edit().putString("guardian_code", v).commit() }

    /** The Keystore alias of this wallet's phone key when it isn't the default (after a switch to a strict key). */
    var phoneAlias: String?
        get() = p.getString("phone_alias", null)
        set(v) { p.edit().putString("phone_alias", v).commit() }

    /** A new (strict) phone key waiting for its settings change to execute. */
    var pendingAlias: String?
        get() = p.getString("pending_alias", null)
        set(v) { p.edit().putString("pending_alias", v).commit() }

    /** Setup: false = the wallet gets no spending limit, so every payment waits the transfer delay. */
    var limitsOn: Boolean
        get() = p.getBoolean("limits_on", true)
        set(v) { p.edit().putBoolean("limits_on", v).commit() }

    var instantNear: String
        get() = p.getString("instant", "1") ?: "1"
        set(v) { p.edit().putString("instant", v).commit() }

    var dailyNear: String
        get() = p.getString("daily", "2") ?: "2"
        set(v) { p.edit().putString("daily", v).commit() }

    fun clear() { p.edit().clear().commit() }
}

/**
 * The wallets on this phone. Each one is a separate NEAR account with its own
 * contract, fee account, cards, [Store] file and phone key (its own
 * Keystore alias). Nothing is shared between them, so forgetting one leaves
 * the others untouched.
 *
 * The first wallet ([FIRST]) uses the original file `tapvault` and alias
 * `tapvault_phone_p256`, so a wallet made before multi-wallet support simply
 * becomes wallet 1.
 */
class WalletIndex(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("tapvault_wallets", Context.MODE_PRIVATE)

    init {
        if (ids.isEmpty()) register(FIRST)
    }

    val ids: List<String>
        get() = p.getString("ids", "").orEmpty().split(',').filter { it.isNotEmpty() }

    /** The wallet on screen; always one of [ids]. */
    var active: String
        get() = p.getString("active", null)?.takeIf { it in ids } ?: ids.first()
        set(v) { p.edit().putString("active", v).commit() }

    fun name(id: String): String = p.getString("name_$id", null) ?: "Wallet"

    fun rename(id: String, name: String) { p.edit().putString("name_$id", name).commit() }

    /** When the app last went to the background (ms), for the app lock; 0 if never recorded. */
    var backgroundAt: Long
        get() = p.getLong("background_at", 0)
        set(v) { p.edit().putLong("background_at", v).commit() }

    /** Saved receivers, shared by every wallet on this phone ([app.tapvault.core.AddressBook], encoded). */
    var addressBook: app.tapvault.core.AddressBook
        get() = app.tapvault.core.AddressBook.decode(p.getString("address_book", null))
        set(v) { p.edit().putString("address_book", v.encode()).commit() }

    /** Whether the user turned on watchtower alerts. */
    var alertsOn: Boolean
        get() = p.getBoolean("alerts_on", false)
        set(v) { p.edit().putBoolean("alerts_on", v).commit() }

    fun store(id: String) = Store(ctx, prefsFile(id))

    /** Register a new, empty wallet and return its id. */
    fun add(): String {
        var id: String
        do {
            id = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        } while (id in ids || id == FIRST)
        register(id)
        return id
    }

    /** Drop [id] from the list (its Store and key are the caller's to clear). Keeps at least one wallet. */
    fun remove(id: String) {
        val rest = ids - id
        p.edit().remove("name_$id").putString("ids", rest.joinToString(",")).commit()
        if (rest.isEmpty()) {
            p.edit().putInt("next", 1).commit()
            register(FIRST)
        }
    }

    private fun register(id: String) {
        val n = p.getInt("next", 1)
        p.edit().putString("ids", (ids + id).joinToString(",")).putString("name_$id", "Wallet $n").putInt("next", n + 1).commit()
    }

    companion object {
        const val FIRST = "0"

        fun prefsFile(id: String) = if (id == FIRST) "tapvault" else "tapvault_w_$id"

        fun keyAlias(id: String) = if (id == FIRST) "tapvault_phone_p256" else "tapvault_phone_p256_$id"

        /** The alias of [id]'s phone key now: [Store.phoneAlias] after a key switch, else [keyAlias]. */
        fun aliasOf(s: Store, id: String) = s.phoneAlias ?: keyAlias(id)
    }
}
