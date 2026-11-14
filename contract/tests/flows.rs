//! End-to-end flows against the contract in near-sdk's mocked runtime, with
//! a software phone key (P-256) and the simulated EMV card. Everything the
//! signers sign is computed here independently from the contract.

#![allow(unused_must_use)] // `execute` returns a promise we don't run here

use std::panic::{catch_unwind, AssertUnwindSafe};

use near_sdk::json_types::{Base64VecU8, U128, U64};
use near_sdk::test_utils::VMContextBuilder;
use near_sdk::{testing_env, NearToken, PromiseOrValue};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use tapvault_contract::setup::Possession;
use tapvault_contract::types::*;
use tapvault_contract::Tapvault;
use tapvault_core::testcard::TestCard;
use tapvault_core::{domain, SoftSha256 as S};

const WALLET: &str = "wallet.testnet";
const RELAYER: &str = "relayer.testnet";
const DAY: i64 = 86_400;
const NEAR: u128 = 1_000_000_000_000_000_000_000_000;
const T0: i64 = 1_800_000_000;

fn acct() -> &'static [u8] {
    WALLET.as_bytes()
}

fn b64(v: impl Into<Vec<u8>>) -> Base64VecU8 {
    Base64VecU8(v.into())
}

fn ctx(predecessor: &str, t: i64, seed: u8) {
    testing_env!(VMContextBuilder::new()
        .current_account_id(WALLET.parse().unwrap())
        .predecessor_account_id(predecessor.parse().unwrap())
        .signer_account_id(predecessor.parse().unwrap())
        .block_timestamp(t as u64 * 1_000_000_000)
        .block_height((t - T0) as u64 + 100)
        .random_seed([seed; 32])
        .account_balance(NearToken::from_near(100))
        .build());
}

/// Run `f` and require that it panics with a message containing `msg`.
fn fails(msg: &str, f: impl FnOnce()) {
    let err = catch_unwind(AssertUnwindSafe(f)).expect_err("call should have failed");
    let text = err
        .downcast_ref::<String>()
        .cloned()
        .or_else(|| err.downcast_ref::<&str>().map(|s| s.to_string()))
        .unwrap_or_default();
    assert!(text.contains(msg), "expected {msg:?}, got {text:?}");
}

struct Phone(SigningKey);

impl Phone {
    fn new(k: u8) -> Self {
        Phone(SigningKey::from_slice(&[k; 32]).unwrap())
    }
    fn key(&self) -> SignerKey {
        SignerKey::P256 { pubkey: b64(self.0.verifying_key().to_encoded_point(true).as_bytes()), webauthn: None }
    }
    fn sign(&self, msg: &[u8; 32]) -> P256Sig {
        let s: Signature = self.0.sign(msg);
        P256Sig { signature: b64(s.to_bytes().to_vec()), webauthn: None }
    }
}

/// A test card that signs like a DDA / CDA card (UN only, format 05), or like
/// a Visa card on an online transaction (fDDA, 19 bytes of terminal data,
/// format 95).
struct Card(TestCard, bool);

impl Card {
    fn new() -> Self {
        Card(TestCard::default(), false)
    }
    fn visa_online() -> Self {
        Card(TestCard::default(), true)
    }
    fn key(&self) -> SignerKey {
        SignerKey::EmvCard {
            modulus: b64(self.0.modulus.clone()),
            exponent: self.0.exponent,
            un_offset: TestCard::UN_OFFSET,
            un_len: TestCard::UN_LEN,
            terminal_data_len: if self.1 { TestCard::FDDA_TERMINAL_DATA_LEN } else { TestCard::TERMINAL_DATA_LEN },
        }
    }
    fn tap(&mut self, challenge: &[u8; 32]) -> CardSig {
        let (sdad, td) = if self.1 { self.0.sign_online_fdda(challenge) } else { self.0.sign_challenge(challenge) };
        CardSig { sdad: b64(sdad), terminal_data: b64(td) }
    }
}

fn key_hash(k: &SignerKey) -> [u8; 32] {
    match k {
        SignerKey::P256 { pubkey, .. } => domain::key_hash_p256::<S>(&pubkey.0),
        SignerKey::EmvCard { modulus, exponent, .. } => domain::key_hash_emv::<S>(&modulus.0, *exponent),
    }
}

fn intent(id: u64, action: &Action) -> [u8; 32] {
    domain::intent_hash::<S>(acct(), id, &near_sdk::borsh::to_vec(action).unwrap())
}

fn send_near(amount: u128) -> Action {
    Action::Transfer { asset: Asset::Near, amount: U128(amount), receiver: "bob.testnet".parse().unwrap() }
}

/// A finalized wallet: signer 0 = phone, signer 1 = card, threshold 2,
/// NEAR limits of 5 instant / 8 per day.
struct Wallet {
    c: Tapvault,
    phone: Phone,
    card: Card,
    t: i64,
    seed: u8,
}

impl Wallet {
    fn new() -> Self {
        Self::with_card(Card::new())
    }

