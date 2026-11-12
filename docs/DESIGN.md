# Tapvault design (NEAR, v0.1)

Tapvault is a self-custodial NEAR wallet whose funds can only move with two
independent factors:

1. **Something you have that can't be copied:** the secure chip in an EMV
   contactless bank card. Its RSA key was loaded by the issuer and cannot be
   extracted. The card only has to RSA-sign a number the reader picks, which
   contactless cards do in one of three ways: DDA, Visa fDDA or CDA
   (section 1).
2. **Something you deliberately approve:** a P-256 key in the phone's secure
   hardware (Android Keystore, in the TEE), unlocked for every single use by
   a fingerprint or the screen lock (fingerprint only on Android 9 and 10),
   or a FIDO2 security key / passkey via WebAuthn (supported by the
   contract; the app has no client for it yet).

There's no seed phrase and no new device to buy. A transaction needs
`threshold` approvals, and every transfer needs at least one phone or
security-key approval.

This document is the source of truth for the security model. If the code and
this document disagree, one of them is a bug. Exact method behaviour, signed
messages and constants are in [CONTRACT.md](CONTRACT.md); how the app talks to
cards is in [CARDS.md](CARDS.md).

> **Status, 12 Nov 2026.** NEAR testnet only. The contract and the app's
> phone + card flows below are implemented and tested with simulated cards
> (mocked runtime, sandbox node, live testnet). Not built: the merchant POS
> flow, a WebAuthn client in the app, code upgrades. Implemented but not
> tested end to end: NEP-141 transfers, WebAuthn signers, recovery with two
> cards (section 10). **Nothing has been run against a real bank card or a
> real phone's NFC yet.** No professional audit. Evidence and open items:
> [STATUS.md](STATUS.md).

---

## 1. What counts as a signer

| Kind | Key | Signs | On-chain check |
|---|---|---|---|
| Phone | P-256 in the Android Keystore (TEE), fingerprint or screen lock per use | 32-byte message (ECDSA-SHA256) | `p256` crate in the contract, digest from the `sha256` host function |
| FIDO2 key / passkey | P-256 | WebAuthn assertion, challenge = 32-byte message | same, plus rpIdHash / flags (user verification required) / clientDataJSON checks |
| EMV card | ICC RSA (e = 3 or 65537, 512 to 1984 bit) | SDAD over terminal data containing a 4 to 8 byte Unpredictable Number | RSA recovery + EMV format (`05` or `95`) + SHA-1, in-contract |

NEAR has no P-256 host function, so the contract verifies P-256 in WASM. The
sandbox test measures it at about 32 Tgas (section 8).

**Cards that cannot be signers:** UID-only and memory cards (NTAG,
Ultralight), MIFARE Classic, and transit cards such as DESFire metro cards.
They can't produce an asymmetric signature that anyone can verify. Neither
can a bank card without a key of its own (SDA-only: no card certificate). The
app refuses them with a reason instead of pretending to enrol them: a tag
that doesn't speak ISO-DEP gets "This card doesn't speak ISO-DEP. Transit
cards, stickers and key fobs can't sign; use a contactless bank card.", and
the card check marks a card with no payment application, no card key, a
card key the contract wouldn't accept (checked before any signing attempt),
or no working signing method as "Can't sign".

### How a card signs: three methods

A card has to RSA-sign data containing an Unpredictable Number (UN, EMV tag
`9F37`) that the reader chose. Contactless cards do this in one of three ways.
All three produce the same kind of signature, Signed Dynamic Application
Data (SDAD) whose SHA-1 covers the reader's terminal data, so the contract
checks them all the same way.

| Method | Typical cards | How the app gets the signature | Terminal data the card signs | Format byte |
|---|---|---|---|---|
| DDA | many contact cards; fewer answer over NFC | INTERNAL AUTHENTICATE with the DDOL values | the DDOL values; with the default DDOL `9F37 04`, just the 4-byte UN | `05` |
| Visa fDDA | Visa payWave | the signature comes in the GET PROCESSING OPTIONS answer; `9F69` from a fresh READ RECORD of the last AFL record | UN (4) ‖ amount (6) ‖ currency (2) ‖ Card Authentication Related Data `9F69` (7) = 19 bytes | `95` online, `05` offline |
| CDA | Mastercard contactless and others | GENERATE AC with the CDA bit set (ARQC requested); a card that declines a zero amount is asked again with amount 100 (1.00 INR) | the UN only (4 bytes) | `05` |

