# Tapvault design (NEAR, v0.1)

Tapvault is a self-custodial NEAR wallet whose funds can only move with two
independent factors:

1. **Something you have that can't be copied:** the secure chip in an EMV
   contactless bank card (DDA-capable). Its RSA key was loaded by the issuer and
   cannot be extracted.
2. **Something you deliberately approve:** a P-256 key in the phone's secure
   hardware behind a biometric (Android Keystore), or a FIDO2 security key /
   passkey via WebAuthn.

There's no seed phrase and no new device to buy. A transaction needs `threshold`
approvals, and every transfer needs at least one phone or security-key
approval.

This document is the source of truth for the security model. If the code and
this document disagree, one of them is a bug.

---

## 1. What counts as a signer

| Kind | Key | Signs | On-chain check |
|---|---|---|---|
| Phone | P-256 in Android Keystore, biometric per use | 32-byte message (ECDSA-SHA256) | `p256` crate in the contract, digest from the `sha256` host function |
| FIDO2 key / passkey | P-256 | WebAuthn assertion, challenge = 32-byte message | same, plus rpIdHash / flags / clientDataJSON checks |
| EMV card | ICC RSA (e = 3 or 65537, ≤ 1984 bit) | SDAD over terminal data containing a 4–8 byte Unpredictable Number | RSA recovery + EMV format + SHA-1, in-contract |

NEAR has no P-256 host function, so the contract verifies P-256 in WASM. The
sandbox test measures it at about 32 Tgas (see section 8).

**Cards that cannot be signers:** UID-only and memory cards (NTAG, Ultralight),
MIFARE Classic, and DESFire transit cards (metro cards). They can't produce an
asymmetric signature that anyone can verify. The app must detect these and
refuse them with an explanation, rather than pretending to enrol them.

### Enrolment and the PAN

The card's certificate chain (CA → issuer → ICC) is verified **on the phone**
at enrolment, and only the ICC public key goes on-chain. The certificates are
never published, because anyone can RSA-recover them, and they contain the
PAN. The card number must never leave the phone: no logs, no relayer, no
analytics.

During setup, every new signer must prove possession: a new card signs its
enrolment challenge, and a new P-256 key signs the setup message. This stops
a typo or a wrong key from bricking the wallet.

---

## 2. The core problem: a card signs whatever it's given

A card has no screen and no button. It signs **any** 4-byte Unpredictable
Number (UN) that **any** reader sends, without a PIN. That allows two attacks:

* **Skimming.** An attacker with a reader near your pocket collects
  signatures over UNs they choose.
* **Grinding.** If the UN were just a prefix of the transaction hash, an
  attacker with one skimmed signature over UN `X` could vary transaction
  fields offline until the hash starts with `X`. That takes about 2³²
  hashes, which is minutes on a laptop.

### Fix: commit, then challenge

1. **`propose`** stores the action. The intent hash is
   `H(intent-domain ‖ len ‖ account_id ‖ proposal_id ‖ borsh(action))`. A
   phone proposer signs the intent hash, which also counts as its approval.
2. `propose` schedules **`seal_challenge`** on the wallet contract itself. A
   receipt created during execution always runs in a **later block**, so the
   randomness it captures (`env::random_seed`) did not exist when the action
   was committed. It is stored on the proposal, once.
3. The card challenge is `H(card-approve ‖ intent ‖ seed)`. **`approve_card`**
   checks that the card's SDAD UN equals its first `un_len` bytes.

No skimmed signature could have targeted the seed, and the action can no
longer change to grind against it. Unlike a sliding "recent block hash"
window, the seed is fixed per proposal, so there is no expiry race and no
second guess for an attacker.

Rules that keep this sound:

* **Exactly one seed per proposal.** `seal_challenge` is `#[private]` (only the
  contract can call it) and refuses to overwrite a seed.
* **Card approvals expire 10 minutes after the seal.** A read of the challenge
  taken later is useless, so the only way to use a card against a sealed
  challenge is to be next to it during those minutes (the live-relay case
  below).
* **Proposals are limited per signer**: 24 per day, and at most 4 open at a
  time that can still complete. Every proposal
  is one lottery ticket against an attacker's pool of skimmed signatures. With
  `K` skimmed signatures and `T` proposals, the chance of a hit is about
  `K·T / 2³²`. For `K = 100,000` and `T = 24`, that is about 0.06% per day,
  and a hit still needs a phone approval and is still capped by limits.
* **A proposal from a card is not an approval.** A card proposer signs
  `H(card-propose ‖ intent)`, which is computable before the proposal exists.
  That stops anonymous spam, but it can't count as an approval.

**Trust in the seed.** `random_seed` comes from the block producer's VRF. The
producer of the sealing block learns it slightly before everyone else and
could, at most, decline to produce the block. It cannot choose the value.
An attacker would also need that producer's cooperation *and* a skimmed
signature matching 32 bits of it.