    fn with_card(mut card: Card) -> Self {
        let phone = Phone::new(7);
        ctx(WALLET, T0, 1);
        let mut c = Tapvault::new(phone.key(), phone.sign(&domain::create_message::<S>(acct(), &key_hash(&phone.key()))));

        let ck = card.key();
        let ch = domain::card_enroll_challenge::<S>(acct(), 1, &key_hash(&ck));
        let auth = phone.sign(&domain::setup_add_message::<S>(acct(), 1, &key_hash(&ck)));
        c.setup_add_signer(ck, 0, auth, Possession::Card(card.tap(&ch)));

        let args = FinalizeArgs {
            threshold: 2,
            delays: DelaysCfg { transfer: DAY, config: DAY, recovery: 3 * DAY },
            limits: vec![LimitInit { asset: Asset::Near, instant_max: U128(5 * NEAR), daily_max: U128(8 * NEAR) }],
        };
        let msg = domain::finalize_message::<S>(acct(), &near_sdk::borsh::to_vec(&args).unwrap());
        c.setup_finalize(args, 0, phone.sign(&msg));
        Wallet { c, phone, card, t: T0, seed: 1 }
    }

    /// v0.3: a wallet set up without a card. Signer 0 = phone, threshold 1;
    /// `card` is a card that isn't enrolled yet.
    fn phone_only() -> Self {
        let phone = Phone::new(7);
        ctx(WALLET, T0, 1);
        let mut c = Tapvault::new(phone.key(), phone.sign(&domain::create_message::<S>(acct(), &key_hash(&phone.key()))));
        let args = FinalizeArgs {
            threshold: 1,
            delays: DelaysCfg { transfer: DAY, config: DAY, recovery: 3 * DAY },
            limits: vec![LimitInit { asset: Asset::Near, instant_max: U128(5 * NEAR), daily_max: U128(8 * NEAR) }],
        };
        let msg = domain::finalize_message::<S>(acct(), &near_sdk::borsh::to_vec(&args).unwrap());
        c.setup_finalize(args, 0, phone.sign(&msg));
        Wallet { c, phone, card: Card::new(), t: T0, seed: 1 }
    }

    fn as_relayer(&self) {
        ctx(RELAYER, self.t, self.seed);
    }

    fn advance(&mut self, secs: i64) {
        self.t += secs;
        self.seed = self.seed.wrapping_add(1);
        self.as_relayer();
    }

    fn propose_by_phone(&mut self, action: Action) -> u64 {
        self.as_relayer();
        let id = self.c.get_wallet().next_proposal_id.0;
        let sig = self.phone.sign(&intent(id, &action));
        let got = self.c.propose(action, SignerProof::P256 { signer_index: 0, sig }, None).0;
        assert_eq!(got, id);
        got
    }

    /// The scheduled `seal_challenge` receipt runs in a later block.
    fn seal(&mut self, id: u64) -> [u8; 32] {
        self.advance(1);
        ctx(WALLET, self.t, self.seed);
        self.c.seal_challenge(U64(id));
        self.as_relayer();
        let p = self.c.get_proposal(U64(id)).unwrap();
        let mut i = [0u8; 32];
        hex_into(&p.intent_hash, &mut i);
        let ch = domain::card_approve_challenge::<S>(&i, &[self.seed; 32]);
        assert_eq!(p.card_challenge.as_deref(), Some(hex(&ch).as_str()));
        ch
    }

    fn cancel_by_phone(&mut self, id: u64) {
        let i = intent(id, &self.c.get_proposal(U64(id)).unwrap().action);
        let sig = self.phone.sign(&domain::cancel_message::<S>(&i));
        self.c.cancel(U64(id), SignerProof::P256 { signer_index: 0, sig });
    }

    fn card_approves(&mut self, id: u64) {
        let ch = self.seal(id);
        let sig = self.card.tap(&ch);
        self.c.approve_card(U64(id), 1, sig);
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn hex_into(s: &str, out: &mut [u8; 32]) {
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap();
    }
}

fn spent_near(c: &Tapvault) -> u128 {
    let l = c.get_wallet().limits.into_iter().find(|l| l.asset == Asset::Near).unwrap();
    l.used.0
}

#[test]
fn setup_produces_a_locked_two_signer_wallet() {
    let w = Wallet::new();
    let v = w.c.get_wallet();
    assert!(!v.setup_mode);
    assert_eq!(v.threshold, 2);
    assert_eq!(v.signers.len(), 2);
}

#[test]
fn setup_rejects_a_card_that_does_not_prove_possession() {
    let phone = Phone::new(7);
    let mut card = Card::new();
    ctx(WALLET, T0, 1);
    let mut c = Tapvault::new(phone.key(), phone.sign(&domain::create_message::<S>(acct(), &key_hash(&phone.key()))));
    let ck = card.key();
    let auth = phone.sign(&domain::setup_add_message::<S>(acct(), 1, &key_hash(&ck)));
    // card signs something other than its enrolment challenge
    let wrong = card.tap(&[0xEE; 32]);
    fails("Card signature", || c.setup_add_signer(ck, 0, auth, Possession::Card(wrong)));
}

#[test]
fn phone_plus_card_sends_instantly_within_limits() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(3 * NEAR));
    fails("Not enough approvals", || {
        w.c.execute(U64(id));
    });
    w.card_approves(id);
    assert!(matches!(w.c.execute(U64(id)), PromiseOrValue::Promise(_)));
    assert_eq!(spent_near(&w.c), 3 * NEAR);
    assert!(w.c.get_proposal(U64(id)).is_none());
}