- The card signs only the **first `un_len` bytes** of the contract's 32-byte
  challenge, as its UN. Real cards' DOLs ask for 4 bytes. Section 2 explains
  why 4 bytes are enough.
- Each card is enrolled with the **layout** its signature had at enrolment
  (`un_offset`, `un_len`, `terminal_data_len`), stored on-chain with its key,
  and every later signature must have exactly that layout. The app remembers
  how the card signed (`CardRecipe`, kept on the phone) and falls back to
  other methods that give the same layout (`CardService.sign`).
- The app first asks for an online cryptogram (Visa: TTQ `37 80 40 00`, so
  no offline counters are used; CDA: an ARQC) and only then allows offline
  approval (Visa: TTQ `37 00 40 00`; CDA: a TC). No PIN is asked and
  nothing is ever sent to a bank, but fDDA and CDA run the card's
  transaction far enough to get a signature, which has side effects
  (section 7, gap 3).
- This follows the EMV specs and public captures of real Visa and Mastercard
  cards. How **RuPay** cards sign is unknown: NPCI's contactless specs aren't
  public. None of it has run against a real card yet.

### Signed Data Format `05` or `95`, and why accepting `95` is safe

A recovered SDAD reads
`6A | format | 01 | Ldd | ICC dynamic data | BB…BB | SHA-1 | BC`. The format
byte is `05` for DDA, CDA and offline fDDA, and `95` when a Visa card signs
fDDA data on an online (ARQC) transaction, which is what the app asks for
first. Contract builds before 12 Nov accepted only `05` and so refused Visa
online signatures; the build shipped since 12 Nov accepts both.

Accepting `95` is safe:

- **Same proof.** Both formats are hashed the same way: SHA-1 over the bytes
  from the format byte to the end of the padding, followed by the terminal
  data that holds the UN. A `95` signature proves exactly what a `05` one
  does: the card's private key signed terminal data containing the
  challenge.
- **No relabelling.** The format byte is inside the RSA-signed block and
  inside the SHA-1 input, so a `05` signature can't pass as `95` or the
  other way round.
- **Nothing else gets in.** Every other format byte is still refused (test
  `only_formats_05_and_95_are_accepted`). EMV's other signed formats
  (issuer and card certificates, signed static data) are made with the CA's
  or the issuer's key, never with the card's own key.
- **The only extra meaning of `95`** is that the card also produced an
  online cryptogram in that transaction. Tapvault never sends cryptograms
  anywhere; the compliance side of that is gap 3 in section 7.

The contract re-check after this change found no issue (section 9).

### What the contract checks, and what it doesn't

