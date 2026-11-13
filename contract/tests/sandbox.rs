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
use near_workspaces::operations::Function;
use near_workspaces::types::Gas as NearGas;
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
    let acct = wallet.id().as_str().as_bytes().to_vec();

    // `new` is private: on a deployed but uninitialised wallet, an outsider
    // can't race to initialise it with their own key.
    let victim = worker.dev_create_account().await?;
    victim.deploy(&wasm).await?.into_result()?;
    let thief = SigningKey::from_slice(&[9; 32]).unwrap();
    let tk = phone_key(&thief);
    let vmsg = domain::create_message::<S>(victim.id().as_str().as_bytes(), &key_hash(&tk));
    let r = relayer
        .call(victim.id(), "new")
        .args_json(json!({ "first_signer": tk, "sig": phone_sign(&thief, &vmsg) }))
        .max_gas()
        .transact()
        .await?;
    assert!(r.is_failure(), "outsider initialised someone else's wallet");

    let phone = SigningKey::from_slice(&[7; 32]).unwrap();
    let mut small = TestCard::default();
    let mut large = TestCard::large();
    let mut worst: f64 = 0.0;
    println!("\n  step                                  gas");

    // ---- setup (sent by the wallet account itself) ----
    // Deploy and initialise in one transaction, as the app does.
    let pk = phone_key(&phone);
    let r = wallet
        .batch(wallet.id())
        .deploy(&wasm)
        .call(
            Function::new("new")
                .args_json(json!({ "first_signer": pk, "sig": phone_sign(&phone, &domain::create_message::<S>(&acct, &key_hash(&pk))) }))
                .gas(NearGas::from_tgas(100)),
        )
        .transact()
        .await?;
    worst = worst.max(report("deploy + new", &r, wallet.id()));
    let contract = near_workspaces::Contract::from_secret_key(wallet.id().clone(), wallet.secret_key().clone(), &worker);

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

    // ---- v0.2: version view, seed in the proposal view, guardian with possession, upgrade ----
    let v: String = contract.view("contract_version").await?.json()?;
    assert_eq!(v, "0.3.0");

    let propose_config = |ops: Vec<ConfigOp>, possession: Value| {
        let relayer = relayer.clone();
        let contract = contract.clone();
        let acct = acct.clone();
        let phone = phone.clone();
        async move {
            let action = Action::Config { ops };
            let state: Value = contract.view("get_wallet").await?.json()?;
            let id: u64 = state["next_proposal_id"].as_str().unwrap().parse()?;
            let intent = domain::intent_hash::<S>(&acct, id, &near_sdk::borsh::to_vec(&action)?);
            let r = relayer
                .call(contract.id(), "propose")
                .args_json(json!({ "action": action, "proof": SignerProof::P256 { signer_index: 0, sig: phone_sign(&phone, &intent) }, "possession": possession }))
                .max_gas()
                .transact()
                .await?;
            anyhow::Ok((id, intent, r))
        }
    };

    // a guardian key with its possession proof in the propose call
    let guardian = SigningKey::from_slice(&[11; 32]).unwrap();
    let gk = phone_key(&guardian);
    let gproof = PossessionProof::P256(phone_sign(&guardian, &domain::possession_message::<S>(&acct, &key_hash(&gk))));
    let (gid, gintent, r) = propose_config(vec![ConfigOp::AddSigner(gk)], json!([OpPossession { op: 0, proof: gproof }])).await?;
    worst = worst.max(report("propose AddSigner + possession", &r, wallet.id()));
    let p: Value = contract.view("get_proposal").args_json(json!({ "id": gid.to_string() })).await?.json()?;
    assert_eq!(p["possessed"], 1);
    let seed = unhex32(p["card_seed"].as_str().expect("seed in the view"));
    let ch = domain::card_approve_challenge::<S>(&gintent, &seed);
    assert_eq!(p["card_challenge"].as_str().unwrap(), ch.iter().map(|b| format!("{b:02x}")).collect::<String>());
    let r = relayer
        .call(contract.id(), "approve_card")
        .args_json(json!({ "id": gid.to_string(), "signer_index": 1, "sig": card_tap(&mut small, &ch) }))
        .max_gas()
        .transact()
        .await?;
    assert!(r.is_success(), "{:?}", r.failures());

    // upgrade to the same code: approved by phone + card, refused before the recovery delay
    let code_hash = near_sdk::env::sha256(&wasm);
    let (uid, uintent, r) = propose_config(vec![ConfigOp::UpgradeCode { code_hash: b64(code_hash) }], Value::Null).await?;
    assert!(r.is_success(), "{:?}", r.failures());
    let p: Value = contract.view("get_proposal").args_json(json!({ "id": uid.to_string() })).await?.json()?;
    let ch = domain::card_approve_challenge::<S>(&uintent, &unhex32(p["card_seed"].as_str().unwrap()));
    let r = relayer
        .call(contract.id(), "approve_card")
        .args_json(json!({ "id": uid.to_string(), "signer_index": 1, "sig": card_tap(&mut small, &ch) }))
        .max_gas()
        .transact()
        .await?;
    assert!(r.is_success(), "{:?}", r.failures());
    let early = relayer.call(contract.id(), "execute_upgrade").args_borsh((uid, wasm.clone())).max_gas().transact().await?;
    assert!(format!("{:?}", early.failures()).contains("Delay has not elapsed"), "{:?}", early.failures());
    println!("  execute_upgrade before the delay: refused");
    // Sandbox blocks move the clock only ~0.3 s each, so a day of blocks would
    // take hours. Instead move this proposal's ready_ts back 25 h in the
    // contract state (the delay rule itself is covered by flows.rs); what this
    // checks is the real deploy + migrate on the compiled wasm.
    {
        use near_sdk::borsh::{self, BorshDeserialize};
        let state = worker.view_state(contract.id()).await?;
        let mut patched = false;
        for (k, v) in state.iter() {
            let mut rest: &[u8] = v;
            let Ok(mut prop) = Proposal::deserialize(&mut rest) else { continue };
            if prop.intent_hash != uintent {
                continue;
            }
            prop.ready_ts = Some(prop.ready_ts.expect("approved") - 25 * 3600);
            let mut bytes = borsh::to_vec(&prop)?;
            bytes.extend_from_slice(rest); // IterableMap keeps the entry's index after the value
            worker.patch_state(contract.id(), k, &bytes).await?;
            patched = true;
        }
        assert!(patched, "upgrade proposal found in state");
        println!("  ready_ts moved back 25 h in state (sandbox clock is too slow to wait a day)");
    }
    let wrong = relayer.call(contract.id(), "execute_upgrade").args_borsh((uid, b"nope".to_vec())).max_gas().transact().await?;
    assert!(format!("{:?}", wrong.failures()).contains("doesn't match"), "{:?}", wrong.failures());
    let r = relayer.call(contract.id(), "execute_upgrade").args_borsh((uid, wasm.clone())).max_gas().transact().await?;
    worst = worst.max(report("execute_upgrade (deploy + migrate)", &r, wallet.id()));
    let after: Value = contract.view("get_wallet").await?.json()?;
    assert_eq!(after["signers"].as_array().unwrap().len(), 3, "state kept across the upgrade");
    // the guardian proposal was voided by the upgrade's epoch bump
    let g: Value = contract.view("get_proposal").args_json(json!({ "id": gid.to_string() })).await?.json()?;
    assert_eq!(g["stale"], true);

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

