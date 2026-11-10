//! Safe-direction actions: cancel a proposal, freeze the wallet, and clean up
//! dead proposals. These only *stop* things, so a single signer suffices.

use near_sdk::json_types::U64;
use near_sdk::serde_json::json;
use near_sdk::{env, near};
use tapvault_core::{domain, policy};

use crate::auth::{verify_card, verify_p256, NearSha256};
use crate::*;

#[near]
impl Tapvault {
    /// Any P-256 signer may cancel any proposal. A card may cancel unless the
    /// proposal removes that card: a stolen card can't block its own removal,
    /// while a biometric-locked phone key, which a thief can't use, can always
    /// veto (so an attacker holding enough signers to pass a proposal that
    /// strips the owner's signers ends in stalemate rather than takeover).
    pub fn cancel(&mut self, id: U64, proof: SignerProof) {
        let p = self.proposal(id.0);
        check(p.signer_epoch == self.signer_epoch, ERR_STALE_PROPOSAL);
        let signer = self.signer(proof.signer_index());
        match &proof {
            SignerProof::P256 { sig, .. } => {
                verify_p256(signer, sig, &domain::cancel_message::<NearSha256>(&p.intent_hash));
            }
            SignerProof::Card { sig, .. } => {
                let removed = p.action.removed_signers();
                check(!removed.iter().any(|h| h[..] == signer.key_hash()[..]), ERR_CANNOT_CANCEL);
                verify_card(signer, sig, &domain::card_cancel_challenge::<NearSha256>(&p.intent_hash));
            }
        }
        self.proposals.remove(&id.0);
        events::emit("cancelled", json!({ "id": id }));
    }

    /// Any single signer (phone or card) can freeze. Freezing blocks all
    /// transfers, including already-approved ones. Unfreezing is a delayed
    /// config proposal.
    pub fn freeze(&mut self, proof: SignerProof) {
        check(!self.setup_mode, ERR_IN_SETUP);
        check(!self.frozen, ERR_FROZEN);
        let account = account_bytes();
        let signer = self.signer(proof.signer_index());
        match &proof {
            SignerProof::P256 { sig, .. } => {
                verify_p256(signer, sig, &domain::freeze_message::<NearSha256>(&account, self.freeze_nonce));
            }
            SignerProof::Card { sig, .. } => {
                verify_card(signer, sig, &domain::card_freeze_challenge::<NearSha256>(&account, self.freeze_nonce));
            }
        }
        self.frozen = true;
        self.freeze_nonce = new_freeze_nonce(self.freeze_nonce);
        events::emit("frozen", json!({ "signer_index": proof.signer_index() }));
    }

    /// Permissionless: delete a proposal voided by a config change, never
    /// fully approved within `UNAPPROVED_TTL`, or past every possible
    /// execution window, freeing its storage.
    pub fn close_stale(&mut self, id: U64) {
        let p = self.proposal(id.0);
        let delays: policy::Delays = self.delays.into();
        let dead_after = match p.ready_ts {
            Some(r) => r.saturating_add(delays.max()).saturating_add(policy::EXECUTION_WINDOW),
            None => p.created_ts.saturating_add(UNAPPROVED_TTL),
        };
        check(p.signer_epoch != self.signer_epoch || now_secs() > dead_after, ERR_NOT_CLOSABLE);
        self.proposals.remove(&id.0);
        env::log_str(&format!("closed stale proposal {}", id.0));
    }
}
