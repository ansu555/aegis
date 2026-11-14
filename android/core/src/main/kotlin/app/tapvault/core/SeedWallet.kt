package app.tapvault.core

import java.math.BigInteger

/**
 * A seed-phrase wallet: a plain NEAR account controlled by one ed25519 key
 * (from a recovery phrase, or a private key the user imported). No contract,
 * no card: whoever has the phrase owns the funds.
 *
 * "Protect with bank card" turns it into a Tapvault wallet with the normal
 * setup steps (deploy + `new`, add the card, finalize), signed by this key;
 * finalize deletes it, so the phrase stops working on chain. [preflight]
 * checks that this is safe before anything is sent.
 */
class SeedWallet(private val near: NearClient, val account: String, val key: NearKey) {

    /** Send NEAR from the account itself (a plain transfer, signed with the phrase's key). */
    fun send(receiver: String, yocto: BigInteger): TxResult {
        require(yocto.signum() > 0) { "amount must be positive" }
        val r = near.send(account, key, receiver, listOf(NearAction.Transfer(yocto)))
        if (!r.ok) throw TapvaultException("transfer failed: ${r.error}")
        return r
    }

    /** What protecting the account needs, found by [preflight]. */
    class Preflight(
        /** Other access keys on the account; they're deleted with the deploy. */
        val otherKeys: List<String>,
        val spendable: BigInteger,
        val needed: BigInteger,
    )

    /**
     * Refuses (with a reason the user can act on) when the account can't be
     * protected safely: it already runs a contract, this key isn't a
     * full-access key of it, or it can't pay for the contract's storage
     * (plus a fee account's funding when [needsFeeAccount]).
     */
    fun preflight(wasmSize: Int, needsFeeAccount: Boolean): Preflight {
        val code = near.codeHash(account)
        if (code != NearClient.NO_CODE) throw TapvaultException(
            "$account already runs a smart contract. Protecting it would replace that contract, so Tapvault won't do it. Move the funds to a new seed phrase wallet first.",
        )
        if (key.publicKeyString !in near.fullAccessKeys(account)) throw TapvaultException(
            "This phone's key is no longer a full-access key of $account, so it can't protect it.",
        )
        val others = near.accessKeys(account).filter { it != key.publicKeyString }
        val (spendable, _) = near.spendable(account)
        val needed = storageFor(wasmSize) + GAS_MARGIN + (if (needsFeeAccount) Onboarding.Sponsor.GAS_FUNDING else BigInteger.ZERO)
        if (spendable < needed) throw TapvaultException(
            "Protecting the wallet needs about ${Near.format(needed, 2)} NEAR free (the contract's storage" +
                (if (needsFeeAccount) ", 0.5 NEAR for the fee account" else "") +
                " and fees); this wallet has ${Near.format(spendable, 2)} NEAR. Add testnet NEAR and try again.",
        )
        return Preflight(others, spendable, needed)
    }

    /**
     * A fee account for this wallet, made without the faucet: an implicit
     * account (the hex of [gasKey]'s public key) that exists once this wallet
     * sends it NEAR. Returns its name; does nothing if it already exists.
     */
    fun fundFeeAccount(gasKey: NearKey, amount: BigInteger = Onboarding.Sponsor.GAS_FUNDING): String {
        val gas = NearClient.implicitAccount(gasKey.publicKey)
        if (!near.accountExists(gas)) {
            val r = near.send(account, key, gas, listOf(NearAction.Transfer(amount)))
            if (!r.ok) throw TapvaultException("funding the fee account failed: ${r.error}")
        }
        return gas
    }

    companion object {
        /** NEAR's storage price: 10^19 yocto per byte. */
        private val PER_BYTE: BigInteger = BigInteger.TEN.pow(19)

        /** The contract's state after setup, with room to spare. */
        private const val STATE_BYTES = 4_000

        /** Gas for the setup transactions. */
        private val GAS_MARGIN: BigInteger = Near.parse("0.1")

        fun storageFor(wasmSize: Int): BigInteger = PER_BYTE * BigInteger.valueOf((wasmSize + STATE_BYTES).toLong())

        /**
         * What the user typed on the import screen: a recovery phrase, or a
         * private key (`ed25519:…`, or its base58 alone). Returns the key and,
         * for a phrase, the normalised phrase.
         */
        fun parseImport(input: String): Pair<NearKey, String?> {
            val t = input.trim()
            if (t.isEmpty()) throw TapvaultException("Enter your recovery phrase or private key.")
            if (t.split(Regex("\\s+")).size > 1) {
                Mnemonic.problem(t)?.let { throw TapvaultException(it) }
                val phrase = Mnemonic.normalize(t)
                return Mnemonic.nearKey(phrase) to phrase
            }
            if (t.contains(':') && !t.startsWith("ed25519:")) throw TapvaultException("Only ed25519 keys are supported (they start with ed25519:).")
            val raw = runCatching { Base58.decode(t.removePrefix("ed25519:")) }.getOrNull()
            if (raw == null || (raw.size != 64 && raw.size != 32)) throw TapvaultException(
                "That isn't a recovery phrase or a NEAR private key. A private key looks like ed25519:3x7… (about 88 characters).",
            )
            val k = NearKey(raw.copyOf(32))
            // A 64-byte secret carries its public key: it must match, or the text was damaged.
            if (raw.size == 64 && !raw.copyOfRange(32, 64).contentEquals(k.publicKey)) throw TapvaultException(
                "This private key is damaged (its two halves don't match). Copy it again.",
            )
            return k to null
        }

        /**
         * Accounts [key] fully controls: its implicit account, if it exists,
         * and any named account the index lists. Each is checked on chain.
         */
        fun findAccounts(near: NearClient, key: NearKey): List<String> {
            val implicit = NearClient.implicitAccount(key.publicKey)
            val indexed = runCatching { near.accountsForKey(key.publicKeyString) }.getOrDefault(emptyList())
            return (indexed + implicit).distinct().filter { a ->
                runCatching { key.publicKeyString in near.fullAccessKeys(a) }.getOrDefault(false)
            }
        }
    }
}
