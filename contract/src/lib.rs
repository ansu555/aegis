//! Tapvault: a self-custodial NEAR wallet co-signed by an EMV bank card and
//! a phone / FIDO2 P-256 key. See docs/DESIGN.md for the threat model.
//!
//! One contract instance is one wallet: it is deployed to the wallet's own
//! account, and `setup_finalize` deletes the deployer's full-access key, so
//! from then on funds only move through this contract's rules.

use near_sdk::json_types::U128;
use near_sdk::store::IterableMap;
use near_sdk::{env, near, AccountId, BorshStorageKey, PanicOnDefault};
use tapvault_core::{domain, policy};

pub mod auth;
pub mod error;
pub mod events;
pub mod execute;
pub mod guard;
pub mod inherit;
pub mod proposals;
pub mod setup;
pub mod types;
pub mod views;

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
    /// v0.2: receivers that may be paid instantly; empty = no allowlist.
    allowlist: Vec<AccountId>,
}

/// Reported by the `contract_version` view (absent before v0.2).
pub const CONTRACT_VERSION: &str = "0.4.0";

#[near]
impl Tapvault {
    /// Create the wallet with its first signer, which must be a P-256 key
    /// (the phone) and must sign the creation message. Only the wallet
    /// account itself can initialise it (deploy + `new` in one transaction),
    /// so nobody can race to set up someone else's wallet.
    #[init]
    pub fn new(first_signer: SignerKey, sig: P256Sig) -> Self {
        // Checked by hand: near-sdk ignores `#[private]` on `#[init]` methods.
        check(env::predecessor_account_id() == env::current_account_id(), ERR_INIT_NOT_SELF);
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
            allowlist: vec![],
        }
    }

    /// Called by `execute_upgrade` right after the new code is deployed, in
    /// the same receipt: if it fails, the deploy is undone too. A later
    /// version whose state layout differs reads the old layout here.
    #[private]
    #[init(ignore_state)]
    pub fn migrate() -> Self {
        env::state_read().unwrap_or_else(|| env::panic_str("no state to migrate"))
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
        // v0.3: threshold 1 only for a phone-only wallet; the deliberate-signer
        // check below then makes that one signer a P-256 key, never a card.
        check(policy::threshold_is_valid(self.threshold, n), ERR_INVALID_THRESHOLD);
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

/// A card must approve within this many seconds of its challenge being sealed.
pub const CARD_APPROVAL_WINDOW: i64 = 600;
/// Open proposals any one signer may have at a time.
pub const MAX_OPEN_PER_SIGNER: usize = 4;
/// A proposal that never gets all its approvals can be closed after this.
pub const UNAPPROVED_TTL: i64 = policy::DAY;

/// The wallet's account id, as signed in every message.
fn account_bytes() -> Vec<u8> {
    env::current_account_id().as_str().as_bytes().to_vec()
}

/// Block time in whole seconds.
fn now_secs() -> i64 {
    (env::block_timestamp_ms() / 1000) as i64
}

/// A fresh, unpredictable freeze nonce, so freeze signatures can't be
/// collected in advance for future nonces.
fn new_freeze_nonce(old: u64) -> u64 {
    let s = env::random_seed_array();
    let n = u64::from_le_bytes(s[..8].try_into().unwrap());
    if n == old { n.wrapping_add(1) } else { n }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