#[test]
fn a_visa_card_signing_online_fdda_works_end_to_end() {
    let mut w = Wallet::with_card(Card::visa_online());
    let id = w.propose_by_phone(send_near(3 * NEAR));
    w.card_approves(id);
    assert!(matches!(w.c.execute(U64(id)), PromiseOrValue::Promise(_)));
    assert_eq!(spent_near(&w.c), 3 * NEAR);

    // A UN-only (DDA-style) signature doesn't fit this card's layout.
    let id = w.propose_by_phone(send_near(NEAR));
    let ch = w.seal(id);
    let (sdad, td) = w.card.0.sign_challenge(&ch);
    fails("Card signature", || w.c.approve_card(U64(id), 1, CardSig { sdad: b64(sdad), terminal_data: b64(td) }));
}

#[test]
fn card_cannot_approve_before_the_challenge_is_sealed() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(NEAR));
    let early = w.card.tap(&[0u8; 32]);
    fails("not sealed", || w.c.approve_card(U64(id), 1, early));
}

#[test]
fn a_skimmed_card_signature_cannot_approve_another_proposal() {
    let mut w = Wallet::new();
    let a = w.propose_by_phone(send_near(NEAR));
    let ch_a = w.seal(a);
    let skimmed = w.card.tap(&ch_a);

    let b = w.propose_by_phone(send_near(NEAR));
    w.seal(b);
    fails("Card signature", || w.c.approve_card(U64(b), 1, skimmed));
}

#[test]
fn over_limit_transfers_wait_for_the_delay() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(6 * NEAR));
    w.card_approves(id);
    fails("Delay has not elapsed", || {
        w.c.execute(U64(id));
    });
    w.advance(DAY);
    w.c.execute(U64(id));
}

#[test]
fn daily_allowance_refills() {
    let mut w = Wallet::new();
    for amount in [5 * NEAR, 3 * NEAR] {
        let id = w.propose_by_phone(send_near(amount));
        w.card_approves(id);
        w.c.execute(U64(id));
    }
    // 8 NEAR used: even 1 more is delayed
    let id = w.propose_by_phone(send_near(NEAR));
    w.card_approves(id);
    fails("Delay has not elapsed", || {
        w.c.execute(U64(id));
    });
    // a day later the allowance has refilled and the proposal is past its delay
    w.advance(DAY);
    w.c.execute(U64(id));
    assert_eq!(spent_near(&w.c), NEAR);
}

#[test]
fn failed_transfer_gives_allowance_back() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(2 * NEAR));
    w.card_approves(id);
    w.c.execute(U64(id));
    assert_eq!(spent_near(&w.c), 2 * NEAR);

    testing_env!(
        VMContextBuilder::new()
            .current_account_id(WALLET.parse().unwrap())
            .predecessor_account_id(WALLET.parse().unwrap())
            .block_timestamp(w.t as u64 * 1_000_000_000)
            .build(),
        near_sdk::test_vm_config(),
        near_sdk::RuntimeFeesConfig::test(),
        Default::default(),
        vec![near_sdk::PromiseResult::Failed]
    );
    assert!(!w.c.on_transfer(U64(id), Asset::Near, U128(2 * NEAR)));
    assert!(spent_near(&w.c) < NEAR / 1000); // back to (almost) nothing used
}

#[test]
fn adding_an_enrolled_key_again_is_refused() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::AddSigner(w.card.key())] });
    let ch = domain::card_possession_challenge::<S>(acct(), &key_hash(&w.card.key()));
    let proof = PossessionProof::Card(w.card.tap(&ch));
    w.c.prove_possession(U64(id), 0, proof);
    w.card_approves(id);
    w.advance(DAY);
    fails("Signer already enrolled", || {
        w.c.execute(U64(id));
    });
}

#[test]
fn a_card_proposal_is_not_an_approval() {
    let mut w = Wallet::new();
    let action = send_near(NEAR);
    let id = w.c.get_wallet().next_proposal_id.0;
    let proof = w.card.tap(&domain::card_propose_challenge::<S>(&intent(id, &action)));
    w.c.propose(action, SignerProof::Card { signer_index: 1, sig: proof }, None);
    assert_eq!(w.c.get_proposal(U64(id)).unwrap().approvals, 0);
    // even after the card's real approval, the phone is still missing
    w.card_approves(id);
    fails("Not enough approvals", || {
        w.c.execute(U64(id));
    });
}

#[test]
fn freeze_blocks_transfers_and_signatures_are_single_use() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(NEAR));
    w.card_approves(id);

    let ch = domain::card_freeze_challenge::<S>(acct(), 0);
    let tap = w.card.tap(&ch);
    w.c.freeze(SignerProof::Card { signer_index: 1, sig: tap.clone() });
    assert!(w.c.get_wallet().frozen);
    // the next nonce is random, so future freeze signatures can't be pre-collected
    assert_ne!(w.c.get_wallet().freeze_nonce.0, 1);
    fails("Wallet is frozen", || {
        w.c.execute(U64(id));
    });
    fails("Wallet is frozen", || {
        w.propose_by_phone(send_near(NEAR));
    });

    // Unfreeze is a delayed config change.
    let un = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::Unfreeze] });
    w.card_approves(un);
    w.advance(DAY);
    w.c.execute(U64(un));
    assert!(!w.c.get_wallet().frozen);

    // The old freeze signature is spent (nonce moved on).
    fails("Card signature", || w.c.freeze(SignerProof::Card { signer_index: 1, sig: tap }));
}

