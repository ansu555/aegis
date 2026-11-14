# Architecture

How Tapvault is put together: the components and their boundaries, the
accounts and keys, what happens in every flow, where each piece of state
lives, how the app talks to NEAR, and how errors are handled. The code is the
source of truth; symbols are named so you can search for them.

> **Status, 12 Nov 2026.** Everything described here runs on NEAR testnet and
> passes its tests with simulated cards. Nothing has run against a real bank
> card or a real phone's NFC yet. Details: [STATUS.md](STATUS.md).

Related: the security reasoning is in [DESIGN.md](DESIGN.md), method-level
contract details in [CONTRACT.md](CONTRACT.md), the card protocol in
[CARDS.md](CARDS.md), app internals and build in [ANDROID.md](ANDROID.md).

## Components

```
+---------------------------------- Android phone -----------------------------------+
|                                                                                    |
|  android/app  (Android, Jetpack Compose)                                           |
|    Screens.kt, Components.kt, Theme.kt   UI                                        |
|    AppViewModel.kt                       every flow; errors become an Outcome      |
|    Keystore.kt     KeystoreSigner        phone key, fingerprint or screen lock     |
|    MainActivity.kt IsoDepTransceiver     NFC reader mode, ISO-DEP link to the card |
|    Store.kt, PendingCard.kt              app-private settings (SharedPreferences)  |
|          |                                                                         |
|          | implements P256Signer and CardTransceiver                               |
|          v                                                                         |
|  android/core  (plain Kotlin on the JVM, no Android APIs)                          |
|    Wallet.kt       TapvaultWallet, Onboarding   contract calls, wallet creation    |
|    NearClient.kt   NearClient, NearKey          JSON-RPC, signing, RPC fallback    |
|    Model.kt, Borsh.kt, Domain.kt                bytes the contract verifies        |
|    Emv.kt, Tlv.kt, resources/capk.txt           EMV card protocol, CA keys         |
+-----------+-------------------------------------------------------+----------------+
            | NFC: ISO-DEP APDUs                                    | HTTPS JSON-RPC
            v                                                       v
   bank card: EMV chip with                         NEAR testnet: the wallet account
   its ICC RSA key                                  running contract/ (logic in
                                                    crates/tapvault-core), the fee
                                                    account, the optional sponsor;
                                                    the faucet helper (HTTPS)
```

| Component | Path | Runtime | Responsibility | Tests |
|---|---|---|---|---|
| Core logic | `crates/tapvault-core` | Rust, no dependencies | RSA public operation (`bigint`), SHA-1 and SHA-256, EMV signature check (`emv`), WebAuthn assertions (`webauthn`), every signed message (`domain`), limits, delays and approval rules (`policy`). `testcard` exists only with feature `test-card` | 20 unit + 7 EMV vector tests |
| Contract | `contract/` | Rust, near-sdk ~5.14, compiled to wasm | One instance per wallet: setup, proposals, sealed card challenges, approvals, execution, cancel, freeze, clean-up, views, NEP-297 events | 24 flow tests, 1 vectors test, opt-in sandbox test |
| Shipped contract build | `contract/res/tapvault_contract.wasm` | wasm, 334,258 bytes | Exactly what the app deploys to every new wallet ([contract/res/README.md](../contract/res/README.md)) | hash check in the Android build (`verifyWasm`) |
| Kotlin core | `android/core` | Kotlin, JVM 17 | NEAR client, contract calls, onboarding, Borsh and contract types, signed messages, EMV over a `CardTransceiver`, CA key list | `EmvTest` 15, `VectorsTest` 3, `RpcProbeTest` 1 and `TestnetE2ETest` 1 (live, opt-in) |
| App | `android/app` | Android, minSdk 28, targetSdk 35 | Screens and flows, the Keystore phone key, NFC reader mode, local storage | none automated; emulator runs only (no NFC there) |
| Tools | `tools/` | Python 3 | Generate the EMV test vectors, the Rust test card key, the simulated card's key chain | n/a |

### Seams

Three small interfaces keep the platform-specific parts swappable. The first
two are how the same Kotlin client code runs in the app and in the tests
(including the live testnet test); the third lets the same Rust core run in
the contract and in native tests:

| Interface | Defined in | In the app | In tests |
|---|---|---|---|
| `P256Signer` | `android/core/.../P256.kt` | `KeystoreSigner` (Android Keystore + BiometricPrompt) | `SoftP256Signer` (in-memory key) |
| `CardTransceiver` | `android/core/.../Emv.kt` | `IsoDepTransceiver` (`MainActivity.kt`) | `SimCard` (`android/core/src/test`) |
| `Sha256Hasher` | `crates/tapvault-core/src/lib.rs` | `NearSha256` in the contract (host function) | `SoftSha256` (software) |

### Trust boundaries

- **The contract trusts only signatures it verifies.** Anyone may call it;
  the fee account that pays for calls has no authority. Exceptions by
  design: `new` must come from the wallet account itself
  (`ERR_INIT_NOT_SELF`), `setup_finalize` must be signed by it
  (`ERR_FINALIZE_SIGNER`), and `seal_challenge` and `on_transfer` are
  `#[private]` (only the contract can call them).
