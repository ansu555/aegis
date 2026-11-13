package app.tapvault

import android.content.Context

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

    /** How the enrolled card signs ([app.tapvault.core.CardRecipe], encoded). */
    var cardRecipe: String?
        get() = p.getString("card_recipe", null)
        set(v) { p.edit().putString("card_recipe", v).commit() }

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
 * contract, fee account, enrolled card, [Store] file and phone key (its own
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
    }
}
