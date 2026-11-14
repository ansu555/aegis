package app.tapvault

import android.content.Context
import app.tapvault.core.CardBook
import app.tapvault.core.Network
import app.tapvault.core.Receivers

/**
 * App-private settings of one wallet (its network is in [WalletIndex]). The
 * gas key just pays fees, and the wallet's temporary key is deleted from
 * the chain (and from here) when setup finishes. The exception is a seed-phrase wallet: until it is
 * protected with a card, its key and phrase (encrypted, [SecretBox]) do
 * control its funds. Writes are synchronous so a crash right after a setup
 * step can't lose what it created.
 */
class Store(context: Context, file: String = WalletIndex.prefsFile(WalletIndex.FIRST)) {
    private val p = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    var walletAccount: String?
        get() = p.getString("wallet", null)
        set(v) { p.edit().putString("wallet", v).commit() }

    /** The wallet account's own key: during setup, and for as long as a seed-phrase wallet isn't protected. Encrypted ([SecretBox]). */
    var walletKey: String?
        get() = p.getString("wallet_key", null)?.let { SecretBox.open(it) }
        set(v) { p.edit().putString("wallet_key", v?.let { SecretBox.seal(it) }).commit() }

    /**
     * A seed-phrase wallet: a plain NEAR account controlled by [walletKey]
     * (setup step 1, no contract), until "Protect with bank card" finishes
     * setup. Cleared when it does.
     */
    var seedWallet: Boolean
        get() = p.getBoolean("seed_wallet", false)
        set(v) { p.edit().putBoolean("seed_wallet", v).commit() }

    /** The recovery phrase of a seed-phrase wallet (null for one imported from a private key). Encrypted ([SecretBox]). */
    var seedPhrase: String?
        get() = p.getString("seed_phrase", null)?.let { SecretBox.open(it) }
        set(v) { p.edit().putString("seed_phrase", v?.let { SecretBox.seal(it) }).commit() }

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
        get() = p.getString("setup_plan", null)?.let { SecretBox.open(it) }
        set(v) { p.edit().putString("setup_plan", v?.let { SecretBox.seal(it) }).commit() }

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

    /**
     * Setup choice from "Create wallet": true = phone + card from the start;
     * false = a phone-only wallet (contract v0.3, threshold 1) whose card is
     * added later from Security. Wallets set up before this choice existed had a card.
     */
    var withCard: Boolean
        get() = p.getBoolean("with_card", true)
        set(v) { p.edit().putBoolean("with_card", v).commit() }

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

