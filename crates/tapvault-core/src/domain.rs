//! Every message a Tapvault signer signs, in one place.
//!
//! Each purpose has its own domain tag so a signature for one purpose can
//! never be replayed as another (e.g. a cancel can't become an approval, and
//! a card challenge for freezing can't approve a transfer). Messages that
//! name the wallet use its NEAR account id, length-prefixed like Borsh so
//! that variable-length ids can't be shifted into neighbouring fields.
//!
//! P-256 signers sign a 32-byte message directly (Android Keystore) or use it
//! as the WebAuthn challenge (FIDO2 keys). Card signers put the first
//! `un_len` bytes of a 32-byte challenge into the Unpredictable Number.

use crate::Sha256Hasher;

pub const D_INTENT: &[u8] = b"tapvault/near/v1/intent";
pub const D_CARD_APPROVE: &[u8] = b"tapvault/near/v1/card-approve";
pub const D_CARD_PROPOSE: &[u8] = b"tapvault/near/v1/card-propose";
pub const D_CANCEL: &[u8] = b"tapvault/near/v1/cancel";
pub const D_CARD_CANCEL: &[u8] = b"tapvault/near/v1/card-cancel";
pub const D_FREEZE: &[u8] = b"tapvault/near/v1/freeze";
pub const D_CARD_FREEZE: &[u8] = b"tapvault/near/v1/card-freeze";
pub const D_CREATE: &[u8] = b"tapvault/near/v1/create";
pub const D_SETUP_ADD: &[u8] = b"tapvault/near/v1/setup-add";
pub const D_CARD_ENROLL: &[u8] = b"tapvault/near/v1/card-enroll";
pub const D_FINALIZE: &[u8] = b"tapvault/near/v1/finalize";
pub const D_KEY_P256: &[u8] = b"tapvault/near/v1/key/p256";
pub const D_KEY_EMV: &[u8] = b"tapvault/near/v1/key/emv";
pub const D_POSSESS: &[u8] = b"tapvault/near/v1/possess";
pub const D_CARD_POSSESS: &[u8] = b"tapvault/near/v1/card-possess";
pub const D_ALIVE: &[u8] = b"tapvault/near/v1/alive";
pub const D_CARD_ALIVE: &[u8] = b"tapvault/near/v1/card-alive";

fn len32(b: &[u8]) -> [u8; 4] {
    (b.len() as u32).to_le_bytes()
}

pub fn key_hash_p256<H: Sha256Hasher>(pubkey33: &[u8]) -> [u8; 32] {
    H::hashv(&[D_KEY_P256, pubkey33])
}

/// Card identity. Computable by anyone who recovers the ICC public key; it
/// does not reveal the PAN.
pub fn key_hash_emv<H: Sha256Hasher>(modulus: &[u8], exponent: u32) -> [u8; 32] {
    H::hashv(&[D_KEY_EMV, &exponent.to_le_bytes(), modulus])
}

pub fn intent_hash<H: Sha256Hasher>(account: &[u8], proposal_id: u64, action: &[u8]) -> [u8; 32] {
    H::hashv(&[D_INTENT, &len32(account), account, &proposal_id.to_le_bytes(), action])
}

/// Card approval challenge. `seed` is the block randomness captured *after*
/// the proposal was committed, so no earlier skimmed signature can match it.
pub fn card_approve_challenge<H: Sha256Hasher>(intent: &[u8; 32], seed: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CARD_APPROVE, intent, seed])
}

/// Proof that the proposer holds an enrolled card (anti-spam only; it is NOT
/// an approval, because it can be computed before the proposal exists).
pub fn card_propose_challenge<H: Sha256Hasher>(intent: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CARD_PROPOSE, intent])
}

pub fn cancel_message<H: Sha256Hasher>(intent: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CANCEL, intent])
}

pub fn card_cancel_challenge<H: Sha256Hasher>(intent: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CARD_CANCEL, intent])
}

/// `freeze_nonce` makes each freeze signature single-use.
pub fn freeze_message<H: Sha256Hasher>(account: &[u8], freeze_nonce: u64) -> [u8; 32] {
    H::hashv(&[D_FREEZE, &len32(account), account, &freeze_nonce.to_le_bytes()])
}

pub fn card_freeze_challenge<H: Sha256Hasher>(account: &[u8], freeze_nonce: u64) -> [u8; 32] {
    H::hashv(&[D_CARD_FREEZE, &len32(account), account, &freeze_nonce.to_le_bytes()])
}