- **The app trusts the RPC for reads only, with one exception.** In Send,
  Add backup card, I lost this card and Request unfreeze the phone builds the
  action itself, computes the intent hash itself (`Domain.intentHash`) and
  sets the prompt text from the same values. Activity → "Approve · phone" and
  "Cancel" start from a proposal the RPC returned, so the phone parses its
  action (`ActionJson.parse`), recomputes the intent hash, refuses if it
  differs from the view's (`ProposalInfo.verifiedAction`: "The node returned
  a request whose details don't match its hash…"), and builds the prompt
  from the parsed action. A lying RPC can make calls fail or show wrong
  balances; it can't change what a prompt approves. The exception (see
  [Known gaps](#known-gaps)):
  - The card signs the `card_challenge` returned by `get_proposal`. The view
    doesn't return the seed, so the phone can't recompute the challenge, and
    `CardService.sign` only checks that the card signed what it was given.
- **The app never sends a card signature it hasn't verified.**
  `CardService.enrol` checks the signature against the key recovered from the
  card's certificate chain, after checking that key against the contract's
  rules for a card key; `CardService.sign` checks it against the enrolled
  key, its terminal-data layout and the challenge before returning.
- **The card trusts nobody.** It signs any Unpredictable Number a reader
  sends. That is why approvals use a challenge sealed in a later block
  ([DESIGN.md](DESIGN.md) section 2) and why every transfer also needs the
  phone.

## Accounts and keys

### Accounts (one wallet = two accounts)

| Account | Name | Created by | Holds | Access keys |
|---|---|---|---|---|
| Wallet | `tv-<10 hex>.testnet`, or `tv-<10 hex>.<sponsor>` | Testnet faucet (10 NEAR) or the sponsor (5.5 NEAR, `Onboarding.Sponsor.WALLET_FUNDING`) | The user's NEAR, the contract code (about 3.34 NEAR of storage) and its state | A temporary ed25519 key until `setup_finalize` deletes it; **none** after setup |
| Fee account | `tv-<10 hex>-gas.testnet`, or `tv-<10 hex>-gas.<sponsor>` | Faucet (10 NEAR) or the sponsor (0.5 NEAR, `GAS_FUNDING`) | NEAR for gas | One ed25519 full-access key, kept in the app's `Store` |
| Sponsor (optional) | Any funded testnet account; the owner uses `tapvault-skyyy.testnet` | By hand | Testnet NEAR for new wallets | Its key is compiled into the APK (`BuildConfig.SPONSOR_KEY`) |

The `<10 hex>` tag is 5 random bytes (`Onboarding.Plan.random`). Each of the
two accounts comes from the faucet while it cooperates and from the sponsor
once it refuses (`Onboarding.createAccounts`), so a wallet can have one of
each kind. The three setup transactions are signed and paid by the wallet
account itself; every call after setup is signed and paid by the fee account.

### Keys

| Key | Lives in | Algorithm | Unlocked by | Role |
|---|---|---|---|---|
| Phone key | Android Keystore, alias `tapvault_phone_p256`, in the TEE (StrongBox deliberately not used) | ECDSA P-256 over SHA-256 | Every single use: BiometricPrompt with `BIOMETRIC_STRONG` or `DEVICE_CREDENTIAL` on Android 11+, strong biometric only on Android 9/10 | Signer 0, `SignerKey::P256` (no WebAuthn), the "deliberate" signer every transfer needs |
| Card key | The card's chip; the private half never leaves it | RSA, modulus 512 to 1984 bits, exponent 3 or 65537; signatures checked with SHA-1 (EMV SDAD) | Nothing: contactless, no PIN | Signer 1, `SignerKey::EmvCard { modulus, exponent, un_offset, un_len, terminal_data_len }` |
| Wallet account key | `Store.walletKey` (and briefly `Store.setupPlan`) | ed25519 | App storage | Signs deploy + `new`, `setup_add_signer`, `setup_finalize` (and the account's deletion by Start over before the contract step); deleted on-chain by `setup_finalize` and then from the Store |
| Fee account key | `Store.gasKey` | ed25519 | App storage | Signs and pays for every call after setup. No authority over funds |
| Sponsor key (optional) | `BuildConfig.SPONSOR_KEY` | ed25519 | In the APK | Creates and funds accounts when the faucet refuses; receives the NEAR of accounts deleted by Start over |

Phone key details (`KeystoreSigner.create`): `setUserAuthenticationRequired(true)`,
per-use authentication (`setUserAuthenticationParameters(0, …)` on Android 11+,
`setUserAuthenticationValidityDurationSeconds(-1)` on 9/10), and
`setInvalidatedByBiometricEnrollment(false)` so adding a fingerprint later
doesn't destroy the key. Removing the screen lock does invalidate it
(`KeyPermanentlyInvalidatedException`). Settings shows where the key ended up
(`KeystoreSigner.hardwareLabel`, e.g. "Trusted Execution Environment"); the
app doesn't verify Android key attestation, so the contract can't know the
key is hardware-backed.

## Flows

Notation: `[wallet key]` and `[fee key]` say which account signs and pays for
the transaction. Quoted text is exactly what the app shows. Every flow runs
inside `AppViewModel.run`, one at a time, with a busy overlay.

### Setup

`AppViewModel.beginSetup` validates the limits and NFC, saves the limits
exactly as typed (a comma becomes a dot), clears any card report left from
Card check, then `continueSetup` runs. "Continue setup" calls `continueSetup`
again after any interruption; it requires NFC only while the card still has
to be read (no `PendingCard` saved and step < 3).

```
 User            App (continueSetup)                   Card           NEAR testnet
  | Create my      |                                     |                  |
  | wallet ------->| create phone key (TEE, no prompt)   |                  |
  |                | save Plan: tag + two ed25519 keys   |                  |
  |                |-- faucet, else sponsor: wallet + fee account --------->|
  |                |-- get_wallet: no contract yet ------------------------>|
  | tap card ----->|-- SELECT, GPO, READ RECORD -------->|                  |
  |                |   recover the card key: CA > issuer > card certificate |
  |                |-- sign card_enroll_challenge ------>|                  |
  |                |<- signature (SDAD) -----------------|                  |
  |                | save PendingCard + CardRecipe       |                  |
  | fingerprint 1->| sign create_message                 |                  |
  |                |-- [wallet key] DeployContract + new ------------------>| signer 0 = phone,
  |                |                                     |                  | setup mode
  | fingerprint 2->| sign setup_add_message              |                  |
  |                |-- [wallet key] setup_add_signer(card, proof) --------->| checks phone and
  |                |                                     |                  | card; signer 1 = card
  | fingerprint 3->| sign finalize_message               |                  |
  |                |-- [wallet key] setup_finalize ------------------------>| threshold 2, delays,
  |                |                                     |                  | NEAR limit; deletes
  |                |                                     |                  | the wallet key
  |                |-- view_access_key_list: empty? ----------------------->|
  |<- "Your wallet is ready"                             |                  |
```

Prompts, in order: "Create your Tapvault wallet", "Add Visa card ****1234 as
a signer" (network and last four digits; "Mastercard" isn't followed by
"card"), "Lock the wallet: up to 1 NEAR at once, 2 NEAR a day". The tap sheet
says "Hold your bank card to the back of the phone to add it".

What makes it resumable and safe to retry:

- `Store.setupStep` records progress: 0 nothing, 1 accounts, 2 deployed,
  3 card added, 4 finalized.
- The account names and keys (`Onboarding.Plan`) are saved in
  `Store.setupPlan` before anything is created. A retry reuses accounts that
  already carry those keys (`createAccounts` checks `view_access_key_list`)
  instead of funding new ones.
- Once the accounts exist, every run reads `get_wallet` before the card and
  contract steps. "No contract yet" (CodeDoesNotExist,
  MethodNotFound, UNKNOWN_ACCOUNT, "does not exist") means deploy; any other
  error stops with "Can't reach NEAR testnet right now (…). Try Continue setup
  again." instead of risking a second copy of a step that landed. Each step
  is skipped if the chain shows it done (contract present, card enrolled,
  `setup_mode == false`).
- The card is read **before** the contract is deployed, so a card that can't
  sign stops setup while undoing it is cheap. So does a card whose key breaks
  the contract's rules (512 to 1984 bits, exponent 3 or 65537, a valid
  modulus): `CardService.enrol` checks them before asking the card to sign
  ("this card can't be a signer: …"). The card's enrolment challenge
  (`Domain.cardEnrollChallenge(wallet, 1, keyHash)`) depends only on the
  account id, the signer count and the card key, so it is known before the
  contract exists. The read card and its proof are kept in
  `Store.pendingCard` until setup finishes, so a retry doesn't need another
  tap.
