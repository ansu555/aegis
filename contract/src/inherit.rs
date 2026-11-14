//! v0.4 inheritance ("dead man's switch").
//!
//! The owner names an heir and an inactivity period through an ordinary,
//! delayed config proposal (`ConfigOp::SetHeir`). Every signer signature the
//! contract verifies (propose, approve, cancel, freeze, `check_in`) counts as
//! activity and voids any open claim. Once the owner has been inactive for the
//! whole period, anyone may `start_inheritance_claim`; after a further grace
//! period (the wallet's `config` delay) with still no activity, anyone may
//! `release_to_heir`, and the funds can only go to the heir.
//!
//! The state lives under its own storage key, so the main `Tapvault` layout is
//! unchanged from v0.3.

use near_sdk::json_types::U128;
use near_sdk::serde_json::json;
use near_sdk::{env, near, AccountId, Gas, NearToken, Promise, PromiseResult};
use tapvault_core::{domain, policy};

use crate::auth::{verify_card, verify_p256, NearSha256};
use crate::*;

const HEIR_KEY: &[u8] = b"heir";
const FT_TRANSFER_GAS: Gas = Gas::from_tgas(15);
const ON_RELEASE_GAS: Gas = Gas::from_tgas(5);
/// Bytes of storage kept paid for on top of what the account uses now, so the
/// wallet stays usable (and its state readable) after a full NEAR release.
const STORAGE_MARGIN_BYTES: u128 = 2_000;

#[near(serializers = [json])]
pub struct InheritanceView {
    pub heir: Option<AccountId>,
    pub inactivity: i64,
    pub last_active: i64,
    pub claim_ts: Option<i64>,
    /// When a claim may start (`None`: no heir).
    pub claimable_at: Option<i64>,
    /// When funds may go to the heir (`None`: no open claim).
    pub releasable_at: Option<i64>,
    pub alive_nonce: u64,
    /// What a check-in signs now (hex): phone message and card challenge.
    pub alive_message: String,
    pub card_alive_challenge: String,
}

#[near]
impl Tapvault {
    /// The owner is still here: any signer signs `alive_message` (phone) or
    /// `card_alive_challenge` (card) for the current nonce. Voids open claims.
    pub fn check_in(&mut self, proof: SignerProof) {
        check(!self.setup_mode, ERR_IN_SETUP);
        let mut h = read_inheritance();
        check(h.heir.is_some(), ERR_NO_HEIR);
        let account = account_bytes();
        let signer = self.signer(proof.signer_index());
        match &proof {
            SignerProof::P256 { sig, .. } => {
                verify_p256(signer, sig, &domain::alive_message::<NearSha256>(&account, h.alive_nonce))
            }
            SignerProof::Card { sig, .. } => {
                verify_card(signer, sig, &domain::card_alive_challenge::<NearSha256>(&account, h.alive_nonce))
            }
        }
        h.alive_nonce = h.alive_nonce.wrapping_add(1);
        write_inheritance(&h);
        touch();
        events::emit("checked_in", json!({ "signer_index": proof.signer_index() }));
    }

    /// Permissionless: open a claim once the owner has been inactive for the
    /// whole period.
    pub fn start_inheritance_claim(&mut self) {
        check(!self.setup_mode, ERR_IN_SETUP);
        let mut h = read_inheritance();
        let heir = h.heir.clone().unwrap_or_else(|| env::panic_str(ERR_NO_HEIR));
        let now = now_secs();
        check(policy::inheritance_claimable(h.last_active, h.inactivity, now), ERR_STILL_ACTIVE);
        if h.claim_ts.is_none() {
            h.claim_ts = Some(now);
            write_inheritance(&h);
        }
        let releasable_at = h.claim_ts.unwrap().saturating_add(self.delays.config);
        events::emit("inheritance_claim_started", json!({ "heir": heir, "releasable_at": releasable_at }));
    }

