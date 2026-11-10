//! Tapvault: a self-custodial NEAR wallet co-signed by an EMV bank card and
//! a phone / FIDO2 P-256 key. See docs/DESIGN.md for the threat model.
//!
//! One contract instance is one wallet: it is deployed to the wallet's own
//! account, and `setup_finalize` deletes the deployer's full-access key, so
//! from then on funds only move through this contract's rules.

use near_sdk::json_types::U128;
use near_sdk::store::IterableMap;
use near_sdk::{env, near, BorshStorageKey, PanicOnDefault};
use tapvault_core::{domain, policy};

pub mod auth;
pub mod error;
pub mod events;
pub mod execute;
pub mod proposals;
pub mod setup;
pub mod types;

use auth::{verify_p256, NearSha256};
use error::*;
use types::*;

#[near]
#[derive(BorshStorageKey)]
enum StorageKey {
    Proposals,
}

#[near(contract_state)]
#[derive(PanicOnDefault)]
pub struct Tapvault {
    setup_mode: bool,
    frozen: bool,
    threshold: u8,
    /// Bumped on every config change; proposals from an older epoch are void.
    signer_epoch: u32,
    /// Bumped on every freeze so freeze signatures are single-use.
    freeze_nonce: u64,
    next_proposal_id: u64,
    rate_window_start: i64,
    /// Per-signer proposal counts in the current window (index = signer index).
    proposals_in_window: [u16; MAX_SIGNERS],
    delays: DelaysCfg,
    signers: Vec<SignerKey>,
    limits: Vec<MintLimit>,
    proposals: IterableMap<u64, Proposal>,
}

#[near]
impl Tapvault {
    /// Create the wallet with its first signer, which must be a P-256 key
    /// (the phone) and must sign the creation message.
    #[init]
    pub fn new(first_signer: SignerKey, sig: P256Sig) -> Self {
        check(first_signer.is_deliberate(), ERR_WRONG_SIGNER_KIND);
        first_signer.validate();
        let msg = domain::create_message::<NearSha256>(&account_bytes(), &first_signer.key_hash());
        verify_p256(&first_signer, &sig, &msg);

        Self {
            setup_mode: true,
            frozen: false,
            threshold: 0,
            signer_epoch: 0,
            freeze_nonce: 0,
            next_proposal_id: 0,
            rate_window_start: 0,
            proposals_in_window: [0; MAX_SIGNERS],
            delays: policy::Delays::DEFAULT.into(),
            signers: vec![first_signer],
            limits: vec![],
            proposals: IterableMap::new(StorageKey::Proposals),
        }
    }
}

impl Tapvault {
    fn signer(&self, index: u8) -> &SignerKey {
        self.signers.get(index as usize).unwrap_or_else(|| env::panic_str(ERR_SIGNER_INDEX))
    }

    /// Invariants that must hold whenever the wallet is out of setup.
    fn validate_config(&self) {
        let n = self.signers.len();
        check(n <= MAX_SIGNERS, ERR_TOO_MANY_SIGNERS);
        check(self.threshold >= policy::MIN_THRESHOLD && (self.threshold as usize) <= n, ERR_INVALID_THRESHOLD);
        check(self.signers.iter().any(|s| s.is_deliberate()), ERR_NO_DELIBERATE_SIGNER);
        let hashes: Vec<[u8; 32]> = self.signers.iter().map(|s| s.key_hash()).collect();
        for (i, s) in self.signers.iter().enumerate() {
            s.validate();
            check(!hashes[..i].contains(&hashes[i]), ERR_DUPLICATE_SIGNER);
        }
        check(policy::Delays::from(self.delays).is_valid(), ERR_INVALID_DELAYS);
        check(self.limits.len() <= MAX_LIMITS, ERR_INVALID_LIMITS);
        for (i, l) in self.limits.iter().enumerate() {
            check(l.instant_max.0 <= l.daily_max.0, ERR_INVALID_LIMITS);
            check(!self.limits[..i].iter().any(|o| o.asset == l.asset), ERR_INVALID_LIMITS);
        }
    }
}

/// The wallet's account id, as signed in every message.
fn account_bytes() -> Vec<u8> {
    env::current_account_id().as_str().as_bytes().to_vec()
}

/// Block time in whole seconds.
fn now_secs() -> i64 {
    (env::block_timestamp_ms() / 1000) as i64
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