- After `setup_finalize` the app checks that the wallet account has no keys
  left (log line "wallet account locked: no keys left", otherwise "WARNING:
  wallet still has keys: …"), then sets step 4 and clears `walletKey` and
  `pendingCard`.
- **Start over** (`AppViewModel.startOver`): if the APK has a sponsor (with
  a key that parses), the wallet account is deleted when step ≤ 1 and the
  fee account at any step, with the sponsor as beneficiary; then
  `resetCurrent()` clears this wallet's Store and deletes its phone key
  (other wallets on the phone are untouched), and if any
  deletion was tried, a "Started over" result lists each one (or why it
  failed). Without a sponsor
  nothing is deleted on-chain. The confirmation dialog (`SetupProgress`,
  using `AppViewModel.hasSponsor`) says which case applies: without a
  sponsor "The phone key and this setup's saved state are removed. The
  accounts made so far stay on testnet with their test NEAR."; with one, at
  step ≤ 1 "The accounts made for this setup are deleted (their NEAR goes
  back to the sponsor) and the phone key is removed.", later "The half-built
  wallet is left on testnet with its test NEAR. The fee account is deleted
  and the phone key removed."

What `finalize` sets in app wallets: threshold 2, `Delays.SHORTEST`
(transfer 3,600 s, config 3,600 s, recovery 86,400 s), and one limit for
`Asset.Near` from "Instant payment limit" / "Daily limit" (defaults 1 and 2 NEAR).

### Instant payment (within the limits)

