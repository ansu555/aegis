//! Read-only views for the app and relayer. Hashes are hex.
//!
//! Clients should compute what they sign themselves (the phone must not trust
//! the relayer); these views exist so they can cross-check.

use near_sdk::json_types::U64;
use near_sdk::near;
use tapvault_core::domain;

use crate::auth::NearSha256;
use crate::*;

#[near(serializers = [json])]
pub struct SignerView {
    pub index: u8,
    pub key_hash: String,
    pub key: SignerKey,
}

#[near(serializers = [json])]
pub struct WalletView {
    pub setup_mode: bool,
    pub frozen: bool,
    pub threshold: u8,
    pub signer_epoch: u32,
    pub freeze_nonce: U64,
    pub next_proposal_id: U64,
    pub delays: DelaysCfg,
    pub signers: Vec<SignerView>,
    pub limits: Vec<MintLimit>,
}

#[near(serializers = [json])]
pub struct ProposalView {
    pub id: U64,
    pub intent_hash: String,
    pub created_ts: i64,
    pub created_height: U64,
    pub approvals: u8,
    pub proposer: u8,
    /// When it became fully approved; delays run from here.
    pub ready_ts: Option<i64>,
    /// Card approvals are accepted until this time.
    pub card_deadline_ts: Option<i64>,
    /// Approved by a signer set that has since changed; can't execute.
    pub stale: bool,
    pub action: Action,
    /// What a card must sign to approve; `None` until the seed is sealed.
    pub card_challenge: Option<String>,
}

#[near(serializers = [json])]
pub struct Challenge {
    pub p256_message: String,
    pub card_challenge: String,
}

#[near]
impl Tapvault {
    pub fn get_wallet(&self) -> WalletView {
        WalletView {
            setup_mode: self.setup_mode,
            frozen: self.frozen,
            threshold: self.threshold,
            signer_epoch: self.signer_epoch,
            freeze_nonce: U64(self.freeze_nonce),
            next_proposal_id: U64(self.next_proposal_id),
            delays: self.delays,
            signers: self
                .signers
                .iter()
                .enumerate()
                .map(|(i, s)| SignerView { index: i as u8, key_hash: hex(&s.key_hash()), key: s.clone() })
                .collect(),
            limits: self.limits.clone(),
        }
    }

    pub fn get_proposal(&self, id: U64) -> Option<ProposalView> {
        self.proposals.get(&id.0).map(|p| self.proposal_view(id.0, p))
    }

    pub fn list_proposals(&self, from_index: Option<u32>, limit: Option<u32>) -> Vec<ProposalView> {
        self.proposals
            .iter()
            .skip(from_index.unwrap_or(0) as usize)
            .take(limit.unwrap_or(20).min(50) as usize)
            .map(|(id, p)| self.proposal_view(*id, p))
            .collect()
    }

    /// The intent hash `action` would get as proposal `id` (normally
    /// `next_proposal_id`).
    pub fn intent_for(&self, action: Action, id: U64) -> String {
        hex(&domain::intent_hash::<NearSha256>(&account_bytes(), id.0, &borsh_bytes(&action)))
    }

    pub fn freeze_challenge(&self) -> Challenge {
        let a = account_bytes();
        Challenge {
            p256_message: hex(&domain::freeze_message::<NearSha256>(&a, self.freeze_nonce)),
            card_challenge: hex(&domain::card_freeze_challenge::<NearSha256>(&a, self.freeze_nonce)),
        }
    }

    pub fn cancel_challenge(&self, id: U64) -> Challenge {
        let intent = self.proposal(id.0).intent_hash;
        Challenge {
            p256_message: hex(&domain::cancel_message::<NearSha256>(&intent)),
            card_challenge: hex(&domain::card_cancel_challenge::<NearSha256>(&intent)),
        }
    }
}

impl Tapvault {
    fn proposal_view(&self, id: u64, p: &Proposal) -> ProposalView {
        ProposalView {
            id: U64(id),
            intent_hash: hex(&p.intent_hash),
            created_ts: p.created_ts,
            created_height: U64(p.created_height),
            approvals: p.approvals,
            proposer: p.proposer,
            ready_ts: p.ready_ts,
            card_deadline_ts: p.sealed_ts.map(|t| t + CARD_APPROVAL_WINDOW),
            stale: p.signer_epoch != self.signer_epoch,
            action: p.action.clone(),
            card_challenge: p.seed.map(|s| hex(&domain::card_approve_challenge::<NearSha256>(&p.intent_hash, &s))),
        }
    }
}
