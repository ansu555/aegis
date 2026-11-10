package app.tapvault

import android.content.Context

/**
 * App-private settings. Testnet only: the gas key just pays fees, and the
 * wallet's temporary key is deleted from the chain (and from here) when
 * setup finishes. Nothing here can move the wallet's funds.
 */
class Store(context: Context) {
    private val p = context.getSharedPreferences("tapvault", Context.MODE_PRIVATE)

    var walletAccount: String?
        get() = p.getString("wallet", null)
        set(v) = p.edit().putString("wallet", v).apply()

    var walletKey: String?
        get() = p.getString("wallet_key", null)
        set(v) = p.edit().putString("wallet_key", v).apply()

    var gasAccount: String?
        get() = p.getString("gas", null)
        set(v) = p.edit().putString("gas", v).apply()

    var gasKey: String?
        get() = p.getString("gas_key", null)
        set(v) = p.edit().putString("gas_key", v).apply()

    /** 0 = nothing, 1 = accounts, 2 = deployed, 3 = card added, 4 = finalized. */
    var setupStep: Int
        get() = p.getInt("step", 0)
        set(v) = p.edit().putInt("step", v).apply()

    var demoCard: Boolean
        get() = p.getBoolean("demo_card", false)
        set(v) = p.edit().putBoolean("demo_card", v).apply()

    var instantNear: String
        get() = p.getString("instant", "1") ?: "1"
        set(v) = p.edit().putString("instant", v).apply()

    var dailyNear: String
        get() = p.getString("daily", "2") ?: "2"
        set(v) = p.edit().putString("daily", v).apply()

    fun clear() = p.edit().clear().apply()
}