```
 User            App (send)                            Card           Wallet contract
  | Approve, then  |                                     |                  |
  | tap card ----->| checks: NFC on, not frozen, spendable >= amount,       |
  |                |         receiver exists                                |
  |                | id = next_proposal_id                                  |
  |                | intent = H(intent tag, wallet, id, borsh(Transfer))    |
  | fingerprint -->| phone signs intent                  |                  |
  |                |-- [fee key] propose(Transfer, phone proof) ----------->| approvals = {phone},
  |                |                                     |                  | seal_challenge queued
  |                |                                     |                  | (runs a block later:
  |                |                                     |                  | seed = random_seed)
  |                |-- get_proposal every 0.7 s, up to 20 s --------------->|
  |                |<- card_challenge = H(card-approve tag, intent, seed) --|
  | tap card ----->|-- SELECT, GPO with UN = challenge ->|                  |
  |                |   (then INTERNAL AUTHENTICATE or    |                  |
  |                |   GENERATE AC, as at enrolment)     |                  |
  |                |<- SDAD + terminal data -------------|                  |
  |                | verify locally: key, layout, UN     |                  |
  |                |-- [fee key] approve_card + execute, one transaction -->| card check, ready_ts,
  |                |                                     |                  | limit check, transfer,
  |                |                                     |                  | on_transfer callback
  |<- "Sent 0.5 NEAR"                                    |                  |
```

- Checks before the fingerprint (`AppViewModel.send`), in order: the amount
  parses (a comma works) and is above zero; the receiver looks like a NEAR
  account id (2 to 64 characters); NFC exists and is on; `get_wallet` says
  not frozen, this phone is a signer and a card is enrolled; the receiver
  isn't the wallet itself; the spendable balance (`NearClient.spendable`:
  balance minus `storage_usage` × 10^19) covers the amount; the receiver
  exists (`view_account`), unless it is a 64-hex implicit account
  (`NearClient.accountExists` counts it as missing only when the RPC says it
  doesn't exist; a network error stops Send with that error instead).
- Prompt: "Send 0.5 NEAR to friend.testnet". Tap sheet: "Tap your card to
  approve sending 0.5 NEAR", then "Keep holding the card…" while reading.
- **Unfinished payments are reused.** If an open proposal sends the same
  NEAR amount to the same receiver, the phone approved it, the card hasn't,
  it isn't void and its card window has more than 30 s left, Send finishes it
  instead of proposing again (no second fingerprint).
- If the card step fails, the proposal stays: "Payment #N is saved and waits
  10 minutes for your card: send the same amount again, or approve or cancel
  it in Activity."
- "Instant" is decided on the phone with `LimitInfo.isInstant` (a port of
  `LimitState::is_instant`, using the phone's clock). The contract decides
  again at execution time.
- `approve_card` (100 Tgas) and `execute` (150 Tgas) go in **one
  transaction** from the fee account, so both run in one receipt: if
  `execute` fails, the card approval is undone too. When the contract
  refuses the execute part with "Delay has not elapsed", "Not enough
  approvals" or "Wallet is frozen" (it decides "instant" with its own clock
  and allowance), `AppViewModel.approveAndMaybeExecute` sends the same card
  signature again as a plain `approve_card`, and Send shows "Approved,
  waiting" ("The wallet didn't treat this as within your limits (the phone's
  clock may be off). Execute it from Activity when it's ready."). Any other
  failure is shown as an error.
- Contract side of `execute`: refuse a transfer while frozen, `gate` (setup
  over, same signer epoch, `policy::check_execution`), record the amount in
  the allowance, delete the proposal, send the NEAR as a promise, then
  `on_transfer` gives the allowance back if the transfer failed.

### Over-limit payment

```
 User            App                                   Card           Wallet contract
  | (as above until the card has signed)                 |                  |
  |                |-- [fee key] approve_card only ------------------------>| ready_ts = now
  |<- "Approved, waiting 1 h"                            |                  |
  |                                                                         |
  |   ... delays.transfer later (1 h in app wallets), counted from ready_ts |
  |                                                                         |
  | Activity >     |                                     |                  |
  | Execute ------>|-- [fee key] execute ---------------------------------->| not frozen, delay over,
  |                |                                     |                  | within 7 days: transfer
  |<- "Done"       |                                     |                  |
```

Nothing executes by itself. Until Execute, the phone can cancel it
(Activity → Cancel) and either signer can freeze the wallet. After
`EXECUTION_WINDOW` (7 days past the delay) the contract answers "Proposal
expired"; Activity then hides Execute and shows "Expired: it can no longer
execute; cancel it". Cancel removes it, and Clean up appears once
`close_stale` accepts it (after `ready_ts` + the longest delay + 7 days).

### Approving and managing proposals from Activity

```
 User            App                                   Card           Wallet contract
  | Approve · card>|-- challenge from get_proposal (or wait for the seal) ->|
  | tap card ----->|-- sign the challenge -------------->|                  |
  |                |   NEAR transfer, phone already approved, not frozen,   |
  |                |   within limits?                                       |
  |                |     yes: approve_card + execute ---------------------->| "Approved and sent"
  |                |          (execute refused: approve_card alone) ------->| "Approved"
  |                |     no:  approve_card -------------------------------->| "Approved"
  | Approve ·      |                                                        |
  | phone -------->| fingerprint "Approve: <description>"                   |
  |                |-- approve_p256(id, phone, sig over the view's intent) >|
  | Execute ------>|-- execute -------------------------------------------->| "Done"
  | Cancel ------->| fingerprint "Cancel: <description>"                    |
  |                |-- cancel(id, phone proof over cancel_message) -------->| proposal deleted
  | Clean up ----->|-- close_stale(id) ------------------------------------>| deleted if void, never
  |                |                                                        | approved within a day,
  |                |                                                        | or long expired
```