### What this does **not** fix: live relay

An attacker who controls your phone (malware) **and** has a reader against
your card **at the same moment** can relay the real challenge to the card.
No protocol fixes this, because a card with no button can't prove the owner
is present. The defences are limits, delays, freeze and notifications
(section 3). We say this plainly in the product and do not claim "a stolen
phone can't do anything".

---

## 3. Limits don't block, they delay

Per asset (`Near`, or a NEP-141 token contract such as USDC):

* `instant_max`: the largest single transfer that can execute immediately.
* `daily_max`: the daily allowance for immediate transfers. It refills
  continuously (`daily_max` per 24 hours), so there is no window edge: at
  most one day's allowance can ever go out at once.

A transfer within both limits executes as soon as it has enough approvals.
Anything else waits `transfer` delay (default 24 hours, minimum 1 hour),
during which any signer can **cancel** it or **freeze** the wallet. An asset
with no configured limit is always delayed. So the most a successful
attacker can take instantly is one day's allowance.

Transfers on NEAR are asynchronous. If one fails (for example, the receiver
isn't registered with the token), the funds return and the `on_transfer`
callback gives the allowance back.

| Action | Approvals needed | Delay |
|---|---|---|
| Transfer within limits | threshold, including ≥ 1 phone/key | none |
| Transfer over limits | threshold, including ≥ 1 phone/key | `transfer` |
| Config change with a phone/key approval | threshold | `config` (default 24 h) |
| Config change with no phone/key approval (lost phone) | threshold | `recovery` (default 72 h, ≥ config) |

**Delays run from full approval.** A proposal's clock starts when it first
has every approval it needs (`ready_ts`), not when it was proposed. Collecting
approvals slowly can't shorten the time the owner has to react.

Approved proposals must execute within 7 days after their delay ends. A
proposal that never gets all its approvals can be deleted by anyone after a
day (`close_stale`).

**Freeze.** Any single signer can freeze: a phone signs, or a card taps. A
freeze blocks all transfers, including ones already approved. Unfreezing is a
delayed config proposal. Each freeze signature is single-use (the freeze
nonce).

**Cancel.**
* A phone/key signer can cancel **any** proposal.
* A card can cancel any proposal **except one that removes that card**.

The reason for the asymmetry: a thief can use a stolen card (no PIN) but
can't use a biometric-locked phone key. A stolen card therefore can't block
its own removal. An attacker who somehow holds enough signers to pass a
proposal that strips the owner's signers is still vetoed by the owner's phone.
The result is a stalemate, not a takeover. The trade-off is that a
malware-compromised phone can also veto, so recovery can be stalled (but
funds can't be stolen this way).

**Any config change voids every other pending proposal.** Approval bitmaps
are indexed by signer position, so the contract bumps `signer_epoch` and
proposals from an older epoch can't execute. Anyone can delete them with
`close_stale`.

---

## 4. Recommended setup and recovery

Recommended: **phone + 2 cards (or phone + card + guardian's phone), threshold 2.**

| Lost | What still works |
|---|---|
| Card | Phone + other card. Remove the lost card with a config proposal (24 h). |
| Phone | Two cards propose "remove old phone, add new phone". Recovery delay applies (72 h). The old phone can't be used by a thief (biometric). |
| Phone + one card (bag stolen) | Need the second card plus a guardian. The thief has one usable key (the card), which is below threshold. |

With threshold 2 and only phone + one card, losing either one locks the
wallet. The app should push users to enrol a third signer.

---

## 5. Payment flows

**Send from the phone**
1. The user enters a recipient and amount. The app shows them; the user
   approves with a fingerprint, and the phone signs the intent hash.
2. The relayer submits `propose`. The seed is sealed about one block (~1 s)
   later; the app reads the card challenge from `get_proposal`.
3. The user taps the card on the phone. The app sends `SELECT …,
   INTERNAL AUTHENTICATE(UN = challenge[..un_len])` and never asks for a PIN
   or runs a payment.
4. The relayer submits one transaction with two actions: `approve_card` and
   `execute`.

**Pay at a merchant POS** (not built yet)
1. The POS reads the card's ICC public key and finds the wallet (an opt-in
   registry, or a wallet id shown by the phone).
2. The POS creates a payment request, and the relayer pushes it to the
   user's phone.
3. The user checks the merchant and amount, and approves with a fingerprint.
   The phone's `propose` lands.
4. The POS shows "tap again". The card signs the challenge, and the POS
   submits `approve_card` + `execute`. The POS verifies the result on-chain
   itself.

The POS sees the PAN while reading the card's certificates (as every payment
terminal does). It must not store or transmit it.

---

## 6. Accounts and storage

* **One wallet = one NEAR account** running this contract. The account holds
  the NEAR and is the NEP-141 token holder.
* `new` can only be called by the wallet account itself (the app deploys and
  initialises in one transaction), so nobody can race to initialise someone
  else's freshly deployed wallet. (near-sdk silently ignores `#[private]` on
  `#[init]` methods, so this is checked by hand; the sandbox test proves it.)
