use near_sdk::json_types::{Base64VecU8, U128};
use near_sdk::{near, AccountId};
use tapvault_core::{domain, emv::EmvKeyLayout, policy};

use crate::auth::NearSha256;
use crate::error::*;

pub const MAX_SIGNERS: usize = 8;
const _: () = assert!(MAX_SIGNERS == policy::MAX_SIGNERS);
pub const MAX_LIMITS: usize = 8;
pub const MAX_CONFIG_OPS: usize = 4;
/// v0.2 receiver allowlist size.
pub const MAX_ALLOWLIST: usize = 16;
pub const P256_PUBKEY_LEN: usize = 33;
pub const P256_SIG_LEN: usize = 64;

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WebAuthnConfig {
    /// SHA-256 of the relying-party id (32 bytes).
    pub rp_id_hash: Base64VecU8,
    pub require_uv: bool,
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SignerKey {
    /// Phone secure-hardware key (raw ECDSA-SHA256 over the 32-byte message)
    /// or, with `webauthn`, a FIDO2 security key / passkey. SEC1 compressed.
    P256 { pubkey: Base64VecU8, webauthn: Option<WebAuthnConfig> },
    /// EMV card ICC public key (from the card's verified certificate chain).
    EmvCard { modulus: Base64VecU8, exponent: u32, un_offset: u8, un_len: u8, terminal_data_len: u8 },
}

impl SignerKey {
    pub fn key_hash(&self) -> [u8; 32] {
        match self {
            SignerKey::P256 { pubkey, .. } => domain::key_hash_p256::<NearSha256>(&pubkey.0),
            SignerKey::EmvCard { modulus, exponent, .. } => domain::key_hash_emv::<NearSha256>(&modulus.0, *exponent),
        }
    }

    /// P-256 signers require a deliberate user action (biometric / PIN /
    /// touch) behind a screen the user reads. Cards sign on contact.
    pub fn is_deliberate(&self) -> bool {
        matches!(self, SignerKey::P256 { .. })
    }

    pub fn emv_layout(&self) -> Option<EmvKeyLayout<'_>> {
        match self {
            SignerKey::EmvCard { modulus, exponent, un_offset, un_len, terminal_data_len } => Some(EmvKeyLayout {
                modulus: &modulus.0,
                exponent: *exponent,
                un_offset: *un_offset,
                un_len: *un_len,
                terminal_data_len: *terminal_data_len,
            }),
            _ => None,
        }
    }

    pub fn validate(&self) {
        match self {
            SignerKey::P256 { pubkey, webauthn } => {
                let p = &pubkey.0;
                check(p.len() == P256_PUBKEY_LEN && (p[0] == 0x02 || p[0] == 0x03), ERR_INVALID_SIGNER_KEY);
                check(p256::PublicKey::from_sec1_bytes(p).is_ok(), ERR_INVALID_SIGNER_KEY);
                if let Some(cfg) = webauthn {
                    check(cfg.rp_id_hash.0.len() == 32, ERR_INVALID_SIGNER_KEY);
                    // Counts as a deliberate (user-verified) signer, so it must verify the user.
                    check(cfg.require_uv, ERR_INVALID_SIGNER_KEY);
                }
            }
            SignerKey::EmvCard { .. } => {
                check(self.emv_layout().unwrap().validate().is_ok(), ERR_INVALID_SIGNER_KEY);
            }
        }
    }
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DelaysCfg {
    /// Seconds.
    pub transfer: i64,
    pub config: i64,
    pub recovery: i64,
}

impl From<DelaysCfg> for policy::Delays {
    fn from(d: DelaysCfg) -> Self {
        policy::Delays { transfer: d.transfer, config: d.config, recovery: d.recovery }
    }
}

impl From<policy::Delays> for DelaysCfg {
    fn from(d: policy::Delays) -> Self {
        DelaysCfg { transfer: d.transfer, config: d.config, recovery: d.recovery }
    }
}

/// What a transfer moves.
#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Asset {
    Near,
    /// A NEP-141 fungible token (e.g. USDC), by its contract account.
    Ft { token: AccountId },
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MintLimit {
    pub asset: Asset,
    pub instant_max: U128,
    pub daily_max: U128,
    /// When `used` was last brought up to date (allowance refills continuously).
    pub updated_at: i64,
    pub used: U128,
}

impl MintLimit {
    pub fn core(&self) -> policy::LimitState {
        policy::LimitState {
            instant_max: self.instant_max.0,
            daily_max: self.daily_max.0,
            updated_at: self.updated_at,
            used: self.used.0,
        }
    }

    pub fn store(&mut self, s: policy::LimitState) {
        self.updated_at = s.updated_at;
        self.used = U128(s.used);
    }
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LimitInit {
    pub asset: Asset,
    pub instant_max: U128,
    pub daily_max: U128,
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ConfigOp {
    AddSigner(SignerKey),
    RemoveSigner { key_hash: Base64VecU8 },
    SetThreshold(u8),
    SetLimit(LimitInit),
    RemoveLimit { asset: Asset },
    SetDelays(DelaysCfg),
    Unfreeze,
    // ---- v0.2 (appended, so earlier variants keep their Borsh tags) ----
    /// Receiver allowlist: once it has any entry, only listed receivers can
    /// be paid instantly; every other transfer waits `delays.transfer`.
    AllowReceiver { receiver: AccountId },
    DisallowReceiver { receiver: AccountId },
    /// Replace the contract code with the wasm whose SHA-256 is `code_hash`.
    /// Must be the only op of its proposal; waits the recovery delay and is
    /// run with `execute_upgrade(id, code)`.
    UpgradeCode { code_hash: Base64VecU8 },
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    Transfer { asset: Asset, amount: U128, receiver: AccountId },
    Config { ops: Vec<ConfigOp> },
}

impl Action {
    pub fn validate(&self) {
        match self {
            Action::Transfer { amount, .. } => check(amount.0 > 0, ERR_INVALID_ACTION),
            Action::Config { ops } => {
                check(!ops.is_empty() && ops.len() <= MAX_CONFIG_OPS, ERR_INVALID_ACTION);
                for op in ops {
                    match op {
                        ConfigOp::AddSigner(k) => k.validate(),
                        ConfigOp::UpgradeCode { code_hash } => {
                            check(code_hash.0.len() == 32, ERR_INVALID_ACTION);
                            check(ops.len() == 1, ERR_UPGRADE_ALONE);
                        }
                        _ => {}
                    }
                }
            }
        }
    }

    /// The code hash if this is an `UpgradeCode` proposal.
    pub fn upgrade_hash(&self) -> Option<&[u8]> {
        match self {
            Action::Config { ops } => ops.iter().find_map(|op| match op {
                ConfigOp::UpgradeCode { code_hash } => Some(code_hash.0.as_slice()),
                _ => None,
            }),
            _ => None,
        }
    }

    /// Bitmap of the op positions that add a signer (each needs a possession proof).
    pub fn add_signer_ops(&self) -> u8 {
        match self {
            Action::Config { ops } => ops
                .iter()
                .enumerate()
                .filter(|(_, op)| matches!(op, ConfigOp::AddSigner(_)))
                .fold(0u8, |m, (i, _)| m | (1 << i)),
            _ => 0,
        }
    }

    /// Key hashes of signers this action would remove.
    pub fn removed_signers(&self) -> Vec<Vec<u8>> {
        match self {
            Action::Config { ops } => ops
                .iter()
                .filter_map(|op| match op {
                    ConfigOp::RemoveSigner { key_hash } => Some(key_hash.0.clone()),
                    _ => None,
                })
                .collect(),
            _ => vec![],
        }
    }
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub struct WebAuthnPayload {
    pub authenticator_data: Base64VecU8,
    pub client_data_json: Base64VecU8,
    pub challenge_index: u16,
    pub type_index: u16,
}

/// A P-256 signature: 64 bytes `r || s`, plus the assertion for WebAuthn
/// signers.
#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub struct P256Sig {
    pub signature: Base64VecU8,
    pub webauthn: Option<WebAuthnPayload>,
}

/// A card's Signed Dynamic Application Data and the terminal data it covers.
#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub struct CardSig {
    pub sdad: Base64VecU8,
    pub terminal_data: Base64VecU8,
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub enum SignerProof {
    P256 { signer_index: u8, sig: P256Sig },
    Card { signer_index: u8, sig: CardSig },
}

impl SignerProof {
    pub fn signer_index(&self) -> u8 {
        match self {
            SignerProof::P256 { signer_index, .. } | SignerProof::Card { signer_index, .. } => *signer_index,
        }
    }
}

/// A key being added signs `possession_message` (P-256) or
/// `card_possession_challenge` (card) for this wallet and its own key hash.
#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub enum PossessionProof {
    P256(P256Sig),
    Card(CardSig),
}

/// The possession proof for op `op` (its position in the Config's ops).
#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub struct OpPossession {
    pub op: u8,
    pub proof: PossessionProof,
}

#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub struct FinalizeArgs {
    pub threshold: u8,
    pub delays: DelaysCfg,
    pub limits: Vec<LimitInit>,
}

#[near(serializers = [borsh])]
#[derive(Clone, Debug)]
pub struct Proposal {
    pub intent_hash: [u8; 32],
    pub created_ts: i64,
    pub created_height: u64,
    pub signer_epoch: u32,
    /// Signer index of the proposer (for the per-signer cap on open proposals).
    pub proposer: u8,
    /// When the proposal first had every approval it needs. Delays run from
    /// here, so the owner always gets the full delay to react.
    pub ready_ts: Option<i64>,
    /// Bit i set ⇔ signers[i] approved.
    pub approvals: u8,
    /// Block randomness captured in a later block than the proposal; the
    /// card approval challenge is derived from it. `None` until sealed.
    pub seed: Option<[u8; 32]>,
    /// When the seed was sealed. Card approvals must follow within
    /// `CARD_APPROVAL_WINDOW`.
    pub sealed_ts: Option<i64>,
    pub action: Action,
    /// v0.2: bit i set ⇔ the key added by op i proved possession.
    pub possessed: u8,
}

pub fn borsh_bytes<T: near_sdk::borsh::BorshSerialize>(t: &T) -> Vec<u8> {
    near_sdk::borsh::to_vec(t).expect("borsh serialize to Vec cannot fail")
}