Which buttons show (`ActivityScreen` in `Screens.kt`):

| Button | Shown when |
|---|---|
| "Approve · card" | The card hasn't approved, its 10-minute window is open (or not sealed yet), not void |
| "Approve · phone" | The phone hasn't approved, not void |
| "Execute" | `secondsToExecute` is 0 (fully approved and the delay is over), the 7-day execution window hasn't passed (`expired`), and it isn't a transfer while the wallet is frozen |
| "Cancel" | Not void |
| "Clean up" | `close_stale` would accept it (`closable`): void, never fully approved and older than a day, or fully approved more than the longest delay + 7 days ago |

Statuses (`proposalStatus`): "Waiting for approvals", "Approved · can execute
in X h Y min", "Approved · ready: tap Execute", "Approved · wallet is frozen",
"Expired: it can no longer execute; cancel it", "Void: signers changed".

### Freeze

```
 User            App                                   Card           Wallet contract
  | snowflake +    |-- get_wallet: freeze_nonce --------------------------->|
  | "Freeze" ----->| challenge = H(card-freeze tag, wallet, nonce)          |
  | tap card ----->|-- sign the challenge -------------->|                  |
  |                |-- [fee key] freeze(card proof) ----------------------->| frozen = true,
  |<- "Wallet frozen"                                    |                  | nonce = new random
  |                                                                         |
  | Settings >     |-- get_wallet: freeze_nonce --------------------------->|
  | Freeze with    | fingerprint "FREEZE the wallet (blocks all transfers)" |
  | phone -------->|-- [fee key] freeze(phone proof over freeze_message) -->| frozen = true,
  |<- "Wallet frozen"                                                       | nonce = new random
```

The freeze challenge needs no seal: it is computed on the phone from the
current `freeze_nonce`, which becomes a fresh random value after every freeze
and unfreeze, so a freeze signature works once. A frozen wallet refuses new
transfer proposals and the execution of approved transfers; setting changes
still work.

### Unfreeze request

```
 User            App (requestUnfreeze)                 Card           Wallet contract
  | Request        |                                     |                  |
  | unfreeze ----->| fingerprint "Unfreeze the wallet (takes effect         |
  |                | after 1 h)"                                            |
  |                |-- [fee key] propose(Config[Unfreeze], phone proof) --->| allowed while frozen
  |                |-- wait for card_challenge ---------------------------->|
  | tap card ----->|-- sign the challenge -------------->|                  |
  |                |-- [fee key] approve_card ----------------------------->| ready_ts = now
  |<- "Unfreeze approved"                                |                  |
  |                                                                         |
  |   ... delays.config later (1 h in app wallets) ...                      |
  |                                                                         |
  | Activity >     |                                     |                  |
  | Execute ------>|-- [fee key] execute ---------------------------------->| frozen = false, new
  |<- "Done"       |                                     |                  | nonce, signer_epoch + 1
```

Before the prompt, `requestUnfreeze` checks NFC and looks for an unfreeze
request the phone approved and the card hasn't, not void, with more than
30 s of its card window left (or not sealed yet). If there is one, it skips
the prompt and the `propose` and goes straight to the card tap for that
proposal.

Executing any setting change bumps `signer_epoch`, which voids every other
open proposal ("Void: signers changed"; remove them with Clean up) and resets
the per-signer proposal counters.

### Card check (no chain involved)

```
 User            App (checkCard)                       Card           NEAR
  | Check a card ->| tap sheet "Hold the card you want to check"            |
  | tap card ----->|-- SELECT, GPO, READ RECORD -------->|                  |  nothing is
  |                |   recover the card key from its certificates           |  sent to NEAR
  |                |-- methods in turn until one signs ->|                  |
  |                |<- signature, or an error -----------|                  |
  |                | CardReport: network, AID, AIP, CA index, key sizes,    |
  |                | how it signs, every attempt, command trace             |
  | Share report ->| share sheet with CardReport.diagnostics()              |
```