* `setup_finalize` must be sent by the wallet account itself and **deletes the
  key it was signed with**. Check with `near account list-keys` that no other
  full-access key remains: after that, only the contract's rules move funds.
* Proposals live in contract storage and are deleted on execute, cancel or
  `close_stale`. Storage is paid from the wallet's own balance, so it must
  keep a little NEAR even if it mostly holds tokens.
* The relayer only pays gas. It can't move funds. If it goes down, the app
  can submit transactions from any NEAR account.

---

## 7. Known gaps (be honest about these)

1. **Untested on real cards.** The verifier passes synthetic DDA/fDDA vectors
   generated independently in Python, and a simulated card. Real Visa,
   Mastercard and RuPay cards from several issuers must be tested next. Visa
   fDDA (signature in the GPO response) is supported in principle through the
   configurable UN offset, but has not been tried.
2. **A raw phone key's biometric lock can't be checked on-chain.** The app
   must verify Android Key Attestation at enrolment (that the key is
   hardware-backed and has `setUserAuthenticationRequired`).
3. **`AddSigner` via config proposal has no proof of possession.** A bad key
   only hurts the wallet that adds it. Fix by requiring the new key to sign
   during the delay window.
4. **Card challenges for propose, cancel and enrolment are deterministic.**
   They prove possession and only ever stop things or start a proposal that
   still needs approvals, so a skimmed one can at worst cancel a proposal or
   use up one of the card's proposal slots. Freeze challenges use a random
   nonce that changes on every freeze and unfreeze, so they can't be
   collected ahead of time.
4b. **Slow approvers.** A card must approve within 10 minutes of the seal, and
   a proposal without all its approvals can be closed after a day. A
   recovery that needs a guardian's phone must therefore be approved within
   a day, and one that needs two cards kept in different places must be done
   in one 10-minute session.
4c. **iPhone.** iOS doesn't let apps talk to payment cards over NFC, so the
   card tap needs an Android phone (or, later, a USB reader on a computer).
   An iPhone passkey can still be the phone signer (WebAuthn is supported).
5. **No upgrade path.** With its keys deleted, the account can't redeploy.
   A delayed `UpgradeCode` config op is the planned fix.
6. **Deployment cost.** The contract is about 330 KB, so each wallet locks
   roughly 3.3 NEAR for code storage. NEAR global contracts (deploy once,
   reference by hash) would remove that.
7. **NEP-141 storage registration.** A token refuses a receiver that hasn't
   paid storage with it. The transfer then fails and the allowance is
   restored (section 3), but the app should register receivers first.
8. **Card network rules and regulation.** Using bank cards for
   non-payment signing may conflict with Visa or Mastercard rules. Crypto
   payments in India have tax, TDS and FIU obligations. Get legal advice
   before launch.
9. **No external audit.** Don't hold real funds before one.

---

## 8. Measured gas (sandbox, nearcore 2.6.5)

From `cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture`:

| Step | Gas |
|---|---|
| deploy + `new` (P-256 check) | 56.3 Tgas |
| `setup_add_signer`, 1024-bit e = 3 card | 34.8 Tgas |
| `setup_add_signer`, 1984-bit e = 65537 card | 53.5 Tgas |
| `propose` by phone | 34.7 Tgas, plus 2.0 Tgas for `seal_challenge` |
| `approve_card`, 1024-bit e = 3 | 2.6 Tgas |
| `approve_card`, 1984-bit e = 65537 | 21.3 Tgas |
| `execute` (NEAR transfer) | 2.7 Tgas, plus 1.6 Tgas callback |
| `freeze` by card | 2.4 Tgas |

The limit is 300 Tgas per receipt; the worst step uses under a fifth of it.

## 9. Security review

An independent review of the contract (read-only, run in a separate session)
found one high, three medium and three low issues. All were fixed and
re-reviewed:
card approvals now expire, delays run from full approval, the daily limit
refills continuously, `new` and `setup_finalize` must come from the wallet
account, open proposals are capped per signer, freeze nonces are random,
freeze is refused during setup, and passkey signers must require user
verification. This is not a substitute for a professional audit.

## 10. Test plan (next)

* Cards: a matrix of at least 10 real cards (Visa, Mastercard, RuPay, debit
  and credit, several banks) recording DDA vs fDDA vs CDA-only, DDOL,
  exponent and modulus size.
* Sandbox: NEP-141 transfers (including an unregistered receiver), WebAuthn
  signers, and the recovery path with two cards.
* Fuzz: the WebAuthn parser and the bigint remainder (against an independent
  big-integer library).
