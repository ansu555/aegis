//! Spending limits, delays and approval rules.
//!
//! The model: **limits don't block, they delay.** A transfer within the
//! per-transaction limit and the daily allowance for its asset executes as
//! soon as it has enough approvals. Anything above them waits
//! `transfer_delay`, during which any signer can cancel it or freeze the
//! wallet. The allowance refills continuously (`daily_max` per 24 hours), so
//! at most one day's allowance can ever go out at once.
//!
//! Delays run from the moment a proposal becomes fully approved, not from
//! when it was proposed, so the owner always gets the whole delay to react
//! to an approved action.
//!
//! Approval rules:
//! - Every action needs `threshold` distinct signer approvals.
//! - A transfer additionally needs at least one *user-verifying* approval
//!   (a P-256 signer: biometric phone key or FIDO2 key with UV). A card can't
//!   show the user what they're approving, so cards alone never move funds.
//! - A config change with a user-verifying approval waits `config_delay`.
//!   Without one (e.g. phone lost, recovering with two cards) it waits the
//!   longer `recovery_delay`.
//! - v0.4, inheritance: the owner may name an heir and an inactivity period.
//!   Once no signer has signed anything for that long, anyone may start a
//!   claim; after a further grace period (`config` delay) with still no
//!   activity, the wallet's funds can only go to the heir. Any signer
//!   signature voids a pending claim (`inheritance_claimable`, `releasable`).
//! - v0.3, phone-only wallet: a wallet whose only signer is the phone key may
//!   use threshold 1 until a card is added (`threshold_is_valid`). Adding a
//!   key to it must raise the threshold to 2 in the same change.

pub const DAY: i64 = 86_400;
pub const EXECUTION_WINDOW: i64 = 7 * DAY;

pub const MIN_TRANSFER_DELAY: i64 = 3_600;
pub const MIN_CONFIG_DELAY: i64 = 3_600;
pub const MIN_RECOVERY_DELAY: i64 = DAY;
pub const MAX_DELAY: i64 = 30 * DAY;

/// v0.4 inheritance: bounds of the inactivity period.
pub const MIN_INACTIVITY: i64 = 3_600;
pub const MAX_INACTIVITY: i64 = 3650 * DAY;

pub const MIN_THRESHOLD: u8 = 2;
/// v0.3: the threshold of a phone-only wallet (one signer, a P-256 key).
pub const PHONE_ONLY_THRESHOLD: u8 = 1;
pub const MAX_SIGNERS: usize = 8;
pub const MAX_PROPOSALS_PER_DAY: u16 = 24;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Delays {
    pub transfer: i64,
    pub config: i64,
    pub recovery: i64,
}

impl Delays {
    pub const DEFAULT: Delays = Delays { transfer: DAY, config: DAY, recovery: 3 * DAY };

    pub fn is_valid(&self) -> bool {
        (MIN_TRANSFER_DELAY..=MAX_DELAY).contains(&self.transfer)
            && (MIN_CONFIG_DELAY..=MAX_DELAY).contains(&self.config)
            && (MIN_RECOVERY_DELAY..=MAX_DELAY).contains(&self.recovery)
            && self.recovery >= self.config
    }

    pub fn max(&self) -> i64 {
        self.transfer.max(self.config).max(self.recovery)
    }
}

/// Whether `threshold` approvals out of `signers` is an allowed rule: at
/// least `MIN_THRESHOLD`, or 1 when the wallet has a single signer (the
/// phone key; the contract checks that it is a P-256 key). With two or more
/// signers no single key, card or phone, can act alone.
pub fn threshold_is_valid(threshold: u8, signers: usize) -> bool {
    let min = if signers == 1 { PHONE_ONLY_THRESHOLD } else { MIN_THRESHOLD };
    threshold >= min && (threshold as usize) <= signers
}

/// v0.4: whether an inactivity period is allowed.
pub fn inactivity_is_valid(inactivity: i64) -> bool {
    (MIN_INACTIVITY..=MAX_INACTIVITY).contains(&inactivity)
}

/// v0.4: a claim may start once the owner has been inactive for the whole
/// period (strictly longer than `inactivity` seconds).
pub fn inheritance_claimable(last_active: i64, inactivity: i64, now: i64) -> bool {
    now > last_active.saturating_add(inactivity)
}

/// v0.4: funds may go to the heir once a claim started at `claim_ts` has
/// waited out `grace` with no owner activity since (`last_active < claim_ts`).
pub fn releasable(claim_ts: i64, last_active: i64, grace: i64, now: i64) -> bool {
    last_active < claim_ts && now >= claim_ts.saturating_add(grace)
}

/// A leaky-bucket allowance: `used` drains at `daily_max` per day.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LimitState {
    pub instant_max: u128,
    pub daily_max: u128,
    /// When `used` was last brought up to date.
    pub updated_at: i64,
    pub used: u128,
}