`CardService.inspect` runs the same probe as enrolment (`CardService.enrol`)
with a random challenge and returns the report whether or not the card could
sign. The report and trace never contain the PAN, track data, cardholder name
or certificates; only the last four digits. Each attempt is a real card
transaction (the card's ATC goes up). Details of the probe: [CARDS.md](CARDS.md).

## Where state lives

| State | Where | Notes |
|---|---|---|
| The wallet's NEAR | Wallet account balance | About 3.35 NEAR (`storage_usage` × 10^19: the 334,258-byte code, the account and the wallet's state) is locked for storage; the app shows the rest as spendable |
| Signers, threshold, delays, limits (with `used` and `updated_at`), `frozen`, `freeze_nonce`, `signer_epoch`, `next_proposal_id`, per-signer proposal counters, open proposals (intent hash, approvals bitmap, `ready_ts`, seed, `sealed_ts`, action) | Contract state on the wallet account | The only authority. Read with `get_wallet`, `get_proposal`, `list_proposals` |
| Phone private key | Android Keystore, one key per wallet: alias `tapvault_phone_p256` for wallet 1, `tapvault_phone_p256_<id>` for the others (`WalletIndex.keyAlias`) | Can't be exported. Deleted by uninstalling, clearing the app's storage, or Forget / Start over of that wallet |
| Phone public key | On-chain signer 0 | The app reads it back from the Keystore certificate (`KeystoreSigner.publicKey`) |
| Card private key | The card's chip | Never leaves it |
| Card public key and terminal-data layout | On-chain signer 1; `Store.pendingCard` during setup | `PendingCard` holds the key, the enrolment proof, the network and the last four digits; no PAN |
| How each card signs (`CardRecipe`) | `Store.cards` (`CardBook`, keyed by card key hash) | AID, method, amount, variant, TTQ; reused first on every tap of that card |
| PAN, track data, cardholder name, certificates | Memory, during one card session (`EmvSession`) | Never stored, logged or sent |
| Wallet account id and temporary key | `Store.walletAccount`, `Store.walletKey` | The key is cleared after setup |
| Fee account id and key | `Store.gasAccount`, `Store.gasKey` | Kept for the wallet's life |
| Setup progress | `Store.setupStep` (0 to 4), `Store.setupPlan`, `Store.pendingCard` | `setupPlan` is cleared once the accounts exist |
| Limits typed on Welcome | `Store.instantNear`, `Store.dailyNear` | Saved exactly as typed (a comma becomes a dot); defaults "1" and "2" |
| Sponsor account and key | `BuildConfig.SPONSOR_ACCOUNT`, `BuildConfig.SPONSOR_KEY` | From `android/local.properties` at build time; empty means no sponsor |
| Contract code to deploy | APK asset `tapvault_contract.wasm` | Copied from `contract/res` by the build after the hash check |
| CA public keys | `android/core/src/main/resources/capk.txt` | Loaded by `CapkStore.load()` |
| Screen, busy label, result, tap request, last wallet view, balance, proposals, card report, offline banner, in-app log | `AppViewModel` (memory) | Lost when the process dies. The log keeps the last 200 lines (Settings → Advanced → Technical log shows 60) |

A phone can hold several wallets (`WalletIndex`, SharedPreferences file
`tapvault_wallets`: the ids, the active one, their names). Every row above
from `Store` exists once per wallet: wallet 1 uses the file `tapvault`, the
others `tapvault_w_<id>`. Wallets share nothing but the phone: each has its
own wallet account, fee account, card enrolment and phone key.

`Store` is a SharedPreferences file (`MODE_PRIVATE`), written
with `commit()` so a crash right after a step can't lose it. The manifest sets
`android:allowBackup="false"`, so it isn't copied to backups. The app writes
nothing to logcat; its log is the in-app list.

## The NEAR client

`NearClient` (`android/core/.../NearClient.kt`) is a small JSON-RPC client.
`TapvaultWallet` wraps the contract's methods and `Onboarding` the wallet
creation, both on top of it.

### Endpoints and fallback

- RPCs, tried in this order (`NearClient.TESTNET_RPCS`):
  `https://rpc.testnet.fastnear.com`, `https://test.rpc.fastnear.com`,
  `https://rpc.testnet.near.org`. The client remembers which one answered
  last (`preferred`) and starts there next time.
- Each call (`rpc`) makes up to two rounds over the three RPCs, with a
  backoff of 0.4 s doubling up to 3 s. It moves to the next RPC on network
  errors, HTTP 408/429/5xx, and the RPC error causes in `RETRYABLE_CAUSES`
  (TIMEOUT_ERROR, NO_SYNCED_BLOCKS, NOT_SYNCED_YET, INTERNAL_ERROR,
  UNKNOWN_BLOCK, UNAVAILABLE_SHARD). A real answer from the chain (bad
  transaction, unknown account, contract panic) is thrown at once.
- An HTTP 408 whose body is JSON is read as the JSON-RPC error inside it
  (TIMEOUT_ERROR) rather than as a transport failure.
- Faucet: `POST https://helper.nearprotocol.com/account` with
  `{newAccountId, newAccountPublicKey}`, then up to 30 s of polling until the
  account exists (`createTestnetAccount`; a network error while polling
  counts as "not yet").

### Sending a transaction (`NearClient.send`)

1. Nonce = max(the access key's nonce on chain, the last nonce this client
   used for that key) + 1. The client remembers the last nonce per
   account/key (`lastNonce`), so back-to-back transactions never collide even
   when an RPC's view lags. If the account doesn't have that key, the send
   stops with the RPC's error; RPCs that report it inside the result give
   "<account> has no access key <public key>: …" (cause
   `UNKNOWN_ACCESS_KEY`).
2. Block hash = the latest **final** block.
3. Sign the SHA-256 of the Borsh-encoded transaction with ed25519, then
   `send_tx` with `wait_until: EXECUTED_OPTIMISTIC`.
4. The same signed bytes may be re-sent to the next RPC; that is safe because
   NEAR runs a given transaction at most once.
