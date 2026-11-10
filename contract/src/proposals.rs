//! Commit, then challenge.
//!
//! `propose` fixes the action and schedules `seal_challenge` on this contract.
//! A cross-contract receipt always runs in a later block, so the randomness
//! `seal_challenge` captures did not exist when the action was committed:
//! no skimmed card signature can have targeted it, and the action can no
//! longer change to grind against it. Card approvals sign
//! `H(card-approve || intent || seed)`. Each proposal gets exactly one seed.

use near_sdk::json_types::U64;
use near_sdk::serde_json::json;
use near_sdk::{env, near, Gas};
use tapvault_core::{domain, policy};

use crate::auth::{verify_card, verify_p256, NearSha256};
use crate::*;

const SEAL_GAS: Gas = Gas::from_tgas(5);

#[near]
impl Tapvault {
    /// The proposer must be an enrolled signer:
    /// - P-256: signs the intent hash; this also counts as its approval.
    /// - Card: signs `H(card-propose || intent)`. That proves possession
    ///   (anti-spam) but is NOT an approval, because it can be computed
    ///   before the proposal exists. The card approves separately once the
    ///   challenge is sealed.
    pub fn propose(&mut self, action: Action, proof: SignerProof) -> U64 {
        check(!self.setup_mode, ERR_IN_SETUP);
        action.validate();
        if matches!(action, Action::Transfer { .. }) {
            check(!self.frozen, ERR_FROZEN);
        }

        let id = self.next_proposal_id;
        let intent = domain::intent_hash::<NearSha256>(&account_bytes(), id, &borsh_bytes(&action));
        let idx = proof.signer_index();
        let signer = self.signer(idx);
        let mut approvals = 0u8;
        match &proof {
            SignerProof::P256 { sig, .. } => {
                verify_p256(signer, sig, &intent);
                approvals |= 1 << idx;
            }
            SignerProof::Card { sig, .. } => {
                verify_card(signer, sig, &domain::card_propose_challenge::<NearSha256>(&intent));
            }
        }

        let now = now_secs();
        check(
            policy::rate_limit(&mut self.rate_window_start, &mut self.proposals_in_window, idx as usize, now),
            ERR_RATE_LIMITED,
        );
        self.next_proposal_id = id.checked_add(1).unwrap();
        self.proposals.insert(
            id,
            Proposal {
                intent_hash: intent,
                created_ts: now,
                created_height: env::block_height(),
                signer_epoch: self.signer_epoch,
                approvals,
                seed: None,
                action,
            },
        );

        Self::ext(env::current_account_id()).with_static_gas(SEAL_GAS).seal_challenge(U64(id)).detach();
        events::emit("proposal_created", json!({ "id": U64(id), "intent_hash": hex(&intent) }));
        U64(id)
    }

    /// Capture this block's randomness as the proposal's card challenge seed.
    #[private]
    pub fn seal_challenge(&mut self, id: U64) {
        if let Some(p) = self.proposals.get_mut(&id.0) {
            if p.seed.is_none() {
                let seed = env::random_seed_array();
                p.seed = Some(seed);
                let ch = domain::card_approve_challenge::<NearSha256>(&p.intent_hash, &seed);
                events::emit("challenge_sealed", json!({ "id": id, "card_challenge": hex(&ch) }));
            }
        }
    }

    /// A P-256 signer approves by signing the intent hash.
    pub fn approve_p256(&mut self, id: U64, signer_index: u8, sig: P256Sig) {
        let intent = self.pre_approve(id.0, signer_index).intent_hash;
        verify_p256(self.signer(signer_index), &sig, &intent);
        self.mark_approved(id.0, signer_index);
    }

    /// A card approves by signing the sealed challenge.
    pub fn approve_card(&mut self, id: U64, signer_index: u8, sig: CardSig) {
        let p = self.pre_approve(id.0, signer_index);
        let seed = p.seed.unwrap_or_else(|| env::panic_str(ERR_CHALLENGE_NOT_READY));
        let ch = domain::card_approve_challenge::<NearSha256>(&p.intent_hash, &seed);
        verify_card(self.signer(signer_index), &sig, &ch);
        self.mark_approved(id.0, signer_index);
    }
}

impl Tapvault {
    fn pre_approve(&self, id: u64, idx: u8) -> Proposal {
        check(!self.setup_mode, ERR_IN_SETUP);
        let p = self.proposal(id);
        check(p.signer_epoch == self.signer_epoch, ERR_STALE_PROPOSAL);
        self.signer(idx);
        check(p.approvals & (1 << idx) == 0, ERR_ALREADY_APPROVED);
        p
    }

    fn mark_approved(&mut self, id: u64, idx: u8) {
        self.proposals.get_mut(&id).unwrap().approvals |= 1 << idx;
        events::emit("approved", json!({ "id": U64(id), "signer_index": idx }));
    }

    pub(crate) fn proposal(&self, id: u64) -> Proposal {
        self.proposals.get(&id).cloned().unwrap_or_else(|| env::panic_str(ERR_NO_PROPOSAL))
    }
}