impl LimitState {
    pub fn new(instant_max: u128, daily_max: u128) -> Self {
        Self { instant_max, daily_max, updated_at: 0, used: 0 }
    }

    /// Allowance in use at `now`, after refilling for the time elapsed.
    pub fn used_at(&self, now: i64) -> u128 {
        let elapsed = now.saturating_sub(self.updated_at).max(0) as u128;
        if elapsed >= DAY as u128 {
            return 0;
        }
        // daily_max * elapsed / DAY without overflow: elapsed < DAY < 2^17
        let refill = (self.daily_max / DAY as u128) * elapsed + (self.daily_max % DAY as u128) * elapsed / DAY as u128;
        self.used.saturating_sub(refill)
    }

    /// Whether `amount` can go out without waiting.
    pub fn is_instant(&self, amount: u128, now: i64) -> bool {
        amount <= self.instant_max && self.used_at(now).checked_add(amount).is_some_and(|t| t <= self.daily_max)
    }

    /// Record an executed transfer (instant or delayed: both count).
    pub fn record(&mut self, amount: u128, now: i64) {
        self.used = self.used_at(now).saturating_add(amount);
        self.updated_at = now;
    }

    /// Give back allowance for a transfer that was recorded but then failed
    /// (NEAR transfers are asynchronous).
    pub fn unrecord(&mut self, amount: u128, now: i64) {
        self.used = self.used_at(now).saturating_sub(amount);
        self.updated_at = now;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionClass {
    Transfer { instant: bool },
    Config,
    /// Replacing the contract code (v0.2 `UpgradeCode`): it can change every
    /// rule, so it always waits the longest delay, `recovery`, whoever approved.
    Upgrade,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    NotEnoughApprovals,
    NeedsUserVerifiedApproval,
    TooEarly,
    Expired,
}

/// Decide whether a proposal may execute now. `ready_ts` is when it
/// became fully approved.
pub fn check_execution(
    class: ActionClass,
    approvals: u8,
    user_verified_approvals: u8,
    threshold: u8,
    delays: &Delays,
    ready_ts: i64,
    now: i64,
) -> Result<(), Refusal> {
    if approvals < threshold {
        return Err(Refusal::NotEnoughApprovals);
    }
    let delay = match class {
        ActionClass::Transfer { instant } => {
            if user_verified_approvals == 0 {
                return Err(Refusal::NeedsUserVerifiedApproval);
            }
            if instant {
                0
            } else {
                delays.transfer
            }
        }
        ActionClass::Config => {
            if user_verified_approvals > 0 {
                delays.config
            } else {
                delays.recovery
            }
        }
        ActionClass::Upgrade => delays.recovery.max(delays.config),
    };
    let ready = ready_ts.saturating_add(delay);
    if now < ready {
        return Err(Refusal::TooEarly);
    }
    if now > ready.saturating_add(EXECUTION_WINDOW) {
        return Err(Refusal::Expired);
    }
    Ok(())
}

/// Per-signer daily proposal quota. Each proposal hands out one fresh card
/// challenge (one "guess" for an attacker holding skimmed signatures), so
/// proposals are bounded. Counting per signer means a stolen card can only
/// burn its own quota, not block the owner's phone or recovery.
///
/// Updates `window_start` / `counts` in place; returns false if `signer`'s
/// quota is exhausted.
pub fn rate_limit(window_start: &mut i64, counts: &mut [u16], signer: usize, now: i64) -> bool {
    if now >= window_start.saturating_add(DAY) {
        *window_start = now;
        counts.iter_mut().for_each(|c| *c = 0);
    }
    match counts.get_mut(signer) {
        Some(c) if *c < MAX_PROPOSALS_PER_DAY => {
            *c += 1;
            true
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allowance_refills_continuously() {
        let mut l = LimitState::new(100, 240);
        let t0 = 1_000_000;
        assert!(l.is_instant(100, t0));
        assert!(!l.is_instant(101, t0));
        l.record(100, t0);
        l.record(100, t0);
        assert!(l.is_instant(40, t0));
        assert!(!l.is_instant(41, t0));
        // a quarter day refills a quarter of daily_max
        assert_eq!(l.used_at(t0 + DAY / 4), 140);
        assert!(l.is_instant(100, t0 + DAY / 4));
        // no "window edge" to exploit: draining right before and after an
        // arbitrary instant can't exceed one allowance plus the refill
        l.record(40, t0);
        assert_eq!(l.used_at(t0 + 1), 240 - 240 / DAY as u128);
        assert!(!l.is_instant(1, t0 + 1));
        // fully refilled after a day
        assert_eq!(l.used_at(t0 + DAY), 0);
        // a failed transfer gives its allowance back
        l.record(100, t0 + DAY);
        l.unrecord(100, t0 + DAY);
        assert_eq!(l.used_at(t0 + DAY), 0);
        // overflow-safe
        let big = LimitState { instant_max: u128::MAX, daily_max: u128::MAX, updated_at: t0, used: u128::MAX };
        assert!(!big.is_instant(1, t0));
        assert!(big.used_at(t0 + 1) < u128::MAX);
    }

    #[test]
    fn execution_rules() {
        let d = Delays::DEFAULT;
        let t = 10_000;
        use ActionClass::*;
        assert_eq!(check_execution(Transfer { instant: true }, 1, 1, 2, &d, t, t), Err(Refusal::NotEnoughApprovals));
        assert_eq!(check_execution(Transfer { instant: true }, 2, 0, 2, &d, t, t), Err(Refusal::NeedsUserVerifiedApproval));
        assert_eq!(check_execution(Transfer { instant: true }, 2, 1, 2, &d, t, t), Ok(()));
        assert_eq!(check_execution(Transfer { instant: false }, 2, 1, 2, &d, t, t), Err(Refusal::TooEarly));
        assert_eq!(check_execution(Transfer { instant: false }, 2, 1, 2, &d, t, t + d.transfer), Ok(()));
        assert_eq!(check_execution(Config, 2, 1, 2, &d, t, t + d.config), Ok(()));
        assert_eq!(check_execution(Config, 2, 0, 2, &d, t, t + d.config), Err(Refusal::TooEarly));
        assert_eq!(check_execution(Config, 2, 0, 2, &d, t, t + d.recovery), Ok(()));
        assert_eq!(
            check_execution(Config, 2, 0, 2, &d, t, t + d.recovery + EXECUTION_WINDOW + 1),
            Err(Refusal::Expired)
        );
        // an upgrade always waits the recovery delay, even with a phone approval
        assert_eq!(check_execution(Upgrade, 2, 1, 2, &d, t, t + d.config), Err(Refusal::TooEarly));
        assert_eq!(check_execution(Upgrade, 2, 1, 2, &d, t, t + d.recovery), Ok(()));
        assert_eq!(check_execution(Upgrade, 1, 1, 2, &d, t, t + d.recovery), Err(Refusal::NotEnoughApprovals));
    }

    #[test]
    fn inheritance_rules() {
        assert!(inactivity_is_valid(MIN_INACTIVITY));
        assert!(!inactivity_is_valid(MIN_INACTIVITY - 1));
        assert!(inactivity_is_valid(MAX_INACTIVITY));
        assert!(!inactivity_is_valid(MAX_INACTIVITY + 1));
        let t = 1_000_000;
        assert!(!inheritance_claimable(t, DAY, t + DAY));
        assert!(inheritance_claimable(t, DAY, t + DAY + 1));
        assert!(!inheritance_claimable(i64::MAX - 1, DAY, i64::MAX));
        let claim = t + DAY + 1;
        assert!(!releasable(claim, t, 3_600, claim + 3_599));
        assert!(releasable(claim, t, 3_600, claim + 3_600));
        // any owner activity at or after the claim voids it
        assert!(!releasable(claim, claim, 3_600, claim + 10 * DAY));
        assert!(!releasable(claim, claim + 5, 3_600, claim + 10 * DAY));
    }

    #[test]
    fn threshold_rules() {
        assert!(threshold_is_valid(1, 1));
        assert!(!threshold_is_valid(2, 1));
        assert!(!threshold_is_valid(0, 1));
        // once a second key exists, one approval is never enough
        assert!(!threshold_is_valid(1, 2));
        assert!(threshold_is_valid(2, 2));
        assert!(!threshold_is_valid(3, 2));
        assert!(threshold_is_valid(2, 3));
        assert!(!threshold_is_valid(1, 3));
        assert!(!threshold_is_valid(0, 0));
    }

    #[test]
    fn delays_validation() {
        assert!(Delays::DEFAULT.is_valid());
        assert!(!Delays { transfer: 10, ..Delays::DEFAULT }.is_valid());
        assert!(!Delays { recovery: DAY / 2, ..Delays::DEFAULT }.is_valid());
        assert!(!Delays { config: 2 * DAY, recovery: DAY, ..Delays::DEFAULT }.is_valid());
    }

    #[test]
    fn rate() {
        let mut start = 0i64;
        let mut counts = [0u16; MAX_SIGNERS];
        for _ in 0..MAX_PROPOSALS_PER_DAY {
            assert!(rate_limit(&mut start, &mut counts, 1, 5 * DAY));
        }
        assert!(!rate_limit(&mut start, &mut counts, 1, 5 * DAY + 100));
        // another signer is unaffected
        assert!(rate_limit(&mut start, &mut counts, 0, 5 * DAY + 100));
        // out-of-range signer refused
        assert!(!rate_limit(&mut start, &mut counts, MAX_SIGNERS, 5 * DAY + 100));
        // window roll resets everyone
        assert!(rate_limit(&mut start, &mut counts, 1, 6 * DAY));
        assert_eq!(start, 6 * DAY);
        assert_eq!(counts[1], 1);
        assert_eq!(counts[0], 0);
    }
}