5. If an RPC times out, or the request may have been delivered, the client
   **never sends a second transaction**: it looks the first one up by hash
   (`tx`, every 2 s for up to 2 minutes, `awaitOutcome`). If that runs out:
   "The network didn't confirm transaction <hash> in time. It may still go
   through: check Activity (or <explorer link>) before trying again."
6. It re-signs with a fresh nonce only when the chain rejected the
   transaction with InvalidNonce **and** a lookup by hash doesn't find it
   executed (at most three signatures). The nonce from the error
   (`ak_nonce`) is used for the next try. One gap: if an earlier RPC may have
   received the transaction but a later one answers with a definite error,
   `rpc` drops the "may have been delivered" flag, so an InvalidNonce from
   that later RPC can still lead to a re-sign while the first copy is
   pending. NEAR nodes normally recognise a resent transaction by its hash,
   so this is unlikely.
7. INVALID_TRANSACTION becomes "The network refused the transaction: …".
8. The outcome (`TxResult`) is a failure if any receipt failed.

### Views and readable errors

- Views use `query` / `call_function` at `optimistic` finality
  (`viewFunction`). A view that fails on chain (no contract, a panic) comes
  back inside the result; the client raises it as `NearRpcException` with
  cause `VIEW_ERROR` and the message "<method> on <account>: <error>".
- Contract panics are shown as the contract's own message with "Smart
  contract panicked: " removed (`readableError`), for example "Delay has not
  elapsed" or "Card approval window has closed; propose again". Messages are
  in `contract/src/error.rs`.
- Common account errors get plain text: "Not enough NEAR: the account must
  keep enough to pay for its storage", "Not enough NEAR in the account",
  "That account doesn't exist on NEAR testnet", "That account already
  exists", "This key can't sign for the account any more".

### Gas attached by the app

| Call | Attached | Signed by |
|---|---|---|
| `new` (with DeployContract) | 100 Tgas | Wallet account |
| `setup_add_signer`, `setup_finalize` | 100 Tgas each | Wallet account |
| `propose`, `approve_card`, `approve_p256`, `cancel`, `freeze`, `close_stale` | 100 Tgas each | Fee account |
| `execute` | 150 Tgas | Fee account |
| `approve_card` + `execute` in one transaction | 100 + 150 Tgas | Fee account |