pub fn create_message<H: Sha256Hasher>(account: &[u8], first_key_hash: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CREATE, &len32(account), account, first_key_hash])
}

/// Authorises adding a signer during setup. `signer_count` (count before the
/// add) makes each authorisation single-use. A new P-256 key proves
/// possession by signing this same message.
pub fn setup_add_message<H: Sha256Hasher>(account: &[u8], signer_count: u8, new_key_hash: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_SETUP_ADD, &len32(account), account, &[signer_count], new_key_hash])
}

/// A new card proves possession by signing this during setup.
pub fn card_enroll_challenge<H: Sha256Hasher>(account: &[u8], signer_count: u8, new_key_hash: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CARD_ENROLL, &len32(account), account, &[signer_count], new_key_hash])
}

/// A key that a config proposal adds proves it is held by signing this
/// (contract v0.2). It names the wallet and the key, not the proposal, so a
/// guardian phone or a backup card can sign it before the proposal exists.
pub fn possession_message<H: Sha256Hasher>(account: &[u8], new_key_hash: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_POSSESS, &len32(account), account, new_key_hash])
}

/// The card form of [`possession_message`].
pub fn card_possession_challenge<H: Sha256Hasher>(account: &[u8], new_key_hash: &[u8; 32]) -> [u8; 32] {
    H::hashv(&[D_CARD_POSSESS, &len32(account), account, new_key_hash])
}

/// v0.4 check-in: the owner shows they still hold a key, which resets the
/// inheritance inactivity timer. `alive_nonce` makes each signature single-use.
pub fn alive_message<H: Sha256Hasher>(account: &[u8], alive_nonce: u64) -> [u8; 32] {
    H::hashv(&[D_ALIVE, &len32(account), account, &alive_nonce.to_le_bytes()])
}

/// The card form of [`alive_message`].
pub fn card_alive_challenge<H: Sha256Hasher>(account: &[u8], alive_nonce: u64) -> [u8; 32] {
    H::hashv(&[D_CARD_ALIVE, &len32(account), account, &alive_nonce.to_le_bytes()])
}

pub fn finalize_message<H: Sha256Hasher>(account: &[u8], args: &[u8]) -> [u8; 32] {
    H::hashv(&[D_FINALIZE, &len32(account), account, args])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::SoftSha256 as S;

    #[test]
    fn domains_are_distinct() {
        let all = [
            D_INTENT, D_CARD_APPROVE, D_CARD_PROPOSE, D_CANCEL, D_CARD_CANCEL, D_FREEZE, D_CARD_FREEZE, D_CREATE,
            D_SETUP_ADD, D_CARD_ENROLL, D_FINALIZE, D_KEY_P256, D_KEY_EMV, D_POSSESS, D_CARD_POSSESS, D_ALIVE, D_CARD_ALIVE,
        ];
        for i in 0..all.len() {
            for j in 0..all.len() {
                if i != j {
                    // no tag is a prefix of another, so concatenations can't collide on the tag
                    assert!(!all[j].starts_with(all[i]), "{:?} prefixes {:?}", all[i], all[j]);
                }
            }
        }
    }

    #[test]
    fn card_challenges_differ_by_purpose() {
        let i = [7u8; 32];
        let seed = [9u8; 32];
        let approve = card_approve_challenge::<S>(&i, &seed);
        assert_ne!(approve, card_propose_challenge::<S>(&i));
        assert_ne!(approve, card_cancel_challenge::<S>(&i));
        assert_ne!(cancel_message::<S>(&i), card_cancel_challenge::<S>(&i));
        assert_ne!(possession_message::<S>(b"w", &i), card_possession_challenge::<S>(b"w", &i));
        assert_ne!(card_possession_challenge::<S>(b"w", &i), card_enroll_challenge::<S>(b"w", 0, &i));
        assert_ne!(alive_message::<S>(b"w", 0), card_alive_challenge::<S>(b"w", 0));
        assert_ne!(alive_message::<S>(b"w", 0), freeze_message::<S>(b"w", 0));
        assert_ne!(alive_message::<S>(b"w", 0), alive_message::<S>(b"w", 1));
    }

    #[test]
    fn account_id_is_length_prefixed() {
        // "ab" + nonce bytes must not collide with "a" + shifted bytes
        let a = freeze_message::<S>(b"ab", 0);
        let b = freeze_message::<S>(b"a", u64::from_le_bytes([b'b', 0, 0, 0, 0, 0, 0, 0]));
        assert_ne!(a, b);
    }
}
