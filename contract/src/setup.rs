//! Wallet setup. During setup the first P-256 signer (the phone) adds the
//! other signers without delays; each new signer must prove possession of its
//! key so a typo can't lock the wallet. `setup_finalize` sets threshold,
//! delays and limits, turns on all protections, and locks the account.

use near_sdk::{env, near, Promise};
use tapvault_core::domain;

use crate::auth::{verify_card, verify_p256, NearSha256};
use crate::*;

/// How a new signer proves it holds its key.
#[near(serializers = [borsh, json])]
#[derive(Clone, Debug)]
pub enum Possession {
    /// The new P-256 key signs the same setup message as the authorizer.
    P256(P256Sig),
    /// The new card signs the enrolment challenge.
    Card(CardSig),
}

#[near]
impl Tapvault {
    /// `authorizer_index` must be an existing P-256 signer.
    pub fn setup_add_signer(
        &mut self,
        new_signer: SignerKey,
        authorizer_index: u8,
        authorizer_sig: P256Sig,
        possession: Possession,
    ) {
        check(self.setup_mode, ERR_NOT_IN_SETUP);
        check(self.signers.len() < MAX_SIGNERS, ERR_TOO_MANY_SIGNERS);
        new_signer.validate();
        let new_hash = new_signer.key_hash();
        check(!self.signers.iter().any(|s| s.key_hash() == new_hash), ERR_DUPLICATE_SIGNER);

        let account = account_bytes();
        let count = self.signers.len() as u8;
        let msg = domain::setup_add_message::<NearSha256>(&account, count, &new_hash);
        let authorizer = self.signer(authorizer_index);
        check(authorizer.is_deliberate(), ERR_WRONG_SIGNER_KIND);
        verify_p256(authorizer, &authorizer_sig, &msg);

        match &possession {
            Possession::P256(sig) => verify_p256(&new_signer, sig, &msg),
            Possession::Card(sig) => {
                let ch = domain::card_enroll_challenge::<NearSha256>(&account, count, &new_hash);
                verify_card(&new_signer, sig, &ch);
            }
        }
        self.signers.push(new_signer);
    }

    /// Leave setup. Must be sent by the wallet account itself: the key it
    /// signed with is deleted, so from then on only the contract moves funds.
    /// (Check that no other full-access key remains on the account.)
    pub fn setup_finalize(&mut self, args: FinalizeArgs, authorizer_index: u8, sig: P256Sig) {
        check(self.setup_mode, ERR_NOT_IN_SETUP);
        check(env::signer_account_id() == env::current_account_id(), ERR_FINALIZE_SIGNER);
        let msg = domain::finalize_message::<NearSha256>(&account_bytes(), &borsh_bytes(&args));
        let authorizer = self.signer(authorizer_index);
        check(authorizer.is_deliberate(), ERR_WRONG_SIGNER_KIND);
        verify_p256(authorizer, &sig, &msg);

        self.threshold = args.threshold;
        self.delays = args.delays;
        self.limits = args
            .limits
            .into_iter()
            .map(|l| MintLimit {
                asset: l.asset,
                instant_max: l.instant_max,
                daily_max: l.daily_max,
                updated_at: 0,
                used: U128(0),
            })
            .collect();
        self.validate_config();
        self.setup_mode = false;

        let _ = Promise::new(env::current_account_id()).delete_key(env::signer_account_pk());
        events::emit("setup_finalized", near_sdk::serde_json::json!({ "threshold": self.threshold }));
    }
}