    /// Permissionless: once a claim has waited out the grace period with no
    /// owner activity, send `asset` to the heir. For NEAR, `amount` may be
    /// left out to send everything above the storage reserve; a token needs
    /// an amount (its `ft_balance_of`). Freeze and limits don't apply: the
    /// owner has been silent for the inactivity period plus the grace period.
    pub fn release_to_heir(&mut self, asset: Asset, amount: Option<U128>) -> Promise {
        check(!self.setup_mode, ERR_IN_SETUP);
        let h = read_inheritance();
        let heir = h.heir.clone().unwrap_or_else(|| env::panic_str(ERR_NO_HEIR));
        let claim = h.claim_ts.unwrap_or_else(|| env::panic_str(ERR_NO_CLAIM));
        check(h.last_active < claim, ERR_NO_CLAIM);
        check(policy::releasable(claim, h.last_active, self.delays.config, now_secs()), ERR_GRACE);
        let send = match &asset {
            Asset::Near => {
                let available = near_available();
                let amount = amount.map_or(available, |a| a.0.min(available));
                check(amount > 0, ERR_NOTHING_TO_RELEASE);
                events::emit("inheritance_released", json!({ "heir": heir, "asset": asset, "amount": U128(amount) }));
                Promise::new(heir).transfer(NearToken::from_yoctonear(amount))
            }
            Asset::Ft { token } => {
                let amount = amount.unwrap_or_else(|| env::panic_str(ERR_NOTHING_TO_RELEASE));
                check(amount.0 > 0, ERR_NOTHING_TO_RELEASE);
                events::emit("inheritance_released", json!({ "heir": heir, "asset": asset, "amount": amount }));
                Promise::new(token.clone()).function_call(
                    "ft_transfer".to_string(),
                    json!({ "receiver_id": heir, "amount": amount }).to_string().into_bytes(),
                    NearToken::from_yoctonear(1),
                    FT_TRANSFER_GAS,
                )
            }
        };
        send.then(Self::ext(env::current_account_id()).with_static_gas(ON_RELEASE_GAS).on_release(asset))
    }

    #[private]
    pub fn on_release(&mut self, asset: Asset) -> bool {
        let ok = matches!(env::promise_result(0), PromiseResult::Successful(_));
        if !ok {
            events::emit("inheritance_release_failed", json!({ "asset": asset }));
        }
        ok
    }

    pub fn get_inheritance(&self) -> InheritanceView {
        let h = read_inheritance();
        let account = account_bytes();
        InheritanceView {
            claimable_at: h.heir.as_ref().map(|_| h.last_active.saturating_add(h.inactivity).saturating_add(1)),
            releasable_at: h
                .claim_ts
                .filter(|c| h.last_active < *c)
                .map(|c| c.saturating_add(self.delays.config)),
            alive_message: hex(&domain::alive_message::<NearSha256>(&account, h.alive_nonce)),
            card_alive_challenge: hex(&domain::card_alive_challenge::<NearSha256>(&account, h.alive_nonce)),
            heir: h.heir,
            inactivity: h.inactivity,
            last_active: h.last_active,
            claim_ts: h.claim_ts,
            alive_nonce: h.alive_nonce,
        }
    }
}

pub(crate) fn read_inheritance() -> Inheritance {
    env::storage_read(HEIR_KEY)
        .map(|b| near_sdk::borsh::from_slice(&b).unwrap_or_else(|_| env::panic_str("bad inheritance state")))
        .unwrap_or_default()
}

pub(crate) fn write_inheritance(h: &Inheritance) {
    env::storage_write(HEIR_KEY, &borsh_bytes(h));
}

/// A signer signed something: the owner is active, and any open claim is void.
/// Writes nothing on a wallet without an heir.
pub(crate) fn touch() {
    let mut h = read_inheritance();
    if h.heir.is_some() {
        h.last_active = now_secs();
        h.claim_ts = None;
        write_inheritance(&h);
    }
}

/// `ConfigOp::SetHeir` / `RemoveHeir`, applied by `apply_config`.
pub(crate) fn set_heir(heir: Option<(AccountId, i64)>) {
    let mut h = read_inheritance();
    match heir {
        Some((account, inactivity)) => {
            check(account != env::current_account_id(), ERR_INVALID_HEIR);
            check(policy::inactivity_is_valid(inactivity), ERR_INVALID_HEIR);
            h.heir = Some(account);
            h.inactivity = inactivity;
        }
        None => {
            check(h.heir.is_some(), ERR_NO_HEIR);
            h.heir = None;
            h.inactivity = 0;
        }
    }
    h.last_active = now_secs();
    h.claim_ts = None;
    write_inheritance(&h);
}

/// NEAR the wallet can send while keeping its storage paid for.
fn near_available() -> u128 {
    let reserve = env::storage_byte_cost()
        .as_yoctonear()
        .saturating_mul((env::storage_usage() as u128).saturating_add(STORAGE_MARGIN_BYTES));
    env::account_balance().as_yoctonear().saturating_sub(reserve)
}
