package app.tapvault.core

/**
 * Every message a Tapvault signer signs. Mirrors crates/tapvault-core/src/domain.rs;
 * pinned by vectors.json.
 */
object Domain {
    private fun tag(s: String) = "tapvault/near/v1/$s".toByteArray()

    private val D_INTENT = tag("intent")
    private val D_CARD_APPROVE = tag("card-approve")
    private val D_CARD_PROPOSE = tag("card-propose")
    private val D_CANCEL = tag("cancel")
    private val D_CARD_CANCEL = tag("card-cancel")
    private val D_FREEZE = tag("freeze")
    private val D_CARD_FREEZE = tag("card-freeze")
    private val D_CREATE = tag("create")
    private val D_SETUP_ADD = tag("setup-add")
    private val D_CARD_ENROLL = tag("card-enroll")
    private val D_FINALIZE = tag("finalize")
    private val D_KEY_P256 = tag("key/p256")
    private val D_KEY_EMV = tag("key/emv")
    private val D_POSSESS = tag("possess")
    private val D_CARD_POSSESS = tag("card-possess")
    private val D_ALIVE = tag("alive")
    private val D_CARD_ALIVE = tag("card-alive")

    private fun acct(account: String): Array<ByteArray> {
        val b = account.toByteArray()
        return arrayOf(u32le(b.size), b)
    }

    fun keyHashP256(pubkey33: ByteArray) = sha256(D_KEY_P256, pubkey33)

    fun keyHashEmv(modulus: ByteArray, exponent: Int) = sha256(D_KEY_EMV, u32le(exponent), modulus)

    fun intentHash(account: String, proposalId: Long, action: ByteArray) =
        sha256(D_INTENT, *acct(account), u64le(proposalId), action)

    fun cardApproveChallenge(intent: ByteArray, seed: ByteArray) = sha256(D_CARD_APPROVE, intent, seed)

    fun cardProposeChallenge(intent: ByteArray) = sha256(D_CARD_PROPOSE, intent)

    fun cancelMessage(intent: ByteArray) = sha256(D_CANCEL, intent)

    fun cardCancelChallenge(intent: ByteArray) = sha256(D_CARD_CANCEL, intent)

    fun freezeMessage(account: String, nonce: Long) = sha256(D_FREEZE, *acct(account), u64le(nonce))

    fun cardFreezeChallenge(account: String, nonce: Long) = sha256(D_CARD_FREEZE, *acct(account), u64le(nonce))

    fun createMessage(account: String, firstKeyHash: ByteArray) = sha256(D_CREATE, *acct(account), firstKeyHash)

    fun setupAddMessage(account: String, signerCount: Int, newKeyHash: ByteArray) =
        sha256(D_SETUP_ADD, *acct(account), byteArrayOf(signerCount.toByte()), newKeyHash)

    fun cardEnrollChallenge(account: String, signerCount: Int, newKeyHash: ByteArray) =
        sha256(D_CARD_ENROLL, *acct(account), byteArrayOf(signerCount.toByte()), newKeyHash)

    fun finalizeMessage(account: String, args: ByteArray) = sha256(D_FINALIZE, *acct(account), args)

    /** v0.2: a key a config proposal adds signs this to prove it is held (no proposal id: it can be signed ahead). */
    fun possessionMessage(account: String, newKeyHash: ByteArray) = sha256(D_POSSESS, *acct(account), newKeyHash)

    /** v0.2: the card form of [possessionMessage]. */
    fun cardPossessionChallenge(account: String, newKeyHash: ByteArray) = sha256(D_CARD_POSSESS, *acct(account), newKeyHash)

    /** v0.4: a check-in ("I'm still here") resets the inheritance timer; the nonce makes it single-use. */
    fun aliveMessage(account: String, nonce: Long) = sha256(D_ALIVE, *acct(account), u64le(nonce))

    /** v0.4: the card form of [aliveMessage]. */
    fun cardAliveChallenge(account: String, nonce: Long) = sha256(D_CARD_ALIVE, *acct(account), u64le(nonce))
}