#[test]
fn config_changes_void_other_proposals() {
    let mut w = Wallet::new();
    let pay = w.propose_by_phone(send_near(NEAR));
    let cfg = w.propose_by_phone(Action::Config {
        ops: vec![ConfigOp::SetLimit(LimitInit {
            asset: Asset::Near,
            instant_max: U128(NEAR),
            daily_max: U128(2 * NEAR),
        })],
    });
    w.card_approves(cfg);
    fails("Delay has not elapsed", || {
        w.c.execute(U64(cfg));
    });
    w.advance(DAY);
    w.c.execute(U64(cfg));

    assert!(w.c.get_proposal(U64(pay)).unwrap().stale);
    let seed_ch = w.seal(pay);
    let tap = w.card.tap(&seed_ch);
    fails("Signer set changed", || w.c.approve_card(U64(pay), 1, tap));
    w.c.close_stale(U64(pay));
    assert!(w.c.get_proposal(U64(pay)).is_none());
}

#[test]
fn a_card_cannot_veto_its_own_removal_but_the_phone_can_veto_anything() {
    let mut w = Wallet::new();
    let ck = w.card.key();
    let remove = Action::Config { ops: vec![ConfigOp::RemoveSigner { key_hash: b64(key_hash(&ck).to_vec()) }] };
    let id = w.propose_by_phone(remove);
    let i = intent(id, &w.c.get_proposal(U64(id)).unwrap().action);

    let tap = w.card.tap(&domain::card_cancel_challenge::<S>(&i));
    fails("may not cancel", || w.c.cancel(U64(id), SignerProof::Card { signer_index: 1, sig: tap }));

    let sig = w.phone.sign(&domain::cancel_message::<S>(&i));
    w.c.cancel(U64(id), SignerProof::P256 { signer_index: 0, sig });
    assert!(w.c.get_proposal(U64(id)).is_none());
}

#[test]
fn forged_or_misdirected_phone_signatures_are_rejected() {
    let mut w = Wallet::new();
    let action = send_near(NEAR);
    let id = w.c.get_wallet().next_proposal_id.0;

    // a different key
    let thief = Phone::new(9);
    let sig = thief.sign(&intent(id, &action));
    fails("P-256 signature invalid", || {
        w.c.propose(action.clone(), SignerProof::P256 { signer_index: 0, sig }, None);
    });

    // the right key over a different action (amount changed after signing)
    let sig = w.phone.sign(&intent(id, &send_near(2 * NEAR)));
    fails("P-256 signature invalid", || {
        w.c.propose(action, SignerProof::P256 { signer_index: 0, sig }, None);
    });
}

#[test]
fn proposals_are_rate_limited_per_signer() {
    let mut w = Wallet::new();
    for _ in 0..tapvault_core::policy::MAX_PROPOSALS_PER_DAY {
        let id = w.propose_by_phone(send_near(NEAR));
        w.cancel_by_phone(id);
    }
    fails("rate limit", || {
        w.propose_by_phone(send_near(NEAR));
    });
}

#[test]
fn open_proposals_are_capped_per_signer() {
    let mut w = Wallet::new();
    for _ in 0..tapvault_contract::MAX_OPEN_PER_SIGNER {
        w.propose_by_phone(send_near(NEAR));
    }
    fails("too many open proposals", || {
        w.propose_by_phone(send_near(NEAR));
    });
    // proposals that can no longer complete don't keep blocking the owner
    w.advance(tapvault_contract::CARD_APPROVAL_WINDOW + 61);
    w.propose_by_phone(send_near(NEAR));
}

#[test]
fn card_approval_window_closes() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(NEAR));
    let ch = w.seal(id);
    // a read of the challenge taken later than the window can't be used
    w.advance(tapvault_contract::CARD_APPROVAL_WINDOW + 1);
    let tap = w.card.tap(&ch);
    fails("approval window has closed", || w.c.approve_card(U64(id), 1, tap));
}

#[test]
fn delay_runs_from_full_approval_not_from_proposal() {
    let mut w = Wallet::new();
    // a card proposes an over-limit transfer and approves it...
    let action = send_near(6 * NEAR);
    let id = w.c.get_wallet().next_proposal_id.0;
    let i = intent(id, &action);
    let proof = w.card.tap(&domain::card_propose_challenge::<S>(&i));
    w.c.propose(action, SignerProof::Card { signer_index: 1, sig: proof }, None);
    w.card_approves(id);
    assert_eq!(w.c.get_proposal(U64(id)).unwrap().ready_ts, None);
    // ...and the phone's approval only arrives three days later
    w.advance(3 * DAY);
    let sig = w.phone.sign(&i);
    w.c.approve_p256(U64(id), 0, sig);
    assert_eq!(w.c.get_proposal(U64(id)).unwrap().ready_ts, Some(w.t));
    fails("Delay has not elapsed", || {
        w.c.execute(U64(id));
    });
    w.advance(DAY);
    w.c.execute(U64(id));
}

#[test]
fn unapproved_proposals_can_be_cleaned_up_after_a_day() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(NEAR));
    fails("not stale", || w.c.close_stale(U64(id)));
    w.advance(tapvault_contract::UNAPPROVED_TTL + 1);
    w.c.close_stale(U64(id));
    assert!(w.c.get_proposal(U64(id)).is_none());
}