Measured use is far lower (most expensive receipt 56.4 Tgas; see
[README.md](../README.md#measured-gas)). Unused gas is refunded.

### Timeouts

| What | Value | Where |
|---|---|---|
| HTTP connect | 15 s | `NearClient.post` |
| HTTP read, views and most calls | 60 s | `NearClient.rpc` |
| `send_tx` read | 90 s | `NearClient.sendSigned` |
| Transaction lookup by hash | 20 s per request; polled for 2 min | `txStatus`, `awaitOutcome` |
| Faucet request | 120 s, then 30 s waiting for the account | `createTestnetAccount` |
| Card challenge seal | polled every 0.7 s for 20 s | `TapvaultWallet.awaitCardChallenge` |
| Waiting for a card tap | 2 min per tap, up to 3 taps | `AppViewModel.withCard` |
| A card already lying on the phone counts as the tap | if it arrived in the last 30 s | `AppViewModel.awaitTag` |
| One APDU | 5 s | `IsoDepTransceiver` |
| Fingerprint prompt | up to 3 min for the app to be on screen, then 3 min for an answer | `KeystoreSigner.sign` |
| Wallet refresh | every 8 s on Home and Activity while the app is on screen | `AppViewModel.startAutoRefresh` |

There is no cancel button for network waits: with all three RPCs unreachable
a send can stay busy for several minutes before it reports.

## Error handling

**Contract.** Every rule is a `check(condition, ERR_…)`
(`contract/src/error.rs`). A failed check panics, which reverts every state
change of that receipt. Because `approve_card` and `execute` share a receipt
when sent together, a failing `execute` also reverts the approval (the app
then sends the approval again on its own when the refusal is about the
delay, the approvals or a freeze; see Instant payment).

**Kotlin core.**

| Exception | Raised for |
|---|---|
| `NearRpcException` (`rpcCause`, `maybeDelivered`) | Transport and RPC errors, view errors (`VIEW_ERROR`), refused transactions, a missing access key (`UNKNOWN_ACCESS_KEY`) |
| `TapvaultException` | A call that ran but failed (the contract's message), setup steps ("deploy failed: …", "adding the card failed: …", "finalize failed: …"), the faucet refusing with no sponsor ("The testnet faucet refused (…). Try again later or from another network.") |
| `EmvException`, `StatusWordException` | Card protocol errors, e.g. "card answered GPO with 6985 (conditions of use not satisfied)"; a card that signed only in a different terminal-data layout from the enrolled one ("the card signed, but not the way it did when it was added; tap it again") |
| `CardCheckFailed` | The card can't be a signer, e.g. "this card can't be a signer: its key is 2048 bits; the wallet accepts 512 to 1984"; carries the `CardReport` |
| `WrongCardException` | A signature in the enrolled terminal-data layout, over the challenge, that doesn't verify with the enrolled key: a different card ("this isn't the card enrolled in the wallet") |

**App** (`AppViewModel`).

- `run(label)` runs one operation at a time on a background thread, shows the
  busy overlay, and turns any exception into a result card: "That didn't
  work" plus the message. Results stay pinned at the top until dismissed with
  "OK", until the next operation starts, or until you change screens.
- Cheap checks run before any prompt, so a fingerprint is never wasted on a
  payment that would fail (see Instant payment).
- `withCard` handles NFC: it checks NFC exists and is on, waits up to
  2 minutes for a tap, and retries up to three taps when the card moves away
  ("The card moved away too soon. Tap again and keep it still until this
  screen closes."), the link drops, the tag goes stale, or a different card
  answers ("That card isn't one of this wallet's cards. Tap a card you added.").
  After three failed taps: "That card isn't one of this wallet's cards. Use a
  card you added to this wallet." if a different card answered any of them,
  otherwise "Couldn't read the card. Hold it flat against the back of the
  phone, near the camera, for 3 seconds."
- `KeystoreSigner.sign` waits until the app is on screen before prompting
  (a prompt shown in the background never appears) and turns failures into
  messages: "Approval cancelled: …", "Approval timed out", "Open Tapvault to
  approve with your fingerprint or screen lock.", and for an invalidated key
  "This phone's Tapvault key no longer works (the screen lock was removed).
  …". A missing key gives "This phone's Tapvault key is missing. On testnet,
  forget the wallet in Settings and create a new one." (also when reading
  `KeystoreSigner.publicKey`), and, outside Send, a phone key that isn't one
  of the wallet's signers "This phone's key isn't a signer of this wallet.
  Forget the wallet in Settings and set up again." (`AppViewModel.phoneIndex`,
  checked before any prompt or card tap).
- Setup is resumable (see Setup); payments are recoverable (the proposal
  stays open for the card, and Send reuses it).
- Refresh failures never interrupt: the last known state stays on screen,
  and Home shows the banner "Can't reach NEAR testnet right now. Showing the
  last known state; retrying."

Every user-visible message, what it means and what to do:
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Rust and Kotlin must agree

The phone signs bytes the contract recomputes, so these pairs must match
byte for byte:

| Rust | Kotlin | Pinned by |
|---|---|---|
| `contract/src/types.rs` (Borsh and JSON of `SignerKey`, `Asset`, `Action`, `ConfigOp`, `LimitInit`, `DelaysCfg`, `FinalizeArgs`, proofs) | `Model.kt`, `Borsh.kt` | `android/core/src/test/resources/vectors.json`, written by `contract/tests/vectors.rs` (`UPDATE_VECTORS=1`), checked by `VectorsTest` |
| `crates/tapvault-core/src/domain.rs` (every signed message) | `Domain.kt` | The same vectors |
| `emv::verify_sdad` | `Rsa.verifySdad` | Kept in step by hand; `EmvTest` covers the Kotlin side, the Rust tests the contract side |
| `policy::LimitState::is_instant` | `LimitInfo.isInstant` | By hand. The contract decides; on the phone it drives the UI and whether `execute` is sent together with `approve_card` (if the phone is wrong, the contract refuses the execute part, that combined transaction fails, and the app sends the card approval again on its own) |

## Build-time wiring

- `android/app/build.gradle.kts`: task `verifyWasm` checks that
  `contract/res/tapvault_contract.wasm` has SHA-256 `contractWasmSha256`
  (`778a310a…82c6`, base58 `93djY2LQU6NbNHJ93GmCDtKXecdrZhQPVCZTUcWt1Kxy`),
  then `copyWasm` copies it into `app/src/main/assets/` (gitignored) before
  `preBuild`. `Onboarding.deployAndInit` deploys those bytes.
- The same file reads `tapvault.sponsor.account` and `tapvault.sponsor.key`
  from `android/local.properties` into `BuildConfig`. Missing lines mean an
  APK without a sponsor; a key that doesn't parse is ignored when the app
  runs, which then uses the faucet only (`AppViewModel.sponsor`,
  `hasSponsor`).
- The release build type is signed with the debug key; `android/build-apk.sh`
  builds the debug APK with a JDK 17.

## Known gaps

The full list with impact is in [STATUS.md](STATUS.md). The ones that are
about how the system is built:

- Nothing executes by itself: over-limit payments and unfreezes need someone
  to tap Execute after the delay.
- No upgrade path: each wallet keeps the contract code it was deployed with.
- The app doesn't verify Android key attestation, so "hardware-backed and
  biometric-gated" is the app's word, not something the contract checks.
- The card signs the `card_challenge` from `get_proposal` without the phone
  being able to check it (the view has no seed). A dishonest RPC can turn a
  legitimate tap into a card signature over a challenge of its choosing.
  That alone can't move funds: transfers need the phone's approval, and the
  phone recomputes every intent it signs (the `approvePhone` half of this gap
  was closed on 13 Nov). Details and the planned fix (seed in the view,
  contract v0.2): [DESIGN.md](DESIGN.md), known gaps.
- On a phone that still holds a wallet made by an earlier build with a
  simulated card (`Store.legacyDemoWallet`), `withCard` refuses every card
  read, Card check included: "This wallet was set up with the old simulated
  demo card. Forget it in Settings and set up again with your bank card."
- The sponsor key ships inside APKs built with it (testnet only).
- One card plus one phone key: losing the phone key strands the wallet
  (recovery needs two other signers).
