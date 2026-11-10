//! The compiled contract on a local NEAR sandbox node: full flow with a
//! software phone key and two simulated cards, printing the gas each step
//! burns. Opt-in, since it downloads a nearcore sandbox binary:
//! `cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture`.
//!
//! Signer 1 is a typical card (1024-bit, e = 3); signer 2 is the most
//! expensive key EMV allows (1984-bit, e = 65537).

use near_sdk::json_types::{Base64VecU8, U128};
use near_workspaces::result::ExecutionFinalResult;
use near_workspaces::types::NearToken;
use near_workspaces::AccountId;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use serde_json::{json, Value};
use tapvault_contract::setup::Possession;
use tapvault_contract::types::*;
use tapvault_core::testcard::TestCard;
use tapvault_core::{domain, SoftSha256 as S};

/// NEAR's per-transaction (and per-receipt) gas ceiling.
const MAX_TGAS: u64 = 300;

fn b64(v: impl Into<Vec<u8>>) -> Base64VecU8 {
    Base64VecU8(v.into())
}

fn phone_key(sk: &SigningKey) -> SignerKey {
    SignerKey::P256 { pubkey: b64(sk.verifying_key().to_encoded_point(true).as_bytes()), webauthn: None }
}

fn phone_sign(sk: &SigningKey, msg: &[u8; 32]) -> P256Sig {
    let s: Signature = sk.sign(msg);
    P256Sig { signature: b64(s.to_bytes().to_vec()), webauthn: None }
}

fn card_key(c: &TestCard) -> SignerKey {
    SignerKey::EmvCard {
        modulus: b64(c.modulus.clone()),
        exponent: c.exponent,
        un_offset: TestCard::UN_OFFSET,
        un_len: TestCard::UN_LEN,
        terminal_data_len: TestCard::TERMINAL_DATA_LEN,
    }
}

fn card_tap(c: &mut TestCard, ch: &[u8; 32]) -> CardSig {
    let (sdad, td) = c.sign_challenge(ch);
    CardSig { sdad: b64(sdad), terminal_data: b64(td) }
}

fn key_hash(k: &SignerKey) -> [u8; 32] {
    match k {
        SignerKey::P256 { pubkey, .. } => domain::key_hash_p256::<S>(&pubkey.0),
        SignerKey::EmvCard { modulus, exponent, .. } => domain::key_hash_emv::<S>(&modulus.0, *exponent),
    }
}

fn unhex32(s: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap();
    }
    out
}

/// Print the gas each of the wallet contract's receipts burned and return
/// the largest, in Tgas. (Refund and transfer receipts elsewhere are ~0.)
fn report(label: &str, r: &ExecutionFinalResult, wallet: &AccountId) -> f64 {
    assert!(r.is_success(), "{label} failed: {:?}", r.failures());
    let mut max: f64 = 0.0;
    for o in r.receipt_outcomes().iter().filter(|o| &o.executor_id == wallet) {
        let tgas = o.gas_burnt.as_gas() as f64 / 1e12;
        println!("  {label:<34} {tgas:>6.1} Tgas");
        max = max.max(tgas);
    }
    max
}