fn new_wallet_in_setup(phone: &Phone) -> Tapvault {
    ctx(WALLET, T0, 1);
    Tapvault::new(phone.key(), phone.sign(&domain::create_message::<S>(acct(), &key_hash(&phone.key()))))
}

#[test]
fn only_the_wallet_account_can_initialise() {
    let phone = Phone::new(7);
    ctx(RELAYER, T0, 1);
    fails("Only the wallet account", || {
        Tapvault::new(phone.key(), phone.sign(&domain::create_message::<S>(acct(), &key_hash(&phone.key()))));
    });
}

#[test]
fn finalize_must_be_sent_by_the_wallet_account() {
    let phone = Phone::new(7);
    let mut card = Card::new();
    let mut c = new_wallet_in_setup(&phone);
    let ck = card.key();
    let ch = domain::card_enroll_challenge::<S>(acct(), 1, &key_hash(&ck));
    let auth = phone.sign(&domain::setup_add_message::<S>(acct(), 1, &key_hash(&ck)));
    c.setup_add_signer(ck, 0, auth, Possession::Card(card.tap(&ch)));

    let args = FinalizeArgs { threshold: 2, delays: DelaysCfg { transfer: DAY, config: DAY, recovery: 3 * DAY }, limits: vec![] };
    let sig = phone.sign(&domain::finalize_message::<S>(acct(), &near_sdk::borsh::to_vec(&args).unwrap()));
    ctx(RELAYER, T0, 1);
    fails("must be sent by the wallet account", || c.setup_finalize(args, 0, sig));
}

#[test]
fn freeze_is_refused_during_setup() {
    let phone = Phone::new(7);
    let mut c = new_wallet_in_setup(&phone);
    let sig = phone.sign(&domain::freeze_message::<S>(acct(), 0));
    fails("still in setup", || c.freeze(SignerProof::P256 { signer_index: 0, sig }));
}

#[test]
fn passkeys_must_require_user_verification() {
    let mut w = Wallet::new();
    let SignerKey::P256 { pubkey, .. } = Phone::new(9).key() else { unreachable!() };
    let lax = SignerKey::P256 { pubkey, webauthn: Some(WebAuthnConfig { rp_id_hash: b64(vec![1; 32]), require_uv: false }) };
    fails("Invalid signer key", || {
        w.propose_by_phone(Action::Config { ops: vec![ConfigOp::AddSigner(lax)] });
    });
}

// ---- contract v0.2 ----

fn new_phone_possession(p: &Phone) -> PossessionProof {
    PossessionProof::P256(p.sign(&domain::possession_message::<S>(acct(), &key_hash(&p.key()))))
}

#[test]
fn an_added_key_must_prove_possession() {
    let mut w = Wallet::new();
    let guardian = Phone::new(9);
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::AddSigner(guardian.key())] });
    w.card_approves(id);
    w.advance(DAY);
    fails("hasn't proved possession", || {
        w.c.execute(U64(id));
    });
    // a proof by another key, for another wallet, or for an op that adds nothing is refused
    fails("P-256 signature invalid", || w.c.prove_possession(U64(id), 0, new_phone_possession(&Phone::new(10))));
    let other_wallet = PossessionProof::P256(guardian.sign(&domain::possession_message::<S>(b"x.testnet", &key_hash(&guardian.key()))));
    fails("P-256 signature invalid", || w.c.prove_possession(U64(id), 0, other_wallet));
    fails("doesn't add a signer", || w.c.prove_possession(U64(id), 1, new_phone_possession(&guardian)));
    // anyone may send the guardian's own proof; then it executes
    w.c.prove_possession(U64(id), 0, new_phone_possession(&guardian));
    assert_eq!(w.c.get_proposal(U64(id)).unwrap().possessed, 1);
    w.c.execute(U64(id));
    assert_eq!(w.c.get_wallet().signers.len(), 3);
}

#[test]
fn possession_can_come_with_the_proposal() {
    let mut w = Wallet::new();
    let newp = Phone::new(11);
    let old = w.phone.key();
    // recovery-style change: remove the phone, add a new one, proposed by the card with the new key's proof
    let action = Action::Config { ops: vec![ConfigOp::RemoveSigner { key_hash: b64(key_hash(&old).to_vec()) }, ConfigOp::AddSigner(newp.key())] };
    w.as_relayer();
    let id = w.c.get_wallet().next_proposal_id.0;
    let proof = w.card.tap(&domain::card_propose_challenge::<S>(&intent(id, &action)));
    let poss = vec![OpPossession { op: 1, proof: new_phone_possession(&newp) }];
    w.c.propose(action, SignerProof::Card { signer_index: 1, sig: proof }, Some(poss));
    assert_eq!(w.c.get_proposal(U64(id)).unwrap().possessed, 2);
    // a bad proof in propose fails the whole propose
    let action2 = Action::Config { ops: vec![ConfigOp::AddSigner(Phone::new(12).key())] };
    let id2 = w.c.get_wallet().next_proposal_id.0;
    let sig2 = w.phone.sign(&intent(id2, &action2));
    fails("P-256 signature invalid", || {
        w.c.propose(action2, SignerProof::P256 { signer_index: 0, sig: sig2 }, Some(vec![OpPossession { op: 0, proof: new_phone_possession(&newp) }]));
    });
}

