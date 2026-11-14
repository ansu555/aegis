//! Error messages. A failed check panics, which reverts every state change
//! the call made.

use near_sdk::env;

pub const ERR_IN_SETUP: &str = "Wallet is still in setup";
pub const ERR_NOT_IN_SETUP: &str = "Wallet setup is already finalized";
pub const ERR_FROZEN: &str = "Wallet is frozen";
pub const ERR_NOT_FROZEN: &str = "Wallet is not frozen";
pub const ERR_TOO_MANY_SIGNERS: &str = "Too many signers";
pub const ERR_DUPLICATE_SIGNER: &str = "Signer already enrolled";
pub const ERR_INVALID_SIGNER_KEY: &str = "Invalid signer key";
pub const ERR_WRONG_SIGNER_KIND: &str = "Signer is the wrong kind for this operation";
pub const ERR_SIGNER_INDEX: &str = "Signer index out of range";
pub const ERR_P256_INVALID: &str = "P-256 signature invalid";
pub const ERR_MISSING_WEBAUTHN: &str = "WebAuthn signer requires an assertion payload";
pub const ERR_WEBAUTHN_INVALID: &str = "WebAuthn assertion invalid";
pub const ERR_CARD_INVALID: &str = "Card signature verification failed";
pub const ERR_INVALID_THRESHOLD: &str = "Invalid threshold";
pub const ERR_NO_DELIBERATE_SIGNER: &str = "At least one P-256 (phone / security key) signer is required";
pub const ERR_INVALID_DELAYS: &str = "Invalid delays";
pub const ERR_INVALID_LIMITS: &str = "Invalid limits";
pub const ERR_INVALID_ACTION: &str = "Invalid action";
pub const ERR_RATE_LIMITED: &str = "Proposal rate limit reached for today";
pub const ERR_NO_PROPOSAL: &str = "Proposal not found";
pub const ERR_STALE_PROPOSAL: &str = "Signer set changed since this proposal was created";
pub const ERR_ALREADY_APPROVED: &str = "Signer already approved";
pub const ERR_CHALLENGE_EXPIRED: &str = "Card approval window has closed; propose again";
pub const ERR_TOO_MANY_OPEN: &str = "This signer has too many open proposals; cancel or wait for some";
pub const ERR_INIT_NOT_SELF: &str = "Only the wallet account itself can initialise it";
pub const ERR_FINALIZE_SIGNER: &str = "setup_finalize must be sent by the wallet account itself, so its key is deleted";
pub const ERR_CHALLENGE_NOT_READY: &str = "Card challenge not sealed yet; retry in the next block";
pub const ERR_NOT_ENOUGH_APPROVALS: &str = "Not enough approvals";
pub const ERR_NEEDS_DELIBERATE: &str = "A transfer needs at least one phone / security-key approval";
pub const ERR_TOO_EARLY: &str = "Delay has not elapsed";
pub const ERR_EXPIRED: &str = "Proposal expired";
pub const ERR_NOT_CLOSABLE: &str = "Proposal is not stale or expired";
pub const ERR_SIGNER_NOT_FOUND: &str = "Signer not found";
pub const ERR_LIMIT_NOT_FOUND: &str = "Limit not found";
pub const ERR_CANNOT_CANCEL: &str = "This signer may not cancel a proposal that removes it";

// v0.2
pub const ERR_NO_POSSESSION: &str = "A key this proposal adds hasn't proved possession (prove_possession)";
pub const ERR_NOT_ADD_SIGNER: &str = "That op doesn't add a signer";
pub const ERR_UPGRADE_ALONE: &str = "UpgradeCode must be the only op of its proposal";
pub const ERR_USE_EXECUTE_UPGRADE: &str = "Run an UpgradeCode proposal with execute_upgrade";
pub const ERR_NOT_UPGRADE: &str = "Not an UpgradeCode proposal";
pub const ERR_CODE_HASH: &str = "Code doesn't match the approved code hash";
pub const ERR_ALLOWLIST: &str = "Receiver allowlist is full or already has that receiver";
pub const ERR_RECEIVER_NOT_FOUND: &str = "Receiver not on the allowlist";

// v0.4
pub const ERR_INVALID_HEIR: &str = "Invalid heir: the inactivity period must be 1 hour to 10 years, and the heir can't be the wallet itself";
pub const ERR_NO_HEIR: &str = "This wallet has no heir";
pub const ERR_STILL_ACTIVE: &str = "The owner has been active within the inactivity period";
pub const ERR_NO_CLAIM: &str = "No inheritance claim is open (or the owner was active since it started)";
pub const ERR_GRACE: &str = "The claim's grace period has not elapsed";
pub const ERR_NOTHING_TO_RELEASE: &str = "Nothing to release";

pub fn check(cond: bool, msg: &str) {
    if !cond {
        env::panic_str(msg)
    }
}
