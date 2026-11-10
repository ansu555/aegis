//! Spending limits, delays and approval rules.
//!
//! The model: **limits don't block, they delay.** A transfer within the
//! per-transaction and rolling-daily limits for its mint executes as soon as it
//! has enough approvals. Anything above them waits `transfer_delay`, during
//! which any signer can cancel it or freeze the wallet. That caps what a
//! successful attacker can take instantly to one day's allowance.
//!
//! Approval rules:
//! - Every action needs `threshold` distinct signer approvals.
//! - A transfer additionally needs at least one *user-verifying* approval
//!   (a P-256 signer: biometric phone key or FIDO2 key with UV). A card can't
//!   show the user what they're approving, so cards alone never move funds.
//! - A config change with a user-verifying approval waits `config_delay`.
//!   Without one (e.g. phone lost, recovering with two cards) it waits the
//!   longer `recovery_delay`.

pub const DAY: i64 = 86_400;
pub const EXECUTION_WINDOW: i64 = 7 * DAY;

pub const MIN_TRANSFER_DELAY: i64 = 3_600;
pub const MIN_CONFIG_DELAY: i64 = 3_600;
pub const MIN_RECOVERY_DELAY: i64 = DAY;
pub const MAX_DELAY: i64 = 30 * DAY;

pub const MIN_THRESHOLD: u8 = 2;
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LimitState {
    pub instant_max: u128,
    pub daily_max: u128,
    pub window_start: i64,
    pub spent: u128,
}

impl LimitState {
    pub fn new(instant_max: u128, daily_max: u128) -> Self {
        Self { instant_max, daily_max, window_start: 0, spent: 0 }
    }

    pub fn spent_at(&self, now: i64) -> u128 {
        if now >= self.window_start.saturating_add(DAY) {
            0
        } else {
            self.spent
        }
    }

    /// Whether `amount` can go out without waiting.
    pub fn is_instant(&self, amount: u128, now: i64) -> bool {
        amount <= self.instant_max
            && self.spent_at(now).checked_add(amount).map_or(false, |t| t <= self.daily_max)
    }

    /// Record an executed transfer (instant or delayed — both count).
    pub fn record(&mut self, amount: u128, now: i64) {
        if now >= self.window_start.saturating_add(DAY) {
            self.window_start = now;
            self.spent = 0;
        }
        self.spent = self.spent.saturating_add(amount);
    }

    /// Give back allowance for a transfer that was recorded but then failed
    /// (NEAR transfers are asynchronous). Only within the same window.
    pub fn unrecord(&mut self, amount: u128, recorded_at: i64) {
        if recorded_at >= self.window_start && recorded_at < self.window_start.saturating_add(DAY) {
            self.spent = self.spent.saturating_sub(amount);
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionClass {
    Transfer { instant: bool },
    Config,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    NotEnoughApprovals,
    NeedsUserVerifiedApproval,
    TooEarly,
    Expired,
}

/// Decide whether a proposal may execute now.
pub fn check_execution(
    class: ActionClass,
    approvals: u8,
    user_verified_approvals: u8,
    threshold: u8,
    delays: &Delays,
    created_ts: i64,
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
    };
    let ready = created_ts.saturating_add(delay);
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
    fn limits_window() {
        let mut l = LimitState::new(100, 250);
        let t0 = 1_000_000;
        assert!(l.is_instant(100, t0));
        assert!(!l.is_instant(101, t0));
        l.record(100, t0);
        l.record(100, t0 + 10);
        assert!(l.is_instant(50, t0 + 20));
        assert!(!l.is_instant(51, t0 + 20));
        // window rolls
        assert!(l.is_instant(100, t0 + DAY));
        l.record(10, t0 + DAY);
        assert_eq!(l.spent_at(t0 + DAY + 1), 10);
        // a failed transfer gives its allowance back
        l.unrecord(10, t0 + DAY);
        assert_eq!(l.spent_at(t0 + DAY + 1), 0);
        // ...but not across a window roll
        l.record(10, t0 + 2 * DAY);
        l.unrecord(10, t0 + DAY);
        assert_eq!(l.spent_at(t0 + 2 * DAY), 10);
        // overflow-safe
        let big = LimitState { instant_max: u128::MAX, daily_max: u128::MAX, window_start: t0, spent: u128::MAX };
        assert!(!big.is_instant(1, t0));
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
