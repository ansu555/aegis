package app.tapvault

import android.content.Context

/**
 * App-private settings. Testnet only: the gas key just pays fees, and the
 * wallet's temporary key is deleted from the chain (and from here) when
 * setup finishes. Nothing here can move the wallet's funds. Writes are
 * synchronous so a crash right after a setup step can't lose what it created.
 */
class Store(context: Context) {
    private val p = context.getSharedPreferences("tapvault", Context.MODE_PRIVATE)

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