#[test]
fn the_view_returns_the_seed_so_clients_can_check_the_card_challenge() {
    let mut w = Wallet::new();
    let id = w.propose_by_phone(send_near(NEAR));
    assert!(w.c.get_proposal(U64(id)).unwrap().card_seed.is_none());
    let ch = w.seal(id);
    let p = w.c.get_proposal(U64(id)).unwrap();
    let mut seed = [0u8; 32];
    hex_into(p.card_seed.as_deref().unwrap(), &mut seed);
    // recomputed from the intent the client computed itself, not from the view's hash
    assert_eq!(domain::card_approve_challenge::<S>(&intent(id, &send_near(NEAR)), &seed), ch);
    assert_eq!(w.c.contract_version(), "0.4.0");
}

#[test]
fn with_an_allowlist_only_listed_receivers_are_instant() {
    let mut w = Wallet::new();
    let carol: near_sdk::AccountId = "carol.testnet".parse().unwrap();
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::AllowReceiver { receiver: carol.clone() }] });
    w.card_approves(id);
    w.advance(DAY);
    w.c.execute(U64(id));
    assert_eq!(w.c.get_wallet().allowlist, vec![carol.clone()]);

    // bob isn't listed: within the limit, but it waits the transfer delay
    let bob = w.propose_by_phone(send_near(NEAR));
    w.card_approves(bob);
    fails("Delay has not elapsed", || {
        w.c.execute(U64(bob));
    });
    // carol is instant
    let pay = Action::Transfer { asset: Asset::Near, amount: U128(NEAR), receiver: carol.clone() };
    let c = w.propose_by_phone(pay);
    w.card_approves(c);
    assert!(matches!(w.c.execute(U64(c)), PromiseOrValue::Promise(_)));
    // listing twice or removing an unknown receiver is refused at execute
    for op in [ConfigOp::AllowReceiver { receiver: carol.clone() }, ConfigOp::DisallowReceiver { receiver: "dave.testnet".parse().unwrap() }] {
        let id = w.propose_by_phone(Action::Config { ops: vec![op] });
        w.card_approves(id);
        w.advance(DAY);
        fails("llowlist", || {
            w.c.execute(U64(id));
        });
        w.cancel_by_phone(id);
    }
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::DisallowReceiver { receiver: carol }] });
    w.card_approves(id);
    w.advance(DAY);
    w.c.execute(U64(id));
    assert!(w.c.get_wallet().allowlist.is_empty());
}

#[test]
fn an_upgrade_waits_the_recovery_delay_and_needs_the_exact_code() {
    let mut w = Wallet::new();
    let code = b"\0asm new code".to_vec();
    let hash = near_sdk::env::sha256(&code);
    fails("only op", || {
        w.propose_by_phone(Action::Config { ops: vec![ConfigOp::UpgradeCode { code_hash: b64(hash.clone()) }, ConfigOp::Unfreeze] });
    });
    fails("Invalid action", || {
        w.propose_by_phone(Action::Config { ops: vec![ConfigOp::UpgradeCode { code_hash: b64(vec![1; 31]) }] });
    });
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::UpgradeCode { code_hash: b64(hash) }] });
    w.card_approves(id);
    w.advance(DAY);
    fails("execute_upgrade", || {
        w.c.execute(U64(id));
    });
    // phone + card approved, but an upgrade waits the recovery delay (3 days here)
    fails("Delay has not elapsed", || {
        w.c.execute_upgrade(id, code.clone());
    });
    w.advance(2 * DAY);
    fails("doesn't match", || {
        w.c.execute_upgrade(id, b"other".to_vec());
    });
    let other = w.propose_by_phone(send_near(NEAR));
    fails("Not an UpgradeCode", || {
        w.c.execute_upgrade(other, code.clone());
    });
    // a frozen wallet runs no new code, even when the upgrade is ready
    let ch = domain::card_freeze_challenge::<S>(acct(), w.c.get_wallet().freeze_nonce.0);
    let tap = w.card.tap(&ch);
    w.c.freeze(SignerProof::Card { signer_index: 1, sig: tap });
    fails("Wallet is frozen", || {
        w.c.execute_upgrade(id, code.clone());
    });
    // unfreezing is a config change, so it also voids the upgrade request
    let un = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::Unfreeze] });
    w.card_approves(un);
    w.advance(DAY);
    w.c.execute(U64(un));
    fails("Signer set changed", || {
        w.c.execute_upgrade(id, code.clone());
    });
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::UpgradeCode { code_hash: b64(near_sdk::env::sha256(&code)) }] });
    w.card_approves(id);
    w.advance(3 * DAY);
    let epoch = w.c.get_wallet().signer_epoch;
    w.c.execute_upgrade(id, code);
    assert!(w.c.get_proposal(U64(id)).is_none());
    assert_eq!(w.c.get_wallet().signer_epoch, epoch + 1, "open proposals are voided");
}

// ---- contract v0.3: phone-only wallets ----

fn finalize_threshold(c: &mut Tapvault, phone: &Phone, threshold: u8) {
    let args = FinalizeArgs { threshold, delays: DelaysCfg { transfer: DAY, config: DAY, recovery: 3 * DAY }, limits: vec![] };
    let sig = phone.sign(&domain::finalize_message::<S>(acct(), &near_sdk::borsh::to_vec(&args).unwrap()));
    c.setup_finalize(args, 0, sig);
}

