package app.tapvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Kotlin client must sign exactly what the Rust contract verifies. */
class VectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/vectors.json")!!.readText()
    ).jsonObject
    private val account = v["account"]!!.jsonPrimitive.content
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private fun msg(k: String) = v["messages"]!!.jsonObject[k]!!.jsonPrimitive.content

    private val phone = SignerKey.P256(
        v["phone_key"]!!.jsonObject["P256"]!!.jsonObject["pubkey"]!!.jsonPrimitive.content.unb64()
    )
    private val card = TestCards.SMALL.let { SignerKey.EmvCard(it.modulus, it.exponent, 0, 4, 4) }
    private val usdc = Asset.Ft("usdc.fakes.testnet")

    private val actions = mapOf(
        "send_near" to Action.Transfer(Asset.Near, BigInteger("1500000000000000000000000"), "bob.testnet"),
        "send_ft" to Action.Transfer(usdc, BigInteger.valueOf(25_000_000), "shop.testnet"),
        "config_all_ops" to Action.Config(
            listOf(
                ConfigOp.AddSigner(card),
                ConfigOp.RemoveSigner(phone.keyHash()),
                ConfigOp.SetThreshold(3),
                ConfigOp.SetLimit(LimitInit(usdc, BigInteger.TEN, BigInteger.valueOf(20))),
            )
        ),
        "config_more_ops" to Action.Config(
            listOf(
                ConfigOp.RemoveLimit(Asset.Near),
                ConfigOp.SetDelays(Delays(3_600, 7_200, 86_400)),
                ConfigOp.Unfreeze,
                ConfigOp.AddSigner(phone),
            )
        ),
        "config_v02_ops" to Action.Config(listOf(ConfigOp.AllowReceiver("carol.testnet"), ConfigOp.DisallowReceiver("dave.testnet"))),
        "config_upgrade" to Action.Config(listOf(ConfigOp.UpgradeCode(ByteArray(32) { 0x33 }))),
        "config_v04_ops" to Action.Config(listOf(ConfigOp.SetHeir("heir.testnet", 15_552_000), ConfigOp.RemoveHeir)),
    )

    @Test
    fun signerKeys() {
        assertEquals(s("phone_key_borsh"), Borsh().also { phone.borsh(it) }.toByteArray().hex())
        assertEquals(s("phone_key_hash"), phone.keyHash().hex())
        assertEquals(s("card_key_borsh"), Borsh().also { card.borsh(it) }.toByteArray().hex())
        assertEquals(s("card_key_hash"), card.keyHash().hex())
        assertEquals(v["phone_key"], phone.json())
    }

    @Test
    fun actionsAndIntents() {
        for (a in v["actions"]!!.jsonArray) {
            val o = a.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val action = actions.getValue(name)
            assertEquals(o["borsh"]!!.jsonPrimitive.content, action.borshBytes().hex(), "borsh $name")
            assertEquals(o["json"], action.json(), "json $name")
            val id = o["intent_id"]!!.jsonPrimitive.int.toLong()
            assertEquals(o["intent"]!!.jsonPrimitive.content, Domain.intentHash(account, id, action.borshBytes()).hex(), "intent $name")
            // the parser used before signing an action read from the RPC gives the same bytes back
            assertEquals(o["borsh"]!!.jsonPrimitive.content, ActionJson.parse(o["json"]!!).borshBytes().hex(), "parse $name")
        }
    }

    @Test
    fun finalizeAndMessages() {
        val args = FinalizeArgs(2, Delays.SHORTEST, listOf(LimitInit(Asset.Near, Near.ONE, Near.ONE * BigInteger.TWO)))
        assertEquals(s("finalize_borsh"), args.borshBytes().hex())
        assertEquals(v["finalize_args"], args.json())

        val intent = msg("intent_input").unhex()
        val seed = msg("seed_input").unhex()
        assertEquals(msg("create"), Domain.createMessage(account, phone.keyHash()).hex())
        assertEquals(msg("setup_add_1_card"), Domain.setupAddMessage(account, 1, card.keyHash()).hex())
        assertEquals(msg("card_enroll_1"), Domain.cardEnrollChallenge(account, 1, card.keyHash()).hex())
        assertEquals(msg("finalize"), Domain.finalizeMessage(account, args.borshBytes()).hex())
        assertEquals(msg("card_approve"), Domain.cardApproveChallenge(intent, seed).hex())
        assertEquals(msg("card_propose"), Domain.cardProposeChallenge(intent).hex())
        assertEquals(msg("cancel"), Domain.cancelMessage(intent).hex())
        assertEquals(msg("card_cancel"), Domain.cardCancelChallenge(intent).hex())
        assertEquals(msg("freeze_3"), Domain.freezeMessage(account, 3).hex())
        assertEquals(msg("card_freeze_3"), Domain.cardFreezeChallenge(account, 3).hex())
        assertEquals(msg("possess_card"), Domain.possessionMessage(account, card.keyHash()).hex())
        assertEquals(msg("card_possess_card"), Domain.cardPossessionChallenge(account, card.keyHash()).hex())
        assertEquals(msg("alive_3"), Domain.aliveMessage(account, 3).hex())
        assertEquals(msg("card_alive_3"), Domain.cardAliveChallenge(account, 3).hex())
    }
}
