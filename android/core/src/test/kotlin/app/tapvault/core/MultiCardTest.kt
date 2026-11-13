package app.tapvault.core

import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several cards per wallet: identify a tapped card by key hash, hold, then sign; backup-card checks; the card book. */
class MultiCardTest {
    private val capks = CapkStore.load().plus(SimCard.TEST_CA)
    private val svc = CardService(capks)
    private val key2 = TestCards.random()

    private fun enrolled(card: SimCard, account: String = "w.testnet", count: Int = 1) =
        svc.enrol(card) { k -> Domain.cardEnrollChallenge(account, count, k.keyHash()) }

    private fun candidate(e: CardService.Enrolment) = CardCandidate(e.key.keyHash().hex(), e.key, e.recipe)

    @Test
    fun identifiesWhichCardWasTappedThenSignsForIt() {
        for (profile in SimCard.Profile.entries) {
            val c1 = enrolled(SimCard(profile))
            val c2 = enrolled(SimCard(profile, key2, SimCard.PAN2))
            val candidates = listOf(candidate(c1), candidate(c2))
            val card2 = SimCard(profile, key2, SimCard.PAN2)
            val held = svc.identify(card2, candidates)
            assertEquals(c2.key.keyHash().hex(), held.card.keyHash, "$profile: matched card 2 by key hash")
            held.stillThere()
            held.stillThere()
            val challenge = EmvSession.randomUn()
            val sig = held.sign(challenge)
            assertTrue(Rsa.verifySdad(c2.key.modulus, c2.key.exponent, sig.sdad, sig.terminalData), "$profile: card 2's key")
            assertContentEquals(challenge.copyOf(c2.key.unLen), sig.terminalData.copyOfRange(c2.key.unOffset, c2.key.unOffset + c2.key.unLen))
            assertEquals(c2.key.terminalDataLen, sig.terminalData.size)

            // card 1 is found too, in its own session
            assertEquals(c1.key.keyHash().hex(), svc.identify(SimCard(profile), candidates).card.keyHash)
        }
    }

    @Test
    fun aCardThatIsntTheWalletsIsRefusedBeforeItSigns() {
        for (profile in SimCard.Profile.entries) {
            val walletCard = enrolled(SimCard(profile))
            val stranger = SimCard(profile, key2, SimCard.PAN2)
            assertFailsWith<WrongCardException> { svc.identify(stranger, listOf(candidate(walletCard))) }
            // DDA and CDA cards sign only when asked; a Visa-style card signs a random number in every GPO answer, nothing else.
            if (profile != SimCard.Profile.VISA_FDDA) assertEquals(0, stranger.signatures, "$profile: no signature at all")
        }
    }

    @Test
    fun aCardMovedAwayDuringTheHoldIsNoticed() {
        val e = enrolled(SimCard())
        val real = SimCard()
        var present = true
        val link = CardTransceiver { apdu -> if (present) real.transceive(apdu) else throw IOException("Tag was lost.") }
        val held = svc.identify(link, listOf(candidate(e)))
        held.stillThere()
        val before = real.signatures
        present = false
        assertFailsWith<IOException> { held.stillThere() }
        assertEquals(before, real.signatures, "nothing signed while the card was away")
    }

    @Test
    fun backupCardPossessionIsCheckedOnThePhone() {
        val account = "w.testnet"
        val e = enrolled(SimCard(SimCard.Profile.VISA_FDDA, key2, SimCard.PAN2), account, 2)
        val challenge = Domain.cardEnrollChallenge(account, 2, e.key.keyHash())
        assertTrue(provesPossession(e.key, e.proof, challenge))
        assertTrue(!provesPossession(e.key, e.proof, Domain.cardEnrollChallenge(account, 3, e.key.keyHash())), "another challenge")
        val other = enrolled(SimCard())
        assertTrue(!provesPossession(other.key, e.proof, challenge), "another card's key")
        val bent = CardSig(e.proof.sdad.copyOf().also { it[5] = (it[5] + 1).toByte() }, e.proof.terminalData)
        assertTrue(!provesPossession(e.key, bent, challenge), "a changed signature")
    }

    @Test
    fun aCardThatIsAlreadyASignerStopsTheEnrolment() {
        val existing = setOf(TestCards.SMALL.let { Domain.keyHashEmv(it.modulus, it.exponent).hex() })
        val card = SimCard()
        assertFailsWith<AlreadySignerException> {
            svc.enrol(card) { k ->
                if (k.keyHash().hex() in existing) throw AlreadySignerException("already a signer")
                ByteArray(32)
            }
        }
        assertEquals(0, card.signatures)
    }

    @Test
    fun cardBookMigratesTheSingleCardAndBindsItToTheSetupCard() {
        val c1 = enrolled(SimCard())
        val c2 = enrolled(SimCard(SimCard.Profile.DDA, key2, SimCard.PAN2))
        val phone = SoftP256Signer().signerKey()
        val state = WalletState(
            setupMode = false, frozen = false, threshold = 2, freezeNonce = 0, nextProposalId = 0,
            delays = Delays.SHORTEST,
            signers = listOf(
                SignerInfo(0, false, phone.keyHash().hex(), phone.json()),
                SignerInfo(1, true, c1.key.keyHash().hex(), c1.key.json()),
                SignerInfo(2, true, c2.key.keyHash().hex(), c2.key.json()),
            ),
            limits = emptyList(),
        )
        assertEquals(0, CardBook.load(null, null, null, null).cards.size)
        val legacy = CardBook.load(null, c1.recipe.encode(), "Visa", "1234")
        assertEquals(1, legacy.cards.size)
        assertEquals("", legacy.cards[0].keyHash)
        val bound = legacy.bind(state)!!
        assertEquals(c1.key.keyHash().hex(), bound.cards[0].keyHash, "the setup card (signer 1) becomes entry 1")
        assertEquals(c1.recipe, bound.cards[0].recipe)
        assertEquals("Visa •••• 1234", bound.cards[0].name)
        assertNull(bound.bind(state), "nothing left to bind")

        val both = bound.put(KnownCard(c2.key.keyHash().hex(), c2.recipe, "Mastercard", "5678"))
        val round = CardBook.decode(both.encode())
        assertEquals(2, round.cards.size)
        assertEquals(c2.recipe, round.find(c2.key.keyHash().hex().uppercase())!!.recipe)
        assertEquals(2, round.put(round.cards[1].copy(last4 = "9999")).cards.size, "same key hash replaces")
        // a stored (possibly empty) list wins over the old single-card fields
        assertEquals(0, CardBook.load("", c1.recipe.encode(), "Visa", "1234").cards.size)
    }

    @Test
    fun configProposalsNameTheCardsTheyAddAndRemove() {
        val c = enrolled(SimCard(SimCard.Profile.DDA, key2, SimCard.PAN2))
        val removed = Domain.keyHashEmv(TestCards.SMALL.modulus, 3)
        val action = Action.Config(listOf(ConfigOp.AddSigner(c.key), ConfigOp.RemoveSigner(removed))).json()
        val p = ProposalInfo(7, ByteArray(32), 0, 1, 0, null, null, false, action, null)
        assertEquals(listOf(c.key.keyHash().hex()), p.addedCardKeyHashes())
        assertEquals(listOf(removed.hex()), p.removedKeyHashes())
        assertTrue(p.describe().contains("AddSigner"))
        assertTrue((action as JsonObject).containsKey("Config"))
    }
}