#[tokio::test]
#[ignore = "downloads a nearcore sandbox binary; run with --ignored"]
async fn full_flow_and_gas() -> anyhow::Result<()> {
    // Pinned: newer nodes changed an RPC field the 0.20 client still expects.
    let worker = near_workspaces::sandbox_with_version("2.6.5").await?;
    let wasm = near_workspaces::compile_project("./").await?;
    let wallet = worker.dev_create_account().await?;
    let relayer = worker.dev_create_account().await?;
    let bob = worker.dev_create_account().await?;
    let contract = wallet.deploy(&wasm).await?.into_result()?;
    let acct = wallet.id().as_str().as_bytes().to_vec();

    let phone = SigningKey::from_slice(&[7; 32]).unwrap();
    let mut small = TestCard::default();
    let mut large = TestCard::large();
    let mut worst: f64 = 0.0;
    println!("\n  step                                  gas");

    // ---- setup (sent by the wallet account itself) ----
    let pk = phone_key(&phone);
    let r = wallet
        .call(contract.id(), "new")
        .args_json(json!({ "first_signer": pk, "sig": phone_sign(&phone, &domain::create_message::<S>(&acct, &key_hash(&pk))) }))
        .max_gas()
        .transact()
        .await?;
    worst = worst.max(report("new", &r, wallet.id()));

    for (i, card) in [&mut small, &mut large].into_iter().enumerate() {
        let ck = card_key(card);
        let count = 1 + i as u8;
        let auth = phone_sign(&phone, &domain::setup_add_message::<S>(&acct, count, &key_hash(&ck)));
        let proof = card_tap(card, &domain::card_enroll_challenge::<S>(&acct, count, &key_hash(&ck)));
        let r = wallet
            .call(contract.id(), "setup_add_signer")
            .args_json(json!({ "new_signer": ck, "authorizer_index": 0, "authorizer_sig": auth, "possession": Possession::Card(proof) }))
            .max_gas()
            .transact()
            .await?;
        worst = worst.max(report(&format!("setup_add_signer (card {})", count), &r, wallet.id()));
    }

    let args = FinalizeArgs {
        threshold: 2,
        delays: DelaysCfg { transfer: 3_600, config: 3_600, recovery: 86_400 },
        limits: vec![LimitInit {
            asset: Asset::Near,
            instant_max: U128(NearToken::from_near(2).as_yoctonear()),
            daily_max: U128(NearToken::from_near(5).as_yoctonear()),
        }],
    };
    let msg = domain::finalize_message::<S>(&acct, &near_sdk::borsh::to_vec(&args)?);
    let r = wallet
        .call(contract.id(), "setup_finalize")
        .args_json(json!({ "args": args, "authorizer_index": 0, "sig": phone_sign(&phone, &msg) }))
        .max_gas()
        .transact()
        .await?;
    worst = worst.max(report("setup_finalize", &r, wallet.id()));
    assert!(wallet.view_access_keys().await?.is_empty(), "deployer key should be deleted");

    // ---- pay bob 1 NEAR: phone proposes, each card approves in turn ----
    for (signer_index, card) in [(1u8, &mut small), (2u8, &mut large)] {
        let action = Action::Transfer {
            asset: Asset::Near,
            amount: U128(NearToken::from_near(1).as_yoctonear()),
            receiver: bob.id().as_str().parse()?,
        };
        let state: Value = contract.view("get_wallet").await?.json()?;
        let id: u64 = state["next_proposal_id"].as_str().unwrap().parse()?;
        let intent = domain::intent_hash::<S>(&acct, id, &near_sdk::borsh::to_vec(&action)?);
        let r = relayer
            .call(contract.id(), "propose")
            .args_json(json!({ "action": action, "proof": SignerProof::P256 { signer_index: 0, sig: phone_sign(&phone, &intent) } }))
            .max_gas()
            .transact()
            .await?;
        worst = worst.max(report("propose (+ seal_challenge)", &r, wallet.id()));

        // seal_challenge ran in a later block within the same transaction
        let p: Value = contract.view("get_proposal").args_json(json!({ "id": id.to_string() })).await?.json()?;
        let ch = unhex32(p["card_challenge"].as_str().expect("challenge sealed"));
        let r = relayer
            .call(contract.id(), "approve_card")
            .args_json(json!({ "id": id.to_string(), "signer_index": signer_index, "sig": card_tap(card, &ch) }))
            .max_gas()
            .transact()
            .await?;
        worst = worst.max(report(&format!("approve_card ({}-bit)", card.modulus.len() * 8), &r, wallet.id()));

        let before = bob.view_account().await?.balance;
        let r = relayer
            .call(contract.id(), "execute")
            .args_json(json!({ "id": id.to_string() }))
            .max_gas()
            .transact()
            .await?;
        worst = worst.max(report("execute (NEAR transfer)", &r, wallet.id()));
        let after = bob.view_account().await?.balance;
        assert_eq!(after.as_yoctonear() - before.as_yoctonear(), NearToken::from_near(1).as_yoctonear());
    }

    // ---- freeze with a card tap ----
    let ch: Value = contract.view("freeze_challenge").await?.json()?;
    let tap = card_tap(&mut small, &unhex32(ch["card_challenge"].as_str().unwrap()));
    let r = relayer
        .call(contract.id(), "freeze")
        .args_json(json!({ "proof": SignerProof::Card { signer_index: 1, sig: tap } }))
        .max_gas()
        .transact()
        .await?;
    worst = worst.max(report("freeze (card)", &r, wallet.id()));
    let state: Value = contract.view("get_wallet").await?.json()?;
    assert_eq!(state["frozen"], true);

    println!("\n  most expensive single receipt: {worst:.1} Tgas (limit {MAX_TGAS})\n");
    assert!(worst < MAX_TGAS as f64);
    Ok(())
}