The contract checks the RSA recovery, the header, trailer, format, hash
algorithm, padding and SHA-1, that the terminal data has exactly the enrolled
length, and that the enrolled UN position holds the challenge's first
`un_len` bytes. It does **not** check the certificate chain (the phone does,
below), the ICC dynamic data, or any terminal data other than the UN. In
fDDA terminal data only the UN is bound to the challenge; amount, currency
and `9F69` are free bytes. That is safe: they are inside the card's SHA-1,
so they are whatever the card actually saw, and only the UN carries the
intent. The step-by-step check is in
[CONTRACT.md](CONTRACT.md#the-card-signature-check).

SHA-1 is there because EMV requires it. Collision attacks on SHA-1 don't
help an attacker: the challenge is fixed only when it is sealed, after the
action is committed, so matching it would take a second preimage, not a
collision.

### Enrolment and the PAN

The card's certificate chain (CA → issuer → ICC) is verified **on the phone**
at enrolment (`EmvSession.recoverCardKey`, against the CA keys in
`android/core/src/main/resources/capk.txt`), and only the ICC public key goes
on-chain. The certificates are never published, because anyone can
RSA-recover them, and they contain the PAN. The card number must never leave
the phone: no logs, no relayer, no analytics. The card report the app can
share shows only the last four digits, and its command trace leaves out the
PAN, track data, cardholder name and certificates.

The on-chain key is still a stable identifier of the card. Anyone who reads
the card (a skimmer, any payment terminal) can recover the same public key
from its certificates, compute its key hash and recognise the wallet it signs
for. That links the card to the wallet; it doesn't reveal the card number.

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
   randomness it captures (`env::random_seed_array()`) did not exist when
   the action was committed. It is stored on the proposal, once.
3. The card challenge is `H(card-approve ‖ intent ‖ seed)`. **`approve_card`**
   checks that the UN in the card's signed terminal data equals its first
   `un_len` bytes.

No skimmed signature could have targeted the seed, and the action can no
longer change to grind against it. Unlike a sliding "recent block hash"
window, the seed is fixed per proposal, so there is no expiry race and no
second guess for an attacker.

Rules that keep this sound:

* **Exactly one seed per proposal.** `seal_challenge` is `#[private]` (only the
  contract can call it) and does nothing if the proposal already has a
  seed.
* **Card approvals expire 10 minutes after the seal** (`CARD_APPROVAL_WINDOW`,
  600 s). A read of the challenge taken later is useless, so the only way to
  use a card against a sealed challenge is to be next to it during those
  minutes (the live-relay case below). The window doesn't age signatures
  collected *before* the seal (a card signature carries no time); the seal
  is what makes those useless, up to the odds below.
* **Proposals are limited per signer**: 24 per day
  (`MAX_PROPOSALS_PER_DAY`), and at most 4 live at a time
  (`MAX_OPEN_PER_SIGNER`; live means fully approved and not yet removed, or
  created in the last 660 s). Every proposal is one lottery ticket against
  an attacker's pool of skimmed signatures.
* **A proposal from a card is not an approval.** A card proposer signs
  `H(card-propose ‖ intent)`, which is computable before the proposal exists.
  That stops anonymous spam, but it can't count as an approval.

### How much a 4-byte UN is worth

Real cards ask for a 4-byte UN (`9F37 04` in their DOLs), so a card signature
carries 32 bits of the challenge. (The contract accepts `un_len` up to 8;
longer would only help.)

To approve with a card they don't have in hand during the 10-minute window,
an attacker needs a signature collected **earlier** over exactly the 4 bytes
that a future seal will produce. Each proposal gets one fresh, unpredictable
challenge, and it is the same for every card in the wallet. With `K`
collected signatures (from any of the wallet's cards) and `T` proposals, the
chance of at least one match is about

```
K × T / 2^32          (2^32 = 4,294,967,296)
```

The contract bounds `T`. Proposals are limited to 24 per day **per signer**
(4 live at a time), and each needs a signer's proof: a phone signature (a
fingerprint every time) or a card's propose signature, which a skimmer can
also collect in advance for intents it can predict. So `T` is up to 24 a day
for each signer the attacker can propose with, and every card added to a
wallet adds to both `K` and `T`:

| Collected signatures `K` | Proposals per day `T` | Chance per day | Mean time to a match |
|---|---|---|---|
| 10,000 | 24 (one signer) | 0.0056 % | about 49 years |
| 65,535 | 24 | 0.037 % | about 7.5 years |
| 100,000 | 24 | 0.056 % | about 4.9 years |
| 100,000 | 48 (phone and one card proposing) | 0.11 % | about 2.5 years |
| 300,000 (3 cards) | 96 (phone and 3 cards proposing) | 0.67 % | about 5 months |

What a match buys is **one card approval**, nothing more. A transfer also
needs a phone approval (a fingerprint) on the same proposal, anything above
the instant limits waits the transfer delay (cancel or freeze meanwhile),
and the most that can go out at once is one day's allowance (section 3).

Collecting `K` isn't free either. Every signature is a separate card
transaction within NFC range (a few centimetres). For fDDA and CDA each one
also advances the card's 2-byte transaction counter (ATC, at most 65,535 over
the card's life), and a large jump shows up to the issuer on the card's next
real payment.

The lesson for setups with several cards: each extra card adds recovery
options, but also a skimming target and another 24 lottery tickets a day.
A guardian's phone as the extra signer gives recovery without that cost
(section 4).

**Trust in the seed.** The block randomness comes from the block producer's VRF. The
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
  most one day's allowance can ever go out at once. Delayed transfers count
  against it too once they execute.

A transfer within both limits executes as soon as it has enough approvals.
Anything else waits the `transfer` delay, during which any signer can
**cancel** it or **freeze** the wallet. An asset with no configured limit is
always delayed. So the most a successful attacker can take instantly is one
day's allowance.

Transfers on NEAR are asynchronous. If one fails (for example, the receiver
isn't registered with the token), the funds return and the `on_transfer`
callback gives the allowance back.

| Action | Approvals needed | Delay (default; app wallets) |
|---|---|---|
| Transfer within limits | threshold, including ≥ 1 phone/key | none |
| Transfer over limits | threshold, including ≥ 1 phone/key | `transfer` (24 h; 1 h) |
| Config change with a phone/key approval | threshold | `config` (24 h; 1 h) |
| Config change with no phone/key approval (lost phone) | threshold | `recovery` (72 h; 24 h), never shorter than `config` |

Bounds: `transfer` and `config` 1 hour to 30 days, `recovery` 1 to 30 days.
The app creates every wallet with the shortest delays the contract allows
(`Delays.SHORTEST`), which suits testing; a real product would use longer
ones.

**Delays run from full approval.** A proposal's clock starts when it first
has every approval it needs (`ready_ts`), not when it was proposed. Collecting
approvals slowly can't shorten the time the owner has to react.

Approved proposals must execute within 7 days after their delay ends. A
proposal that never gets all its approvals can be deleted by anyone after a
day (`close_stale`). **Nothing executes by itself:** after a delay, someone
must send `execute` (in the app, Activity → Execute). `execute` is
permissionless, so a service could do this; none exists yet.

**Freeze.** Any single signer can freeze: a phone signs, or a card taps. A
freeze blocks all transfers, including ones already approved, and new
transfer proposals. Unfreezing is a delayed config proposal. Each freeze
signature is single-use: the freeze nonce changes to a random value on every
freeze and unfreeze (gap 9 in section 7 says what that does and doesn't
stop).

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
`close_stale`. The per-signer proposal counters reset at the same time.

---

## 4. Recommended setup and recovery

Recommended: **phone + 2 cards (or phone + card + guardian's phone), threshold 2.**

| Lost | What still works |
|---|---|
| Card | Phone + other card. Remove the lost card with a config proposal (24 h). |
| Phone | Two cards propose "remove old phone, add new phone". Recovery delay applies (72 h). The old phone can't be used by a thief (biometric). |
| Phone + one card (bag stolen) | Need the second card plus a guardian. The thief has one usable key (the card), which is below threshold. |

With threshold 2 and only phone + one card, losing either one locks the
wallet. **That is what the app sets up today:** phone (signer 0) + one card
(signer 1), threshold 2, and there is no screen yet to add a third signer.
An app wallet therefore can't be recovered: uninstalling the app, clearing
its data or removing the screen lock destroys the phone key and strands the
wallet's NEAR. That is acceptable only on testnet. The "Forget this wallet?"
dialog in Settings warns about it: "This deletes the phone key. With a
single card the wallet can't approve anything without it, so its NEAR stays
locked in the wallet for good (on testnet that is only test NEAR)."

Section 2's numbers are the cost of extra cards: each one is another
skimming target and another 24 proposals a day for an attacker who holds its
signatures. A guardian's phone as the third signer gives the same recovery
without that cost.

---

## 5. Payment flows

**Create a wallet** (the card is read before anything is deployed)
1. The app creates the phone key in the Keystore, then two testnet accounts:
   the wallet and a fee account, from the faucet or, when the faucet refuses,
   from a sponsor account (only if the APK was built with the sponsor's key).
   Their names and keys are saved before anything is created, so a retry
   reuses them.
2. The user taps the card. In one tap the app reads the card's certificate
   chain, recovers its key, finds a method that makes it sign, and has it
   sign the enrolment challenge. That challenge depends only on the wallet's
   account id, the signer count and the card's key hash, so it needs no
   contract yet. A card that can't sign stops setup here, while undoing it
   is still cheap: with a sponsor configured, Start over deletes the
   untouched accounts and returns their NEAR to the sponsor.
3. Fingerprint: one transaction deploys the contract and calls `new` with
   the phone key.
4. Fingerprint: `setup_add_signer` adds the card with the proof it signed.
5. Fingerprint: `setup_finalize` sets threshold, delays and limits and
   deletes the wallet account's own key. The app checks that no keys remain.

Each step checks the chain first and skips what already landed, and a card
that was read (its key and signed proof) is kept on the phone until setup
finishes, so a retry doesn't need another tap.

**Send from the phone**
1. The user enters a receiver and amount. Before any prompt the app checks
   NFC, the frozen flag, the spendable balance and that the receiver exists.
   The fingerprint prompt names the amount and receiver; the phone signs the
   intent hash it computed itself.
2. The fee account submits `propose`. The seed is sealed about one block
   (~1 s) later; the app reads the card challenge from `get_proposal`.
3. The user taps the card. The app puts the challenge's first `un_len` bytes
   into the UN and gets the card to sign with the method it used at
   enrolment. It never asks for a PIN and sends nothing to a bank. Before
   sending, it checks the signature against the enrolled key, layout and
   challenge.
4. Within the limits: one transaction with two actions, `approve_card` and
   `execute`. Over the limits: `approve_card` only, and after the transfer
   delay someone taps Execute in Activity.

If the tap fails after the fingerprint, the proposal stays open for the
10-minute card window: sending the same payment again finishes it instead of
creating another (the contract allows 4 open proposals per signer).

**Pay at a merchant POS** (not built yet)
1. The POS reads the card's ICC public key and finds the wallet (an opt-in
   registry, or a wallet id shown by the phone).
2. The POS creates a payment request, and the relayer pushes it to the
   user's phone.
3. The user checks the merchant and amount, and approves with a fingerprint.
   The phone's `propose` lands.
4. The POS shows "tap again". The card signs the challenge with whichever
   method it supports, and the POS submits `approve_card` + `execute`. The
   POS verifies the result on-chain itself.

The POS sees the PAN while reading the card's certificates (as every payment
terminal does). It must not store or transmit it.

---

## 6. Accounts and storage

* **One wallet = one NEAR account** running this contract. The account holds
  the NEAR and is the NEP-141 token holder.
* **Plus a fee account** (`tv-<10 hex>-gas…`) whose ed25519 key the app
  stores. It signs and pays gas for every call after setup: the relayer role.
  It can't move funds, and if it runs dry the same calls can be sent from any
  NEAR account.
* `new` can only be called by the wallet account itself (the app deploys and
  initialises in one transaction), so nobody can race to initialise someone
  else's freshly deployed wallet. (near-sdk silently ignores `#[private]` on
  `#[init]` methods, so this is checked by hand; the sandbox test proves it.)
* `setup_finalize` must be sent by the wallet account itself and **deletes the
  key it was signed with**. Check that no other full-access key remains
  (`view_access_key_list`, see [CONTRACT.md](CONTRACT.md#verify-a-wallet)):
  after that, only the contract's rules move funds. Until then the wallet's
  own key, kept in the app's storage during setup, controls everything.
* **The code is fixed.** With no keys and no upgrade method, a wallet keeps
  the code it was deployed with. Check a wallet by its `code_hash`:
  `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf` for the build shipped since
  12 Nov (committed as `contract/res/tapvault_contract.wasm`, hash-checked by
  the Android build).
* Proposals live in contract storage and are deleted on execute, cancel or
  `close_stale`. Storage is paid from the wallet's own balance: the
  334,258-byte code locks about 3.34 NEAR and each open proposal a little
  more, so a wallet must keep some NEAR even if it mostly holds tokens.
* The app reaches NEAR through three public RPCs with fallback. The phone
  computes the intent hashes it signs in Send and Request unfreeze itself,
  and the card's enrolment and freeze challenges. Two things come from the
  RPC on trust: the card challenge every card approval signs (Send,
  Activity → "Approve · card", Request unfreeze), and the intent hash behind
  Activity → "Approve · phone" (gap 6 in section 7).

---

## 7. Known gaps (be honest about these)

1. **Untested on real cards and real phones.** The verifier passes synthetic
   vectors generated independently in Python, and simulated cards that answer
   real APDUs like a DDA card, a Visa-style fDDA card and a Mastercard-style
   CDA card, in unit tests and on live testnet. No real card has signed for
   Tapvault yet, and no real phone's NFC, Keystore or fingerprint prompt has
   run this build. The plan is in [TESTING.md](TESTING.md).
2. **RuPay is unknown.** NPCI's contactless specs aren't public, so how
   RuPay cards sign is unknown. `capk.txt` has RuPay CA indices 04, 05, 06
   and 6A to 6D (6A and 6B are labelled test keys in their source list). A
   newer index may be missing; the card check then says "no CA key for
   A000000524 index XX: this app doesn't know that network key yet; share
   the card report so it can be added".
3. **Every tap is a real card transaction.** Online fDDA and CDA make the
   card produce a real online-authorisation cryptogram (ARQC) on every
   Tapvault tap; on Visa cards the cryptogram comes with the GPO answer, so
   it is produced whichever method then signs. It is never sent to the bank.
   Each tap also advances the card's transaction counter (ATC), which the
   issuer sees on the card's next real payment. This isn't a contract issue,
   but it is a card-network and compliance question for a real product.
   Offline signing avoids the ARQC where a card supports it (an offline TC
   under the TTQ that allows it, or DDA on a card whose GPO produces no
   cryptogram); the app currently asks for an online cryptogram first.
4. **A single-card wallet can't be recovered.** App wallets are phone + one
   card, threshold 2, and recovery needs two other signers. Losing the phone
   key (uninstall, cleared data, removed screen lock) strands the wallet.
   Testnet only.
5. **Nothing executes by itself, and approval and execution share a
   receipt.** Over-limit payments and unfreezes need someone to send
   `execute` after the delay. When the app sends `approve_card` and `execute`
   in one transaction and `execute` fails, the whole transaction is undone.
   If the contract refused with `Delay has not elapsed` (it didn't count the
   payment as within the limits, by its own clock and allowance), `Not
   enough approvals` or `Wallet is frozen`, the app sends the same card
   signature again with `approve_card` alone and the payment waits
   ("Approved, waiting" in Send, "Approved" in Activity). Any other `execute`
   failure still discards the card approval (the pre-checks make this rare;
   tap again within the window).
6. **The app takes two things from the RPC on trust.** (a) The card signs
   the `card_challenge` that `get_proposal` (or `list_proposals`, for
   Activity) returns; the view doesn't expose the seed, so the app can't
   recompute it, and the card signs whatever the app passes. A dishonest
   RPC can therefore turn each legitimate tap into a signature over a UN of
   its choosing (the power of a skimmer, section 2), for example an
   approval of a different proposal. (b) Activity →
   "Approve · phone" signs the intent hash returned by `get_proposal`
   (`TapvaultWallet.approvePhone`) under that view's description, instead
   of recomputing the hash from the action. (a) alone can't move funds,
   because a transfer also needs a phone approval and Send and Request
   unfreeze compute their intent locally; (a) and (b) together could get a
   disguised proposal approved by both. Fix: recompute the intent in
   `approvePhone`, and return the seed in `ProposalView` so clients can check
   the challenge. (Cancel and freeze also sign values read from the RPC, the
   proposal's intent hash and the freeze nonce, but those messages can only
   cancel a proposal or freeze the wallet.)
7. **A phone key's biometric lock can't be checked on-chain.** The contract
   can't see that signer 0 is a hardware key behind user authentication, and
   the app doesn't send or check Android Key Attestation (that the key is
   hardware-backed and has `setUserAuthenticationRequired`).
8. **`AddSigner` via config proposal has no proof of possession.** A bad key
   only hurts the wallet that adds it. Fix by requiring the new key to sign
   during the delay window. (The app doesn't propose `AddSigner`.)
9. **Some card challenges can be collected in advance.** The propose, cancel
   and enrolment challenges are deterministic, and the freeze challenge is
   public (the `freeze_challenge` view; the nonce starts at 0). A skimmed
   signature can at worst start a proposal that still needs approvals (and
   use up that card's proposal quota), cancel an open proposal, or freeze
   the wallet once. The random freeze nonce only means a freeze signature
   stops working after the next freeze or unfreeze; it doesn't stop someone
   collecting one for the current nonce. All of these only stop things, and
   a card can't cancel its own removal.
10. **Slow approvers.** A card must approve within 10 minutes of the seal,
    and a proposal without all its approvals can be closed after a day. A
    recovery that needs a guardian's phone must therefore be approved within
    a day, and one that needs two cards kept in different places must be
    done in one 10-minute session.
11. **iPhone.** iOS doesn't let apps talk to payment cards over NFC, so the
    card tap needs an Android phone (or, later, a USB reader on a computer).
    An iPhone passkey can still be the phone signer (WebAuthn is supported).
12. **No upgrade path.** With its keys deleted, the account can't redeploy.
    Wallets created before 12 Nov run the previous build
    (`GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow`), which refuses Visa
    online signatures (format `95`), and they stay that way. A delayed
    `UpgradeCode` config op is the planned fix.
13. **Deployment cost.** The contract is 334,258 bytes, so each wallet locks
    about 3.34 NEAR for code storage. NEAR global contracts (deploy once,
    reference by hash) would remove that.
14. **NEP-141 storage registration.** A token refuses a receiver that hasn't
    paid storage with it. The transfer then fails and the allowance is
    restored (section 3), but a client should register receivers first. (The
    app only sends NEAR today.)
15. **The CA list includes test keys, and expiry isn't enforced.**
    `capk.txt` carries some test CA keys next to the production ones, so the
    phone's chain check proves "a chain to a known CA key", not necessarily
    a production bank card. That doesn't weaken a wallet (the enrolled key
    must still sign its enrolment challenge), but a card built on a test CA
    would be accepted. An expired issuer certificate is only noted in the
    card report, and the card certificate's expiry date isn't read at all.
16. **EMV's signature scheme hasn't been assessed for this use.** EMV signs
    with ISO/IEC 9796-2 and SHA-1. Published forgery research on that scheme
    (Coron, Naccache, Tibouchi and Weinmann, CRYPTO 2009) was judged not to
    threaten payment cards for operational reasons. Tapvault uses the card
    as a general signer and accepts any ICC dynamic data, so a cryptographer
    should re-check those reasons before real funds.
17. **Other keys on the phone and in the APK.** The fee-account key and,
    during setup, the wallet account's own key sit in the app's private
    storage (not the Keystore); the sponsor key ships inside APKs built with
    it. Testnet only. After setup none of them can move a wallet's funds;
    the sponsor key can spend the sponsor's testnet NEAR.
18. **Card network rules and regulation.** Using bank cards for non-payment
    signing may conflict with Visa, Mastercard or RuPay rules (see also
    gap 3). Crypto payments in India have tax, TDS and FIU obligations. Get
    legal advice before launch.
19. **No external audit, and the build isn't reproducible.** Don't hold real
    funds before an audit. The shipped wasm can only be matched by its hash,
    not rebuilt byte for byte elsewhere
    ([contract/res/README.md](../contract/res/README.md)).

---

## 8. Measured gas (sandbox, nearcore 2.6.5)

From `cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture`
on 12 Nov 2026, with the contract compiled from the current source:

| Step | Gas |
|---|---|
| deploy + `new` (P-256 check) | 56.4 Tgas |
| `setup_add_signer`, 1024-bit e = 3 card | 34.7 Tgas |
| `setup_add_signer`, 1984-bit e = 65537 card | 53.4 Tgas |
| `setup_finalize` | 35.1 Tgas |
| `propose` by phone | 34.8 Tgas, plus 2.0 Tgas for `seal_challenge` |
| `approve_card`, 1024-bit e = 3 | 2.7 Tgas |
| `approve_card`, 1984-bit e = 65537 | 21.3 Tgas |
| `execute` (NEAR transfer) | 2.7 Tgas, plus 1.6 Tgas callback |
| `freeze` by card | 2.4 Tgas |

The P-256 check is about 32 Tgas of every step that verifies a phone
signature. The limit is 300 Tgas per receipt; the worst step (56.4 Tgas)
uses under a fifth of it. The app attaches 100 Tgas per call and 150 Tgas to
`execute` (250 Tgas for `approve_card` + `execute` in one transaction).

## 9. Security review

**First review (10 to 11 Nov 2026).** An independent read-only review of the
contract found one high, three medium and three low issues. All were fixed
and re-reviewed: card approvals now expire, delays run from full approval,
the daily limit refills continuously, `new` and `setup_finalize` must come
from the wallet account, open proposals are capped per signer, freeze nonces
are random, freeze is refused during setup, and passkey signers must require
user verification.

**12 Nov review, before the first real-phone test.** The app, client and
contract were reviewed for a first real-phone, real-card testnet run: EMV
protocol, NFC handling, Keystore and biometrics, the NEAR client and
onboarding, the app/contract boundary, app flows, build and install, and
contract security, plus research on how real cards behave. Every serious
finding was checked by two independent reviewers before being acted on.
Fixes are in commits `82cb0b1` to `e613e89`. Blockers found and fixed:

- Most contactless cards don't answer INTERNAL AUTHENTICATE over NFC. The app
  now signs with DDA, Visa fDDA or CDA, remembers how each card signed, and
  falls back to methods with the same on-chain layout.
- Visa online fDDA uses Signed Data Format `95`. The contract and the app
  accepted only `05`; both now accept `05` or `95`, and the new contract
  build ships in the app (section 1).
- Visa returns its per-transaction `9F69` in the last AFL record; the app was
  using a cached copy. It now re-reads that record after every GPO.
- The RuPay CA keys in `capk.txt` were test keys, so no real RuPay card could
  pass. Production keys 04, 05, 06, 6C and 6D were added (and Visa 10).

Other fixes covered setup (card read before the deploy, resumable steps,
Start over), payments (pre-checks before the fingerprint, reuse of an
unfinished payment, Execute shown only when the contract will accept it),
NFC (reader mode while the app is on screen), the phone key (TEE instead of
StrongBox, not invalidated by a new fingerprint), the network (three RPCs,
a sent transaction never re-signed) and the build (the reviewed wasm
committed and hash-checked). The full list and what stayed open:
[STATUS.md](STATUS.md).

**Contract re-check after the format-95 change (12 Nov).** No exploitable or
fund-moving issue. Format `05` and `95` signatures can't stand in for each
other (the format byte is inside the hashed data), layouts are
length-checked, and phone-only, card-only or network-only attackers can't
make the contract move funds. Notes from that pass, all reflected above:

- Online fDDA and CDA make the card produce a real ARQC on every tap, never
  sent to the bank: a card-network and compliance question (gap 3).
- The 24-per-day rate limit is per signer, so several cards give a skimmer
  proportionally more challenges to match; one card approval still can't
  move funds (section 2).
- In fDDA terminal data only the UN is bound; amount, currency and `9F69`
  are free bytes covered by the card's signature (section 1).

That pass looked at the contract. The app's reliance on the RPC for card
challenges and for "Approve · phone" (gap 6) was written down afterwards and
is not fixed yet.

This is not a substitute for a professional audit.

## 10. Test plan (next)

The step-by-step plan for the first real-phone, real-card run is in
[TESTING.md](TESTING.md). It has to establish, in order:

1. **What each real card does.** Card check with every card first (Visa,
   Mastercard, RuPay; debit and credit; several banks), sharing the report:
   which method signs (DDA, fDDA or CDA), Signed Data Format, terminal-data
   length, DDOL, key size and exponent, CA index.
2. **A whole wallet with a card that passed:** setup, an instant payment, an
   over-limit payment that waits, cancel, freeze by card, unfreeze.
3. **The phone's side:** where the Keystore puts the key, the fingerprint
   and screen-lock prompts, the NFC antenna position, and Android 9/10 if a
   phone with it is available.

After that:

* Sandbox: NEP-141 transfers (including an unregistered receiver), WebAuthn
  signers, and the recovery path with two cards.
* Fuzz: the WebAuthn parser and the bigint remainder (against an independent
  big-integer library).
* A cryptographer's look at gap 16, and the fixes for gap 6.
