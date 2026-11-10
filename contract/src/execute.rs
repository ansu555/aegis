//! Execute an approved proposal. Anyone may call `execute` (usually the
//! relayer); the approvals and policy are what authorise it.

use near_sdk::json_types::U64;
use near_sdk::serde_json::json;
use near_sdk::{env, near, Gas, NearToken, Promise, PromiseError, PromiseOrValue};
use tapvault_core::policy::{self, ActionClass, Refusal};

use crate::*;

const FT_TRANSFER_GAS: Gas = Gas::from_tgas(15);
const ON_TRANSFER_GAS: Gas = Gas::from_tgas(5);

#[near]
impl Tapvault {
    pub fn execute(&mut self, id: U64) -> PromiseOrValue<bool> {
        let p = self.proposal(id.0);
        let now = now_secs();
        match p.action.clone() {
            Action::Transfer { asset, amount, receiver } => {
                check(!self.frozen, ERR_FROZEN);
                let instant = self
                    .limits
                    .iter()
                    .find(|l| l.asset == asset)
                    .is_some_and(|l| l.core().is_instant(amount.0, now));
                self.gate(&p, ActionClass::Transfer { instant }, now);
                if let Some(l) = self.limits.iter_mut().find(|l| l.asset == asset) {
                    let mut s = l.core();
                    s.record(amount.0, now);
                    l.store(s);
                }
                self.proposals.remove(&id.0);
                events::emit("executed", json!({ "id": id }));

                let send = match &asset {
                    Asset::Near => Promise::new(receiver).transfer(NearToken::from_yoctonear(amount.0)),
                    Asset::Ft { token } => Promise::new(token.clone()).function_call(
                        "ft_transfer".to_string(),
                        json!({ "receiver_id": receiver, "amount": amount }).to_string().into_bytes(),
                        NearToken::from_yoctonear(1),
                        FT_TRANSFER_GAS,
                    ),
                };
                PromiseOrValue::Promise(send.then(
                    Self::ext(env::current_account_id())
                        .with_static_gas(ON_TRANSFER_GAS)
                        .on_transfer(id, asset, amount, now),
                ))
            }
            Action::Config { ops } => {
                self.gate(&p, ActionClass::Config, now);
                self.apply_config(ops);
                self.proposals.remove(&id.0);
                events::emit("executed", json!({ "id": id }));
                PromiseOrValue::Value(true)
            }
        }
    }

    /// NEAR transfers are asynchronous. If one failed, the funds came back;
    /// give its allowance back too.
    #[private]
    pub fn on_transfer(&mut self, id: U64, asset: Asset, amount: U128, recorded_at: i64) -> bool {
        // The result body doesn't matter (both return nothing); only failure does.
        let ok = !matches!(env::promise_result_checked(0, 64), Err(PromiseError::Failed));
        if !ok {
            if let Some(l) = self.limits.iter_mut().find(|l| l.asset == asset) {
                let mut s = l.core();
                s.unrecord(amount.0, recorded_at);
                l.store(s);
            }
            events::emit("transfer_failed", json!({ "id": id }));
        }
        ok
    }
}

impl Tapvault {
    /// Count approvals and apply the policy. Shared by every execute path.
    fn gate(&self, p: &Proposal, class: ActionClass, now: i64) {
        check(!self.setup_mode, ERR_IN_SETUP);
        check(p.signer_epoch == self.signer_epoch, ERR_STALE_PROPOSAL);
        let (mut n, mut deliberate) = (0u8, 0u8);
        for (i, s) in self.signers.iter().enumerate() {
            if p.approvals & (1 << i) != 0 {
                n += 1;
                deliberate += s.is_deliberate() as u8;
            }
        }
        let r = policy::check_execution(class, n, deliberate, self.threshold, &self.delays.into(), p.created_ts, now);
        if let Err(r) = r {
            env::panic_str(match r {
                Refusal::NotEnoughApprovals => ERR_NOT_ENOUGH_APPROVALS,
                Refusal::NeedsUserVerifiedApproval => ERR_NEEDS_DELIBERATE,
                Refusal::TooEarly => ERR_TOO_EARLY,
                Refusal::Expired => ERR_EXPIRED,
            })
        }
    }

    /// Config changes run even while frozen (that's how you recover and
    /// unfreeze). They are always delayed: `config` with a P-256 approval,
    /// `recovery` with cards only.
    fn apply_config(&mut self, ops: Vec<ConfigOp>) {
        for op in ops {
            match op {
                ConfigOp::AddSigner(k) => {
                    check(self.signers.len() < MAX_SIGNERS, ERR_TOO_MANY_SIGNERS);
                    self.signers.push(k); // validity + duplicates checked by validate_config
                }
                ConfigOp::RemoveSigner { key_hash } => {
                    let pos = self
                        .signers
                        .iter()
                        .position(|s| s.key_hash()[..] == key_hash.0[..])
                        .unwrap_or_else(|| env::panic_str(ERR_SIGNER_NOT_FOUND));
                    self.signers.remove(pos);
                }
                ConfigOp::SetThreshold(t) => self.threshold = t,
                ConfigOp::SetLimit(l) => match self.limits.iter_mut().find(|x| x.asset == l.asset) {
                    Some(existing) => {
                        existing.instant_max = l.instant_max;
                        existing.daily_max = l.daily_max;
                    }
                    None => {
                        check(self.limits.len() < MAX_LIMITS, ERR_INVALID_LIMITS);
                        self.limits.push(MintLimit {
                            asset: l.asset,
                            instant_max: l.instant_max,
                            daily_max: l.daily_max,
                            window_start: 0,
                            spent: U128(0),
                        });
                    }
                },
                ConfigOp::RemoveLimit { asset } => {
                    let pos = self
                        .limits
                        .iter()
                        .position(|l| l.asset == asset)
                        .unwrap_or_else(|| env::panic_str(ERR_LIMIT_NOT_FOUND));
                    self.limits.remove(pos);
                }
                ConfigOp::SetDelays(d) => self.delays = d,
                ConfigOp::Unfreeze => {
                    check(self.frozen, ERR_NOT_FROZEN);
                    self.frozen = false;
                }
            }
        }
        self.validate_config();
        // Any config change voids every other pending proposal: their approval
        // bitmaps refer to signer indices that may have moved. Per-signer rate
        // counters are index-based too, so they reset with the epoch.
        self.signer_epoch = self.signer_epoch.checked_add(1).unwrap();
        self.proposals_in_window = [0; MAX_SIGNERS];
    }
}