#[test]
fn a_phone_only_wallet_needs_threshold_one_and_sends_with_the_phone_alone() {
    // one signer: threshold 2 can never be met, so it is refused
    let phone = Phone::new(7);
    let mut c = new_wallet_in_setup(&phone);
    fails("Invalid threshold", || finalize_threshold(&mut c, &phone, 2));

    let mut w = Wallet::phone_only();
    let v = w.c.get_wallet();
    assert!(!v.setup_mode);
    assert_eq!((v.threshold, v.signers.len()), (1, 1));
    // within the limits: the phone's proposal is its approval, so it executes at once
    let id = w.propose_by_phone(send_near(3 * NEAR));
    assert!(matches!(w.c.execute(U64(id)), PromiseOrValue::Promise(_)));
    assert_eq!(spent_near(&w.c), 3 * NEAR);
    // over the limit it still waits the transfer delay, and can be cancelled meanwhile
    let id = w.propose_by_phone(send_near(6 * NEAR));
    fails("Delay has not elapsed", || {
        w.c.execute(U64(id));
    });
    w.advance(DAY);
    w.c.execute(U64(id));
}

#[test]
fn threshold_one_is_refused_once_a_card_is_a_signer() {
    let phone = Phone::new(7);
    let mut card = Card::new();
    let mut c = new_wallet_in_setup(&phone);
    let ck = card.key();
    let ch = domain::card_enroll_challenge::<S>(acct(), 1, &key_hash(&ck));
    let auth = phone.sign(&domain::setup_add_message::<S>(acct(), 1, &key_hash(&ck)));
    c.setup_add_signer(ck, 0, auth, Possession::Card(card.tap(&ch)));
    fails("Invalid threshold", || finalize_threshold(&mut c, &phone, 1));

    // and a finished two-key wallet can't drop to one approval
    let mut w = Wallet::new();
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::SetThreshold(1)] });
    w.card_approves(id);
    w.advance(DAY);
    fails("Invalid threshold", || {
        w.c.execute(U64(id));
    });
}

#[test]
fn adding_the_first_card_later_must_raise_the_threshold() {
    let mut w = Wallet::phone_only();
    let ck = w.card.key();
    let poss = PossessionProof::Card(w.card.tap(&domain::card_possession_challenge::<S>(acct(), &key_hash(&ck))));

    // the card alone, threshold left at 1: refused, or the card could act alone.
    // (Another wallet: the mocked runtime keeps a failed call's writes; the chain doesn't.)
    let mut other = Wallet::phone_only();
    let lone = other.propose_by_phone(Action::Config { ops: vec![ConfigOp::AddSigner(ck.clone())] });
    other.c.prove_possession(U64(lone), 0, poss.clone());
    other.advance(DAY);
    fails("Invalid threshold", || {
        other.c.execute(U64(lone));
    });

    // with SetThreshold(2): the phone's approval is enough, after the config delay
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::AddSigner(ck), ConfigOp::SetThreshold(2)] });
    w.c.prove_possession(U64(id), 0, poss);
    fails("Delay has not elapsed", || {
        w.c.execute(U64(id));
    });
    w.advance(DAY);
    w.c.execute(U64(id));
    let v = w.c.get_wallet();
    assert_eq!((v.threshold, v.signers.len()), (2, 2));

    // from now on it is an ordinary two-key wallet: the phone alone can't pay
    let pay = w.propose_by_phone(send_near(NEAR));
    fails("Not enough approvals", || {
        w.c.execute(U64(pay));
    });
    w.card_approves(pay);
    assert!(matches!(w.c.execute(U64(pay)), PromiseOrValue::Promise(_)));
}

// ---- v0.4: inheritance ----

const HEIR: &str = "heir.testnet";

/// Name `heir.testnet` with `inactivity` seconds through a config proposal
/// (phone + card, then the 1-day config delay). Returns when it applied.
fn set_heir(w: &mut Wallet, inactivity: i64) -> i64 {
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::SetHeir { heir: HEIR.parse().unwrap(), inactivity }] });
    w.card_approves(id);
    w.advance(DAY);
    w.c.execute(U64(id));
    w.t
}

fn check_in_by_phone(w: &mut Wallet) {
    let nonce = w.c.get_inheritance().alive_nonce;
    let sig = w.phone.sign(&domain::alive_message::<S>(acct(), nonce));
    w.c.check_in(SignerProof::P256 { signer_index: 0, sig });
}

#[test]
fn an_heir_is_set_by_a_delayed_config_change_and_the_bounds_hold() {
    let mut w = Wallet::new();
    let v = w.c.get_inheritance();
    assert!(v.heir.is_none() && v.claimable_at.is_none());
    fails("This wallet has no heir", || w.c.start_inheritance_claim());

    // out of bounds: refused when proposed
    for bad in [3_599, 3651 * DAY] {
        fails("Invalid heir", || {
            w.propose_by_phone(Action::Config { ops: vec![ConfigOp::SetHeir { heir: HEIR.parse().unwrap(), inactivity: bad }] });
        });
    }
    // the wallet itself can't be its heir: refused at execute
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::SetHeir { heir: WALLET.parse().unwrap(), inactivity: DAY }] });
    w.card_approves(id);
    w.advance(DAY);
    fails("Invalid heir", || {
        w.c.execute(U64(id));
    });
    w.cancel_by_phone(id);

    let at = set_heir(&mut w, 30 * DAY);
    let v = w.c.get_inheritance();
    assert_eq!(v.heir.as_ref().map(|a| a.as_str()), Some(HEIR));
    assert_eq!(v.inactivity, 30 * DAY);
    assert_eq!(v.last_active, at);
    assert_eq!(v.claimable_at, Some(at + 30 * DAY + 1));
    assert_eq!(v.releasable_at, None);
}

