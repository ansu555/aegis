//! Signature checks for the two signer kinds.
//!
//! P-256: verified in-contract (NEAR has no P-256 host function). The digest
//! comes from the sha256 host function and the curve math from `p256`.
//!
//! EMV card: RSA recovery + SHA-1 check of the card's SDAD, from
//! `tapvault-core`.

use near_sdk::env;
use p256::ecdsa::signature::hazmat::PrehashVerifier;
use p256::ecdsa::{Signature, VerifyingKey};
use tapvault_core::{emv, webauthn, Sha256Hasher};

use crate::error::*;
use crate::types::*;

pub struct NearSha256;

impl Sha256Hasher for NearSha256 {
    fn hashv(parts: &[&[u8]]) -> [u8; 32] {
        if let [one] = parts {
            return env::sha256_array(one);
        }
        env::sha256_array(&parts.concat())
    }
}

/// Require that `key` (a P-256 signer) signed the 32-byte `message`.
pub fn verify_p256(key: &SignerKey, sig: &P256Sig, message: &[u8; 32]) {
    let (pubkey, cfg) = match key {
        SignerKey::P256 { pubkey, webauthn } => (pubkey, webauthn),
        _ => env::panic_str(ERR_WRONG_SIGNER_KIND),
    };
    let signed: Vec<u8> = match cfg {
        None => message.to_vec(),
        Some(cfg) => {
            let p = sig.webauthn.as_ref().unwrap_or_else(|| env::panic_str(ERR_MISSING_WEBAUTHN));
            let a = webauthn::Assertion {
                authenticator_data: &p.authenticator_data.0,
                client_data_json: &p.client_data_json.0,
                challenge_index: p.challenge_index,
                type_index: p.type_index,
            };
            let rp: [u8; 32] = cfg.rp_id_hash.0.as_slice().try_into().unwrap();
            webauthn::signed_message::<NearSha256>(&a, message, &rp, cfg.require_uv).unwrap_or_else(|e| {
                env::log_str(&format!("webauthn: {e}"));
                env::panic_str(ERR_WEBAUTHN_INVALID)
            })
        }
    };
    check(p256_ok(&pubkey.0, &sig.signature.0, &signed), ERR_P256_INVALID);
}

/// ECDSA-P256-SHA256 verification of `msg`.
pub fn p256_ok(pubkey: &[u8], signature: &[u8], msg: &[u8]) -> bool {
    if signature.len() != P256_SIG_LEN {
        return false;
    }
    let (Ok(vk), Ok(sig)) = (VerifyingKey::from_sec1_bytes(pubkey), Signature::from_slice(signature)) else {
        return false;
    };
    vk.verify_prehash(&env::sha256_array(msg), &sig).is_ok()
}

/// Require that card `key` produced `sig` over terminal data whose
/// Unpredictable Number is the prefix of `challenge`.
pub fn verify_card(key: &SignerKey, sig: &CardSig, challenge: &[u8; 32]) {
    let layout = key.emv_layout().unwrap_or_else(|| env::panic_str(ERR_WRONG_SIGNER_KIND));
    if let Err(e) = emv::verify_card_challenge(&layout, &sig.sdad.0, &sig.terminal_data.0, challenge) {
        env::log_str(&format!("emv: {e}"));
        env::panic_str(ERR_CARD_INVALID)
    }
}
