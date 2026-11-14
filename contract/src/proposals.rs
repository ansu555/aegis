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
    ///
    /// v0.2: `possession` may carry the proofs for the keys this action adds
    /// (they can also come later through `prove_possession`, before execute).
    pub fn propose(&mut self, action: Action, proof: SignerProof, possession: Option<Vec<OpPossession>>) -> U64 {
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

        crate::inherit::touch();

        // Count only proposals that can still complete: same signer set, and
        // either already approved or still inside the card-approval window.
        // Dead ones don't block the owner; close_stale cleans them up.
        let now = now_secs();
        let epoch = self.signer_epoch;
        let live = |p: &Proposal| {
            p.signer_epoch == epoch
                && (p.ready_ts.is_some() || now <= p.created_ts.saturating_add(CARD_APPROVAL_WINDOW + 60))
        };
        let open = self.proposals.values().filter(|p| p.proposer == idx && live(p)).count();
        check(open < MAX_OPEN_PER_SIGNER, ERR_TOO_MANY_OPEN);
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
                proposer: idx,
                ready_ts: None,
                approvals,
                seed: None,
                sealed_ts: None,
                action,
                possessed: 0,
            },
        );
        for p in possession.unwrap_or_default() {
            self.record_possession(id, p.op, &p.proof);
        }
        self.update_ready(id);

        // A dropped promise is still scheduled.
        let _ = Self::ext(env::current_account_id()).with_static_gas(SEAL_GAS).seal_challenge(U64(id));
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
                p.sealed_ts = Some(now_secs());
                let ch = domain::card_approve_challenge::<NearSha256>(&p.intent_hash, &seed);
                events::emit("challenge_sealed", json!({ "id": id, "card_challenge": hex(&ch) }));
            }
        }
    }

    /// v0.2: a key that proposal `id` adds (op `op`) proves it is held by
    /// signing `possession_message` / `card_possession_challenge` for this
    /// wallet and its own key hash. Anyone may send it; execute requires one
    /// for every added key (DESIGN gap 8).
    pub fn prove_possession(&mut self, id: U64, op: u8, proof: PossessionProof) {
        check(!self.setup_mode, ERR_IN_SETUP);
        self.record_possession(id.0, op, &proof);
    }

    /// A P-256 signer approves by signing the intent hash.
    pub fn approve_p256(&mut self, id: U64, signer_index: u8, sig: P256Sig) {
        let intent = self.pre_approve(id.0, signer_index).intent_hash;
        verify_p256(self.signer(signer_index), &sig, &intent);
        crate::inherit::touch();
        self.mark_approved(id.0, signer_index);
    }

    /// A card approves by signing the sealed challenge.
    pub fn approve_card(&mut self, id: U64, signer_index: u8, sig: CardSig) {
        let p = self.pre_approve(id.0, signer_index);
        let seed = p.seed.unwrap_or_else(|| env::panic_str(ERR_CHALLENGE_NOT_READY));
        // A card read taken after the seal is only safe if it's the owner's
        // tap right now; an old challenge can't be filled in days later.
        let sealed = p.sealed_ts.unwrap_or(i64::MIN);
        check(now_secs() <= sealed.saturating_add(CARD_APPROVAL_WINDOW), ERR_CHALLENGE_EXPIRED);
        let ch = domain::card_approve_challenge::<NearSha256>(&p.intent_hash, &seed);
        verify_card(self.signer(signer_index), &sig, &ch);
        crate::inherit::touch();
        self.mark_approved(id.0, signer_index);
    }
}

impl Tapvault {
    fn record_possession(&mut self, id: u64, op: u8, proof: &PossessionProof) {
        let p = self.proposal(id);
        let key = match &p.action {
            Action::Config { ops } => match ops.get(op as usize) {
                Some(ConfigOp::AddSigner(k)) => k.clone(),
                _ => env::panic_str(ERR_NOT_ADD_SIGNER),
            },
            _ => env::panic_str(ERR_NOT_ADD_SIGNER),
        };
        let account = account_bytes();
        match proof {
            PossessionProof::P256(sig) => {
                verify_p256(&key, sig, &domain::possession_message::<NearSha256>(&account, &key.key_hash()))
            }
            PossessionProof::Card(sig) => {
                verify_card(&key, sig, &domain::card_possession_challenge::<NearSha256>(&account, &key.key_hash()))
            }
        }
        self.proposals.get_mut(&id).unwrap().possessed |= 1 << op;
        events::emit("possession_proved", json!({ "id": U64(id), "op": op }));
    }

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
        self.update_ready(id);
    }

    /// Stamp `ready_ts` the first time the proposal has all the approvals it
    /// needs (threshold, plus a phone/key approval for transfers).
    fn update_ready(&mut self, id: u64) {
        let (n, deliberate) = self.count_approvals(self.proposals.get(&id).unwrap().approvals);
        let threshold = self.threshold;
        let p = self.proposals.get_mut(&id).unwrap();
        let needs_deliberate = matches!(p.action, Action::Transfer { .. });
        if p.ready_ts.is_none() && n >= threshold && (!needs_deliberate || deliberate > 0) {
            p.ready_ts = Some(now_secs());
            events::emit("ready", json!({ "id": U64(id) }));
        }
    }

    /// (approvals, approvals by phone / key signers)
    pub(crate) fn count_approvals(&self, bitmap: u8) -> (u8, u8) {
        let (mut n, mut deliberate) = (0u8, 0u8);
        for (i, s) in self.signers.iter().enumerate() {
            if bitmap & (1 << i) != 0 {
                n += 1;
                deliberate += s.is_deliberate() as u8;
            }
        }
        (n, deliberate)
    }

    pub(crate) fn proposal(&self, id: u64) -> Proposal {
        self.proposals.get(&id).cloned().unwrap_or_else(|| env::panic_str(ERR_NO_PROPOSAL))
    }
}