#[test]
fn a_claim_needs_the_whole_inactivity_period_then_the_grace_period() {
    let mut w = Wallet::new();
    let at = set_heir(&mut w, 30 * DAY);
    w.advance(30 * DAY);
    fails("active within", || w.c.start_inheritance_claim());
    fails("No inheritance claim", || {
        w.c.release_to_heir(Asset::Near, None);
    });
    w.advance(1);
    w.c.start_inheritance_claim();
    let v = w.c.get_inheritance();
    assert_eq!(v.claim_ts, Some(at + 30 * DAY + 1));
    // grace = the config delay (1 day here)
    assert_eq!(v.releasable_at, Some(at + 30 * DAY + 1 + DAY));
    // starting again doesn't move the claim
    w.advance(10);
    w.c.start_inheritance_claim();
    assert_eq!(w.c.get_inheritance().claim_ts, Some(at + 30 * DAY + 1));
    fails("grace period", || {
        w.c.release_to_heir(Asset::Near, None);
    });
    w.advance(DAY);
    // anyone may release, and it only goes to the heir; frozen or not
    assert!(catch_unwind(AssertUnwindSafe(|| w.c.release_to_heir(Asset::Near, None))).is_ok());
    w.c.release_to_heir(Asset::Ft { token: "usdc.testnet".parse().unwrap() }, Some(U128(5_000_000)));
    fails("Nothing to release", || {
        w.c.release_to_heir(Asset::Ft { token: "usdc.testnet".parse().unwrap() }, None);
    });
}

#[test]
fn any_owner_signature_voids_a_claim() {
    let mut w = Wallet::new();
    set_heir(&mut w, DAY);

    // a phone check-in
    w.advance(DAY + 1);
    w.c.start_inheritance_claim();
    w.advance(60);
    check_in_by_phone(&mut w);
    let v = w.c.get_inheritance();
    assert_eq!((v.claim_ts, v.last_active, v.alive_nonce), (None, w.t, 1));
    w.advance(DAY);
    fails("No inheritance claim", || {
        w.c.release_to_heir(Asset::Near, None);
    });
    // a check-in signature is single-use
    let old = w.phone.sign(&domain::alive_message::<S>(acct(), 0));
    fails("P-256 signature invalid", || w.c.check_in(SignerProof::P256 { signer_index: 0, sig: old }));

    // a card check-in
    w.advance(DAY + 1);
    w.c.start_inheritance_claim();
    let ch = domain::card_alive_challenge::<S>(acct(), w.c.get_inheritance().alive_nonce);
    let sig = w.card.tap(&ch);
    w.c.check_in(SignerProof::Card { signer_index: 1, sig });
    assert_eq!(w.c.get_inheritance().claim_ts, None);

    // a claim started, then the owner proposes a payment: also void
    w.advance(DAY + 1);
    w.c.start_inheritance_claim();
    w.advance(DAY - 10);
    let id = w.propose_by_phone(send_near(NEAR));
    assert_eq!(w.c.get_inheritance().claim_ts, None);
    w.advance(20);
    fails("No inheritance claim", || {
        w.c.release_to_heir(Asset::Near, None);
    });
    // a card approval counts too: the timer restarts from it
    w.card_approves(id);
    assert_eq!(w.c.get_inheritance().last_active, w.t);

    // and a freeze
    w.advance(DAY + 1);
    w.c.start_inheritance_claim();
    let nonce = w.c.get_wallet().freeze_nonce.0;
    let sig = w.phone.sign(&domain::freeze_message::<S>(acct(), nonce));
    w.c.freeze(SignerProof::P256 { signer_index: 0, sig });
    assert_eq!(w.c.get_inheritance().claim_ts, None);
}

#[test]
fn removing_the_heir_ends_inheritance() {
    let mut w = Wallet::new();
    set_heir(&mut w, DAY);
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::RemoveHeir] });
    w.card_approves(id);
    w.advance(DAY);
    w.c.execute(U64(id));
    assert!(w.c.get_inheritance().heir.is_none());
    w.advance(10 * DAY);
    fails("This wallet has no heir", || w.c.start_inheritance_claim());
    fails("This wallet has no heir", || check_in_by_phone(&mut w));
    // removing again is refused at execute
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::RemoveHeir] });
    w.card_approves(id);
    w.advance(DAY);
    fails("This wallet has no heir", || {
        w.c.execute(U64(id));
    });
}

#[test]
fn a_phone_only_wallet_can_name_an_heir_with_the_phone_alone() {
    let mut w = Wallet::phone_only();
    let id = w.propose_by_phone(Action::Config { ops: vec![ConfigOp::SetHeir { heir: HEIR.parse().unwrap(), inactivity: 3_600 }] });
    w.advance(DAY);
    w.c.execute(U64(id));
    w.advance(3_601);
    w.c.start_inheritance_claim();
    w.advance(DAY);
    w.c.release_to_heir(Asset::Near, Some(U128(NEAR)));
}
