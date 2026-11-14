package app.tapvault.core

/**
 * Wallet integrity check (plan 5.7), the counterpart of a hardware wallet's
 * firmware check: the wallet account runs the code this app ships, has no
 * access keys (so nobody can replace that code or sign as the account), the
 * phone's key is one of its signers, and setup is over. Every value comes
 * from the chain; the app only compares.
 */
object Integrity {
    /** The build wallets ran before 12 Nov (it refuses Visa online fDDA signatures; AGENTS.md invariant 4). */
    const val PREVIOUS_BUILD = "GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow"

    /** The v0.1 build shipped from 12 Nov to 14 Nov (before contract v0.3): official, no possession proofs or upgrade path. */
    const val V01_BUILD = "5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf"

    /** The v0.3 build shipped on 14 Nov before contract v0.4: official, phone-only wallets, no inheritance. */
    const val V03_BUILD = "DdhpDh3c2gbVJK4iFQLU7C3c2W4Bgg6JYTBSBtBDRcS7"

    enum class Status { OK, NOTE, FAIL }

    data class Check(val name: String, val status: Status, val detail: String)

    data class Report(val checks: List<Check>, val atSecs: Long) {
        val ok get() = checks.none { it.status == Status.FAIL }
        val failed get() = checks.filter { it.status == Status.FAIL }

        /** One line per check: `name|STATUS|detail` (for Store). */
        fun encode() = "$atSecs\n" + checks.joinToString("\n") { "${it.name}|${it.status}|${it.detail.replace('\n', ' ').replace('|', '/')}" }

        companion object {
            fun decode(s: String?): Report? = s?.lines()?.takeIf { it.size >= 2 }?.let { l ->
                runCatching {
                    Report(l.drop(1).map { val f = it.split("|", limit = 3); Check(f[0], Status.valueOf(f[1]), f[2]) }, l[0].toLong())
                }.getOrNull()
            }
        }
    }

    /** The base58 code hash NEAR reports for [wasm]: the SHA-256 of its bytes. */
    fun codeHashOf(wasm: ByteArray): String = Base58.encode(sha256(wasm))

    /** Compare what the chain says ([codeHash], [accessKeys], [state]) with what this phone expects. */
    fun evaluate(
        codeHash: String,
        accessKeys: List<String>,
        state: WalletState,
        phonePublicKey: ByteArray?,
        shippedCodeHash: String,
        nowSecs: Long = System.currentTimeMillis() / 1000,
    ): Report {
        val code = when (codeHash) {
            shippedCodeHash -> Check("Wallet code", Status.OK, "the official build this app ships")
            V03_BUILD -> Check("Wallet code", Status.NOTE, "the 14 Nov build (v0.3): still yours and official; it has no inheritance (wallets made with this app version do)")
            V01_BUILD -> Check("Wallet code", Status.NOTE, "the 12 Nov build (v0.1): still yours and official; it doesn't check new keys on chain and can't be upgraded")
            PREVIOUS_BUILD -> Check("Wallet code", Status.NOTE, "the older build from before 12 Nov: still yours, but it refuses Visa online signatures and can't be upgraded")
            else -> Check("Wallet code", Status.FAIL, "unknown code ($codeHash): this isn't a Tapvault build this app knows")
        }
        val keys = if (accessKeys.isEmpty()) Check("Account keys", Status.OK, "none: only the wallet code can move funds")
        else Check("Account keys", Status.FAIL, "${accessKeys.size} access key${if (accessKeys.size == 1) "" else "s"} on the wallet account: whoever holds ${if (accessKeys.size == 1) "it" else "them"} could replace the code")
        val phone = when {
            phonePublicKey == null -> Check("Phone key", Status.FAIL, "this phone has no key for this wallet")
            state.phoneIndexOf(phonePublicKey) != null -> Check("Phone key", Status.OK, "this phone is signer ${state.phoneIndexOf(phonePublicKey)}")
            else -> Check("Phone key", Status.FAIL, "this phone's key isn't a signer of the wallet any more")
        }
        val setup = if (!state.setupMode) Check("Setup", Status.OK, "locked; ${state.threshold} of ${state.signers.size} signers needed")
        else Check("Setup", Status.FAIL, "the wallet is still in setup mode")
        return Report(listOf(code, keys, phone, setup), nowSecs)
    }
}
