//! Reference vectors for other implementations of the client (the Android
//! app's Kotlin core). They pin the exact bytes every signer signs.
//!
//! `UPDATE_VECTORS=1 cargo test -p tapvault-contract --test vectors` rewrites
//! android/core/src/test/resources/vectors.json; otherwise the test fails if
//! the file is out of date.

use near_sdk::json_types::{Base64VecU8, U128};
use serde_json::{json, Value};
use tapvault_contract::types::*;
use tapvault_core::testcard::TestCard;
use tapvault_core::{domain, SoftSha256 as S};

const PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../android/core/src/test/resources/vectors.json");
const ACCOUNT: &str = "tv-demo.testnet";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn borsh<T: near_sdk::borsh::BorshSerialize>(t: &T) -> Vec<u8> {
    near_sdk::borsh::to_vec(t).unwrap()
}

fn phone_key() -> SignerKey {
    // compressed P-256 point of the scalar 7 (any valid point works here)
    let pk = p256::ecdsa::SigningKey::from_slice(&[7; 32]).unwrap();
    SignerKey::P256 {
        pubkey: Base64VecU8(pk.verifying_key().to_encoded_point(true).as_bytes().to_vec()),
        webauthn: None,
    }
}

fn card_key() -> SignerKey {
    let c = TestCard::default();
    SignerKey::EmvCard { modulus: Base64VecU8(c.modulus), exponent: c.exponent, un_offset: 0, un_len: 4, terminal_data_len: 4 }
}

fn key_hash(k: &SignerKey) -> [u8; 32] {
    match k {
        SignerKey::P256 { pubkey, .. } => domain::key_hash_p256::<S>(&pubkey.0),
        SignerKey::EmvCard { modulus, exponent, .. } => domain::key_hash_emv::<S>(&modulus.0, *exponent),
    }
}

fn actions() -> Vec<(&'static str, Action)> {
    let usdc: near_sdk::AccountId = "usdc.fakes.testnet".parse().unwrap();
    vec![
        (
            "send_near",
            Action::Transfer { asset: Asset::Near, amount: U128(1_500_000_000_000_000_000_000_000), receiver: "bob.testnet".parse().unwrap() },
        ),
        ("send_ft", Action::Transfer { asset: Asset::Ft { token: usdc.clone() }, amount: U128(25_000_000), receiver: "shop.testnet".parse().unwrap() }),
        (
            "config_all_ops",
            Action::Config {
                ops: vec![
                    ConfigOp::AddSigner(card_key()),
                    ConfigOp::RemoveSigner { key_hash: Base64VecU8(key_hash(&phone_key()).to_vec()) },
                    ConfigOp::SetThreshold(3),
                    ConfigOp::SetLimit(LimitInit { asset: Asset::Ft { token: usdc }, instant_max: U128(10), daily_max: U128(20) }),
                ],
            },
        ),
        (
            "config_more_ops",
            Action::Config {
                ops: vec![
                    ConfigOp::RemoveLimit { asset: Asset::Near },
                    ConfigOp::SetDelays(DelaysCfg { transfer: 3_600, config: 7_200, recovery: 86_400 }),
                    ConfigOp::Unfreeze,
                    ConfigOp::AddSigner(phone_key()),
                ],
            },
        ),
        (
            "config_v02_ops",
            Action::Config {
                ops: vec![
                    ConfigOp::AllowReceiver { receiver: "carol.testnet".parse().unwrap() },
                    ConfigOp::DisallowReceiver { receiver: "dave.testnet".parse().unwrap() },
                ],
            },
        ),
        ("config_upgrade", Action::Config { ops: vec![ConfigOp::UpgradeCode { code_hash: Base64VecU8(vec![0x33; 32]) }] }),
        (
            "config_v04_ops",
            Action::Config {
                ops: vec![ConfigOp::SetHeir { heir: "heir.testnet".parse().unwrap(), inactivity: 15_552_000 }, ConfigOp::RemoveHeir],
            },
        ),
    ]
}

fn build() -> Value {
    let acct = ACCOUNT.as_bytes();
    let intent_id = 5u64;
    let acts: Vec<Value> = actions()
        .into_iter()
        .map(|(name, a)| {
            let b = borsh(&a);
            json!({
                "name": name,
                "json": serde_json::to_value(&a).unwrap(),
                "borsh": hex(&b),
                "intent_id": intent_id,
                "intent": hex(&domain::intent_hash::<S>(acct, intent_id, &b)),
            })
        })
        .collect();

    let finalize = FinalizeArgs {
        threshold: 2,
        delays: DelaysCfg { transfer: 3_600, config: 3_600, recovery: 86_400 },
        limits: vec![LimitInit { asset: Asset::Near, instant_max: U128(1_000_000_000_000_000_000_000_000), daily_max: U128(2_000_000_000_000_000_000_000_000) }],
    };
    let pk = phone_key();
    let ck = card_key();
    let intent = [0x11u8; 32];
    let seed = [0x22u8; 32];
    json!({
        "account": ACCOUNT,
        "phone_key": serde_json::to_value(&pk).unwrap(),
        "phone_key_borsh": hex(&borsh(&pk)),
        "phone_key_hash": hex(&key_hash(&pk)),
        "card_key_borsh": hex(&borsh(&ck)),
        "card_key_hash": hex(&key_hash(&ck)),
        "actions": acts,
        "finalize_args": serde_json::to_value(&finalize).unwrap(),
        "finalize_borsh": hex(&borsh(&finalize)),
        "messages": {
            "create": hex(&domain::create_message::<S>(acct, &key_hash(&pk))),
            "setup_add_1_card": hex(&domain::setup_add_message::<S>(acct, 1, &key_hash(&ck))),
            "card_enroll_1": hex(&domain::card_enroll_challenge::<S>(acct, 1, &key_hash(&ck))),
            "finalize": hex(&domain::finalize_message::<S>(acct, &borsh(&finalize))),
            "card_approve": hex(&domain::card_approve_challenge::<S>(&intent, &seed)),
            "card_propose": hex(&domain::card_propose_challenge::<S>(&intent)),
            "cancel": hex(&domain::cancel_message::<S>(&intent)),
            "card_cancel": hex(&domain::card_cancel_challenge::<S>(&intent)),
            "freeze_3": hex(&domain::freeze_message::<S>(acct, 3)),
            "card_freeze_3": hex(&domain::card_freeze_challenge::<S>(acct, 3)),
            "possess_card": hex(&domain::possession_message::<S>(acct, &key_hash(&ck))),
            "card_possess_card": hex(&domain::card_possession_challenge::<S>(acct, &key_hash(&ck))),
            "alive_3": hex(&domain::alive_message::<S>(acct, 3)),
            "card_alive_3": hex(&domain::card_alive_challenge::<S>(acct, 3)),
            "intent_input": hex(&intent),
            "seed_input": hex(&seed),
        },
    })
}

#[test]
fn vectors_are_current() {
    let want = serde_json::to_string_pretty(&build()).unwrap() + "\n";
    if std::env::var("UPDATE_VECTORS").is_ok() {
        std::fs::create_dir_all(std::path::Path::new(PATH).parent().unwrap()).unwrap();
        std::fs::write(PATH, &want).unwrap();
        return;
    }
    let have = std::fs::read_to_string(PATH).unwrap_or_default();
    assert!(have == want, "{PATH} is stale: run with UPDATE_VECTORS=1");
}