    /** Which tokens this wallet shows ([app.tapvault.core.TokenPrefs], encoded): added and hidden ones. */
    var tokenPrefs: app.tapvault.core.TokenPrefs
        get() = app.tapvault.core.TokenPrefs.decode(p.getString("token_prefs", null))
        set(v) { p.edit().putString("token_prefs", v.encode()).commit() }

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
 *
 * Each wallet belongs to one [Network] for life, stored here as `net_<id>`
 * when it is registered; an entry without one is from before networks
 * existed and is a testnet wallet. [network] is the Settings → Network
 * switch: it decides which wallets are shown ([idsOn]), never which network
 * a wallet talks to. There is always at least one wallet (maybe an empty
 * one, shown as Welcome) on the network shown.
 */
class WalletIndex(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("tapvault_wallets", Context.MODE_PRIVATE)

    init {
        val existing = p.contains("ids")
        // Every wallet registered before networks existed is a testnet wallet: say so explicitly.
        ids.filter { !p.contains("net_$it") }.forEach { p.edit().putString("net_$it", Network.TESTNET.id).commit() }
        // Existing installs stay on testnet; a new install starts on mainnet once the build allows it.
        if (!p.contains("network")) p.edit().putString("network", if (existing || !MAINNET_ENABLED) Network.TESTNET.id else Network.MAINNET.id).commit()
        // A build without mainnet never shows it (its mainnet wallets, if any, stay stored, untouched).
        if (!MAINNET_ENABLED && network.isMainnet) p.edit().putString("network", Network.TESTNET.id).commit()
        ensureOn(network)
    }

    /** Every wallet on this phone, on every network. */
    val ids: List<String>
        get() = p.getString("ids", "").orEmpty().split(',').filter { it.isNotEmpty() }

    /** The network [id] lives on (set when it was registered, never changed). */
    fun networkOf(id: String): Network = Network.of(p.getString("net_$id", null))

    /** The wallets on [n], in the order they were added. */
    fun idsOn(n: Network): List<String> = ids.filter { networkOf(it) === n }

    /** The network shown (Settings → Network). Setting it doesn't touch any wallet. */
    var network: Network
        get() = Network.of(p.getString("network", null))
        set(v) {
            require(v.isTestnet || MAINNET_ENABLED) { "mainnet is off in this build" }
            p.edit().putString("network", v.id).commit()
            ensureOn(v)
        }

    /** The wallet on screen: the last one used on the network shown; always one of [idsOn] it. */
    var active: String
        get() {
            val on = idsOn(network)
            val last = p.getString("active_${network.id}", null) ?: p.getString("active", null).takeIf { network.isTestnet }
            return last?.takeIf { it in on } ?: on.first()
        }
        set(v) { p.edit().putString("active_${networkOf(v).id}", v).commit() }

    fun name(id: String): String = p.getString("name_$id", null) ?: "Wallet"

    fun rename(id: String, name: String) { p.edit().putString("name_$id", name).commit() }

    /** When the app last went to the background (ms), for the app lock; 0 if never recorded. */
    var backgroundAt: Long
        get() = p.getLong("background_at", 0)
        set(v) { p.edit().putLong("background_at", v).commit() }

    /**
     * Saved receivers on [n], shared by that network's wallets on this phone
     * ([app.tapvault.core.AddressBook], encoded). The book from before
     * networks existed is the testnet one.
     */
    fun addressBook(n: Network): app.tapvault.core.AddressBook =
        app.tapvault.core.AddressBook.decode(p.getString(bookKey(n), null))

    fun setAddressBook(n: Network, b: app.tapvault.core.AddressBook) { p.edit().putString(bookKey(n), b.encode()).commit() }

    private fun bookKey(n: Network) = if (n.isTestnet) "address_book" else "address_book_${n.id}"

    /** Whether the user turned on watchtower alerts. */
    var alertsOn: Boolean
        get() = p.getBoolean("alerts_on", false)
        set(v) { p.edit().putBoolean("alerts_on", v).commit() }

    fun store(id: String) = Store(ctx, prefsFile(id))

    /** Register a new, empty wallet on [n] (the network shown, by default) and return its id. */
    fun add(n: Network = network): String {
        var id: String
        do {
            id = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        } while (id in ids || id == FIRST)
        register(id, n)
        return id
    }

    /**
     * Drop [id] from the list (its Store and key are the caller's to clear).
     * Keeps at least one wallet on the network shown.
     */
    fun remove(id: String) {
        val rest = ids - id
        p.edit().remove("name_$id").remove("net_$id").putString("ids", rest.joinToString(",")).commit()
        if (rest.isEmpty()) p.edit().putInt("next", 1).commit()
        ensureOn(network)
    }

    /** An empty wallet on [n] if it has none, so the screen always has one to show (Welcome). */
    private fun ensureOn(n: Network) {
        if (idsOn(n).isNotEmpty()) return
        if (FIRST !in ids) register(FIRST, n) else add(n)
    }

    private fun register(id: String, net: Network) {
        val n = p.getInt("next", 1)
        p.edit().putString("ids", (ids + id).joinToString(",")).putString("name_$id", "Wallet $n").putString("net_$id", net.id).putInt("next", n + 1).commit()
    }

    companion object {
        const val FIRST = "0"

        /** Whether this build may show mainnet (`tapvault.mainnet=true` in local.properties); off until real-card testing and a contract review. */
        val MAINNET_ENABLED get() = BuildConfig.MAINNET_ENABLED

        fun prefsFile(id: String) = if (id == FIRST) "tapvault" else "tapvault_w_$id"

        fun keyAlias(id: String) = if (id == FIRST) "tapvault_phone_p256" else "tapvault_phone_p256_$id"

        /** The alias of [id]'s phone key now: [Store.phoneAlias] after a key switch, else [keyAlias]. */
        fun aliasOf(s: Store, id: String) = s.phoneAlias ?: keyAlias(id)
    }
}