/// v0.3 on the compiled wasm: a wallet set up without a card pays with the
/// phone alone; adding the card later (with `SetThreshold(2)`) makes it an
/// ordinary two-key wallet.
#[tokio::test]
#[ignore = "downloads a nearcore sandbox binary; run with --ignored"]
async fn phone_only_wallet_then_add_the_card() -> anyhow::Result<()> {
    let worker = near_workspaces::sandbox_with_version("2.6.5").await?;
    let wasm = near_workspaces::compile_project("./").await?;
    let wallet = worker.dev_create_account().await?;
    let relayer = worker.dev_create_account().await?;
    let bob = worker.dev_create_account().await?;
    let acct = wallet.id().as_str().as_bytes().to_vec();
    let phone = SigningKey::from_slice(&[7; 32]).unwrap();
    let mut card = TestCard::default();
    println!("\n  phone-only step                       gas");

    let pk = phone_key(&phone);
    let r = wallet
        .batch(wallet.id())
        .deploy(&wasm)
        .call(
            Function::new("new")
                .args_json(json!({ "first_signer": pk, "sig": phone_sign(&phone, &domain::create_message::<S>(&acct, &key_hash(&pk))) }))
                .gas(NearGas::from_tgas(100)),
        )
        .transact()
        .await?;
    report("deploy + new", &r, wallet.id());
    let contract = near_workspaces::Contract::from_secret_key(wallet.id().clone(), wallet.secret_key().clone(), &worker);

    let args = FinalizeArgs {
        threshold: 1,
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
    report("setup_finalize (threshold 1)", &r, wallet.id());
    assert!(wallet.view_access_keys().await?.is_empty(), "deployer key should be deleted");

    let propose = |action: Action, possession: Value| {
        let relayer = relayer.clone();
        let contract = contract.clone();
        let acct = acct.clone();
        let phone = phone.clone();
        async move {
            let state: Value = contract.view("get_wallet").await?.json()?;
            let id: u64 = state["next_proposal_id"].as_str().unwrap().parse()?;
            let intent = domain::intent_hash::<S>(&acct, id, &near_sdk::borsh::to_vec(&action)?);
            let r = relayer
                .call(contract.id(), "propose")
                .args_json(json!({ "action": action, "proof": SignerProof::P256 { signer_index: 0, sig: phone_sign(&phone, &intent) }, "possession": possession }))
                .max_gas()
                .transact()
                .await?;
            anyhow::Ok((id, intent, r))
        }
    };
    let pay = || Action::Transfer {
        asset: Asset::Near,
        amount: U128(NearToken::from_near(1).as_yoctonear()),
        receiver: bob.id().as_str().parse().unwrap(),
    };
    let execute = |id: u64| {
        let relayer = relayer.clone();
        let contract = contract.clone();
        async move { relayer.call(contract.id(), "execute").args_json(json!({ "id": id.to_string() })).max_gas().transact().await }
    };

    // the phone alone pays within the limits
    let (id, _, r) = propose(pay(), Value::Null).await?;
    report("propose (phone only)", &r, wallet.id());
    let before = bob.view_account().await?.balance;
    let r = execute(id).await?;
    report("execute (NEAR transfer)", &r, wallet.id());
    let after = bob.view_account().await?.balance;
    assert_eq!(after.as_yoctonear() - before.as_yoctonear(), NearToken::from_near(1).as_yoctonear());

    // add the card: AddSigner + SetThreshold(2), approved by the phone's proposal, waits the config delay
    let ck = card_key(&card);
    let poss = PossessionProof::Card(card_tap(&mut card, &domain::card_possession_challenge::<S>(&acct, &key_hash(&ck))));
    let (cid, cintent, r) = propose(
        Action::Config { ops: vec![ConfigOp::AddSigner(ck), ConfigOp::SetThreshold(2)] },
        json!([OpPossession { op: 0, proof: poss }]),
    )
    .await?;
    report("propose AddSigner(card) + threshold 2", &r, wallet.id());
    let early = execute(cid).await?;
    assert!(format!("{:?}", early.failures()).contains("Delay has not elapsed"), "{:?}", early.failures());
    {
        use near_sdk::borsh::{self, BorshDeserialize};
        let state = worker.view_state(contract.id()).await?;
        let mut patched = false;
        for (k, v) in state.iter() {
            let mut rest: &[u8] = v;
            let Ok(mut prop) = Proposal::deserialize(&mut rest) else { continue };
            if prop.intent_hash != cintent {
                continue;
            }
            prop.ready_ts = Some(prop.ready_ts.expect("ready with the phone's approval") - 2 * 3600);
            let mut bytes = borsh::to_vec(&prop)?;
            bytes.extend_from_slice(rest);
            worker.patch_state(contract.id(), k, &bytes).await?;
            patched = true;
        }
        assert!(patched, "add-card proposal found in state");
    }
    let r = execute(cid).await?;
    report("execute (card added)", &r, wallet.id());
    let state: Value = contract.view("get_wallet").await?.json()?;
    assert_eq!(state["threshold"], 2);
    assert_eq!(state["signers"].as_array().unwrap().len(), 2);

    // now the phone alone can't pay
    let (id, _, r) = propose(pay(), Value::Null).await?;
    assert!(r.is_success(), "{:?}", r.failures());
    let refused = execute(id).await?;
    assert!(format!("{:?}", refused.failures()).contains("Not enough approvals"), "{:?}", refused.failures());
    println!("  phone alone after the card was added: refused\n");
    Ok(())
}
