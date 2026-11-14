# Contract reference

The Aegis wallet contract, method by method: who may call each method,
the exact JSON it takes, what it checks, what it changes, the events it
emits, the errors it raises and the gas it burns. Then the constants and
rules, every signed message, the card and P-256 signature checks, storage,
how a wallet is created, the shipped build, and how to change the contract
safely.

> **Status, 12 Nov 2026.** NEAR testnet only. The contract source passes the
> mocked-runtime flow tests and a sandbox run of the contract compiled from
> it; the shipped build (code hash
> `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`) passed live testnet runs.
> All of these used **simulated** cards. The contract has never checked a
> signature from a real bank card. NEP-141 token transfers and WebAuthn
> signers are implemented but no test runs them end to end. No professional
> audit. Evidence: [STATUS.md](STATUS.md).

Where things are:

- Contract code: `contract/src/` (`lib.rs`, `types.rs`, `auth.rs`,
  `setup.rs`, `proposals.rs`, `execute.rs`, `guard.rs`, `views.rs`,
  `events.rs`, `error.rs`).
- Security logic with no NEAR dependency, so it can be tested natively:
  `crates/tapvault-core/src/` (`emv.rs`, `policy.rs`, `domain.rs`,
  `bigint.rs`, `sha1.rs`, `sha256.rs`, `webauthn.rs`; `testcard.rs`, the
  simulated test card, is compiled only for tests and with the `test-card`
  feature).
- Reference client: `android/core/src/main/kotlin/app/tapvault/core/`
  (`Wallet.kt` for calls, `Model.kt` for argument types, `Domain.kt` for
  signed messages, `Borsh.kt`).
- Why the rules are what they are: [DESIGN.md](DESIGN.md). How the app gets
  a card to sign: [CARDS.md](CARDS.md). Components and flows:
  [ARCHITECTURE.md](ARCHITECTURE.md). What a user sees when a call fails:
  [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Contents

- [At a glance](#at-a-glance)
- [Calling conventions](#calling-conventions)
- [Argument types](#argument-types)
- [Setup methods](#setup-methods): `new`, `setup_add_signer`, `setup_finalize`
- [Proposal methods](#proposal-methods): `propose`, `seal_challenge`, `approve_p256`, `approve_card`
- [Execution methods](#execution-methods): `execute`, `on_transfer`
- [Guard methods](#guard-methods): `cancel`, `freeze`, `close_stale`
- [Views](#views)
- [Events](#events)
- [Errors](#errors)
- [Constants and rules](#constants-and-rules)
- [Signed messages](#signed-messages)
- [The card signature check](#the-card-signature-check)
- [The P-256 check](#the-p-256-check)
- [State and storage](#state-and-storage)
- [Creating a wallet](#creating-a-wallet)
- [The shipped build](#the-shipped-build)
- [Changing the contract safely](#changing-the-contract-safely)
- [Contract v0.4](#contract-v04): inheritance (`SetHeir`, `check_in`, `start_inheritance_claim`, `release_to_heir`)
- [Contract v0.3](#contract-v03)
- [Contract v0.2](#contract-v02): possession proofs, seed in the view, receiver allowlist, `UpgradeCode`

## Contract v0.4

14 Nov 2026 (afternoon): **inheritance.** The owner names an heir and an
inactivity period. If no key of the wallet signs anything for that long,
anyone may start a claim; if the owner still doesn't sign within a grace
period (the wallet's `config` delay), the funds can go only to the heir.
`contract_version` returns `"0.4.0"`. Only wallets created from this build
have it (existing wallets keep their code).

| Change | Where | Why |
|---|---|---|
| `ConfigOp::SetHeir { heir, inactivity }` (Borsh tag 10) and `ConfigOp::RemoveHeir` (tag 11), appended so earlier tags stay put. Ordinary config proposals: threshold, then `delays.config`; they bump `signer_epoch` like every config change. `inactivity` is 1 hour to 3650 days (`policy::MIN_INACTIVITY`, `MAX_INACTIVITY`, checked when proposed); the heir can't be the wallet itself (checked at execute). Both reset the timer and close any open claim | `types.rs`, `execute.rs` `apply_config`, `inherit.rs` `set_heir` | Naming or changing the heir needs the same approvals as any settings change |
| **Activity**: every signer signature the contract verifies sets `last_active = now` and voids an open claim: `propose`, `approve_p256`, `approve_card`, `cancel`, `freeze`, and the new `check_in`. Nothing is written on a wallet without an heir | `inherit::touch`, called from `proposals.rs`, `guard.rs` | Any use of the wallet shows the owner is alive |
| `check_in(proof: SignerProof)`: a phone signs `alive_message(account, alive_nonce)`, a card signs `card_alive_challenge(account, alive_nonce)`. The nonce goes up on every check-in, so a signature works once. Refused without an heir | `inherit.rs` | "I'm still here" without moving funds |
| `start_inheritance_claim()`, permissionless: needs an heir and `now > last_active + inactivity` (`policy::inheritance_claimable`). Sets `claim_ts` (a repeat keeps the first one) | `inherit.rs` | Anyone (the heir, a relayer) can start it; it only opens the grace period |
| `release_to_heir(asset, amount?)`, permissionless: needs an open claim with no activity since (`last_active < claim_ts`) and `now >= claim_ts + delays.config` (`policy::releasable`). NEAR: `amount` null sends everything above the storage reserve (storage in use + 2,000 bytes); a token needs an amount (`ft_transfer` to the heir, 1 yocto). Freeze and limits don't apply; proposals are untouched. Callback `on_release` logs a failed send | `inherit.rs` | Funds can go only to the heir the owner named |
| View `get_inheritance()`: `heir`, `inactivity`, `last_active`, `claim_ts`, `claimable_at`, `releasable_at` (null without an open claim), `alive_nonce`, and the current `alive_message` / `card_alive_challenge` (hex) for cross-checking | `inherit.rs` | Clients compute what they sign themselves and compare |
| State lives under its own storage key `b"heir"` (Borsh `Inheritance`), so the `Tapvault` layout is unchanged from v0.3 and `migrate` reads it as before | `inherit.rs` `read_inheritance`, `write_inheritance` | A v0.3 wallet that upgrades simply has no heir |

Events: `checked_in`, `inheritance_claim_started` (`heir`, `releasable_at`),
`inheritance_released` (`heir`, `asset`, `amount`),
`inheritance_release_failed`. Errors: `Invalid heir: …`, `This wallet has no
heir`, `The owner has been active within the inactivity period`, `No
inheritance claim is open (or the owner was active since it started)`, `The
claim's grace period has not elapsed`, `Nothing to release`.

What it means for the threat model (DESIGN.md, "Inheritance"): a stolen card
alone can keep a claim from ever completing (a card tap is activity) but
can't move funds; a compromised heir account gets nothing while the owner
signs anything at least once per period; whoever holds the heir account gets
everything once the owner is silent for the period plus the grace period, so
the period should be long (the app offers 30 days to 1 year; 1 hour on
testnet only). The watchtower alerts the owner's phone when the period is
nearly over and when a claim opens.

Tests: `flows.rs` 5 new (set heir and bounds, claim after the period then
the grace period, every kind of owner signature voids a claim, remove heir,
phone-only wallet), `policy::inheritance_rules`, `domain` tags,
`sandbox.rs` `inheritance_releases_to_the_heir_after_inactivity` (compiled
wasm: the heir received 96.27 of 100 NEAR, the wallet kept its storage),
Kotlin `InheritanceTest`, `VectorsTest` (`config_v04_ops`, `alive_3`), live
`InheritanceE2ETest`.

## Contract v0.3

14 Nov 2026: **phone-only wallets.** The app's "Create wallet" offers
"Without a card for now": setup skips the card and finalizes with
threshold 1. The first build shipped with v0.3 is also the first shipped
build with everything in v0.2 below. `contract_version` returns `"0.3.0"`
(`isV02` is true for it).

| Change | Where | Why |
|---|---|---|
| Threshold 1 is allowed only while the wallet has exactly one signer (`policy::threshold_is_valid`, `PHONE_ONLY_THRESHOLD`); `validate_config` already requires a P-256 signer, so that one signer is the phone, never a card. With two or more signers the minimum stays 2 | `lib.rs` `validate_config` (setup and every config change) | A wallet can start without a card and get one later |
| Adding a key to a threshold-1 wallet must raise the threshold in the same change: `Config { ops: [AddSigner(card), SetThreshold(2)] }`. `AddSigner` alone fails `validate_config` (`Invalid threshold`), so no card ever acts alone | No new op; existing `SetThreshold` | After the change it's an ordinary two-key wallet |
| A phone-only wallet's proposals are ready as soon as the phone proposes (its approval meets threshold 1). Transfers within the limits execute at once; others wait `delays.transfer`; adding the card waits `delays.config`, during which the phone can cancel | No code change: `update_ready`, `check_execution` | Same delays and limits as any wallet |

What a phone-only wallet gives up until its card is added: anyone holding
the phone's key (an unlocked phone and the fingerprint or screen lock) can
pay within the limits; recovery on a new phone needs cards, so a lost phone
means a lost wallet; a guardian can't be added (it would be a second signer
on threshold 1). The app says so on the choice sheet and keeps an "Add your
card" banner on Home until the card is added.

Tests: `flows.rs` 3 new (threshold 1 only with one signer, the phone alone
pays, the first card must come with `SetThreshold(2)` and then the phone
alone is refused), `policy::threshold_rules`, `sandbox.rs`
`phone_only_wallet_then_add_the_card` (compiled wasm), Kotlin `Phase5Test`,
live `PhoneOnlyE2ETest`.

## Contract v0.2

Source changes of 13 Nov 2026 (hardware-wallet plan phase 4). Only wallets
created from a build that ships it get it; every existing wallet keeps the
code it was deployed with (AGENTS.md invariant 4). The reference sections
below describe v0.1; this section lists what v0.2 adds or changes. The
`contract_version` view returns `"0.2.0"`; v0.1 has no such view, which is
how the app tells them apart (`TapvaultWallet.isV02`).

| Change | Methods and types | Why |
|---|---|---|
| A key added by a settings change must prove possession before the change executes | `propose(action, proof, possession: Option<Vec<OpPossession>>)`; new `prove_possession(id: U64, op: u8, proof: PossessionProof)` (anyone may send it); `execute` refuses with `A key this proposal adds hasn't proved possession (prove_possession)` | DESIGN gap 8: a key nobody holds can't be added by a client of one's own |
| Possession messages | P-256: `possession_message(account, new_key_hash)` = SHA-256(`tapvault/near/v1/possess` ‖ len(account) ‖ account ‖ key hash); card: `card_possession_challenge`, tag `tapvault/near/v1/card-possess`. No proposal id, so a guardian or a backup card signs it ahead | Same shape as the setup messages |
| The sealed seed is in the proposal view | `ProposalView.card_seed` (hex, null before the seal), `ProposalView.possessed` (bit i ⇔ op i proved) | DESIGN gap 6a: the phone recomputes `card_approve_challenge(intent, seed)` from the intent it computed and refuses a view that differs (`TapvaultWallet.checkedChallenge`) |
| Receiver allowlist | `ConfigOp::AllowReceiver { receiver }`, `ConfigOp::DisallowReceiver { receiver }` (Borsh tags 7, 8), `WalletView.allowlist`, at most 16 (`MAX_ALLOWLIST`). Once the list has an entry, only listed receivers can be paid instantly; any other transfer waits `delays.transfer` even within the limits | A thief with phone, card and fingerprint can't pay a new receiver at once |
| Code upgrade | `ConfigOp::UpgradeCode { code_hash }` (tag 9; must be the only op; 32-byte SHA-256 of the new wasm). `execute` refuses it; `execute_upgrade(id: u64, code: Vec<u8>)` with **Borsh** arguments is refused while the wallet is frozen (`Wallet is frozen`; unfreezing is a config change, so it also voids the upgrade request), checks the hash, runs the gate with `ActionClass::Upgrade` (always the recovery delay, ≥ 24 h, whoever approved), bumps `signer_epoch`, then deploys the code and calls `migrate` in one receipt (if `migrate` fails, the old code stays) | DESIGN gap 12: wallets can receive fixes, behind the longest delay the owner can cancel in |
| `migrate` | `#[private] #[init(ignore_state)] migrate()`: reads the state as the current layout. A later version with another layout converts it here | Needed by `execute_upgrade` |
| State | `Proposal.possessed: u8`, `Tapvault.allowlist: Vec<AccountId>` | A v0.1 state can't be read by v0.2, which is fine: v0.1 wallets have no upgrade path |

New errors: `ERR_NO_POSSESSION`, `ERR_NOT_ADD_SIGNER` ("That op doesn't add a
signer"), `ERR_UPGRADE_ALONE`, `ERR_USE_EXECUTE_UPGRADE`, `ERR_NOT_UPGRADE`,
`ERR_CODE_HASH` ("Code doesn't match the approved code hash"),
`ERR_ALLOWLIST`, `ERR_RECEIVER_NOT_FOUND` (`contract/src/error.rs`).

Tests: `contract/tests/flows.rs` (29, five for v0.2), `policy` unit tests,
`contract/tests/sandbox.rs` (possession in `propose`, seed in view, an
upgrade refused early, a wrong code refused, the upgrade to the same code
after a day keeping the state), `vectors.json` (`config_v02_ops`,
`config_upgrade`, `possess_card`, `card_possess_card`).

## At a glance

- **One contract instance is one wallet.** It is deployed to the wallet's
  own account (`tv-<10 hex>.testnet`, or `tv-<10 hex>.<sponsor>`), which
  holds the NEAR and any NEP-141 tokens.
- **Signers.** Up to 8, each at an index (its position in the list): `P256`
  keys (the phone; a FIDO2 key or passkey when `webauthn` is set) and
  `EmvCard` keys (a bank card's ICC public key plus where the challenge sits
  in the data the card signs). At least one must be P-256. Wallets made by
  the app have the phone at index 0 and one card at index 1, threshold 2.
- **All authority comes from signatures** over domain-separated messages.
  Who sends a transaction doesn't matter, with two exceptions: `new` must
  come from the wallet account itself, and `setup_finalize` must be signed
  by the wallet account's key, which it then deletes. (The callbacks
  `seal_challenge` and `on_transfer` are private: only the contract itself
  can call them.) After setup the app sends every call from its fee
  account, which can't authorise anything.
- **No upgrade.** There is no method that replaces the code, and after
  `setup_finalize` an app-created wallet account has no access keys (the
  contract deletes only the key that signed `setup_finalize`). A wallet runs
  the code it was created with for good.

```
new --> setup_add_signer (1..7 times) --> setup_finalize   [account key deleted]
                                                |
            +-----------------------------------+
            v
   propose --> seal_challenge --> approve_p256 / approve_card --> execute --> on_transfer
     (any signer)  (contract, a later block)   (other signers)     (anyone)   (transfers)

   at any time: cancel (a signer) . freeze (a signer) . close_stale (anyone, dead proposals)
```

| Method | Kind | Who may send it | Authorised by | Kotlin client | Gas (sandbox) |
|---|---|---|---|---|---|
| `new` | init | the wallet account only | first P-256 signer signs the create message | `Onboarding.deployAndInit` | 56.4 Tgas together with the deploy |
| `setup_add_signer` | call, setup only | anyone | P-256 authorizer + new signer's proof of possession | `Onboarding.addCard` | 34.7 (1024-bit card), 53.4 (1984-bit card) |
| `setup_finalize` | call, setup only | a transaction signed by the wallet account | P-256 authorizer signs the finalize message | `Onboarding.finalize` | 35.1 |
| `propose` | call | anyone | a signer's proof | `TapvaultWallet.proposeByPhone`, `proposeByCard` | 34.8 by phone, + 2.0 for the seal |
| `seal_challenge` | private callback | the contract itself | none | scheduled by `propose` | 2.0 |
| `approve_p256` | call | anyone | P-256 signature over the intent | `approvePhone` | not measured (P-256 check ~32) |
| `approve_card` | call | anyone | card signature over the sealed challenge | `approveCard`, `approveCardAndExecute` | 2.7 (1024-bit, e = 3) to 21.3 (1984-bit, e = 65537) |
| `execute` | call | anyone | approvals and policy | `execute`, `approveCardAndExecute` | 2.7 + 1.6 callback (NEAR transfer) |
| `on_transfer` | private callback | the contract itself | none | scheduled by `execute` | 1.6 |
| `cancel` | call | anyone | a signer's proof | `cancelByPhone` | not measured |
| `freeze` | call | anyone | a signer's proof | `freezeByPhone`, `freezeByCard` | 2.4 by card |
| `close_stale` | call | anyone | nothing: only dead proposals | `closeStale` | not measured |
| `get_wallet`, `get_proposal`, `list_proposals`, `intent_for`, `freeze_challenge`, `cancel_challenge` | view | anyone | none | `state`, `proposal`, `proposals` | free |

Gas figures are burnt gas measured on a local sandbox node (nearcore
2.6.5, 12 Nov 2026) by `contract/tests/sandbox.rs`. NEAR's limit is 300 Tgas
per receipt; the most expensive receipt is 56.4 Tgas. The P-256 check is
about 32 Tgas of every call that verifies a phone signature. The v0.2 source (13 Nov run) is within ~1 Tgas
of these for the same steps (deploy + `new` 57.5 Tgas); its new calls:
`propose` of an `AddSigner` with a P-256 possession proof 68.1 Tgas (two
P-256 checks, now the most expensive receipt), `execute_upgrade` deploy +
`migrate` 24.5 Tgas.

## Calling conventions

- **JSON** for arguments and return values (near-sdk 5.14 with serde).
- **Bytes** (`Base64VecU8`) are standard Base64 with padding (what Java's
  `Base64.getEncoder()` produces), not base64url and not hex.
- **`U64` and `U128` are decimal strings**: `"id": "7"`,
  `"amount": "500000000000000000000000"`. Plain `u8`, `u16`, `u32` and `i64`
  are JSON numbers: signer indices, `exponent`, timestamps, delays.
- **Enums are externally tagged** (serde's default). A unit variant is a
  string (`"Near"`, `"Unfreeze"`). A struct variant is
  `{"Variant": {fields}}`. A newtype variant is `{"Variant": value}`:
  `{"SetThreshold": 3}`, `{"AddSigner": {"P256": {...}}}`,
  `{"Card": {"sdad": ..., "terminal_data": ...}}`.
- **`Option`** is `null` or the value. The Kotlin client always sends
  `"webauthn": null` for phone keys and phone signatures.
- **Views return hashes and challenges as lowercase hex** (64 characters),
  while arguments take bytes as Base64. Mind the one place where both meet:
  `ConfigOp::RemoveSigner { key_hash }` takes Base64, but `get_wallet` shows
  `key_hash` in hex.
- **Times** are block time in whole Unix seconds.
- **Amounts** are integers: yoctoNEAR for `Near` (1 NEAR = 10^24), the
  token's base units for `Ft`.
- **No method is payable.** Attaching a deposit to any change method fails
  with `Method <name> doesn't accept deposit` (near-sdk). Views have no such
  check.
- **Malformed arguments**, including an invalid account id, fail with
  `Failed to deserialize input from JSON.`; a call with no arguments at all
  to a method that takes some fails with `Expected input since method has
  arguments.` (both near-sdk).
- **Before `new`**, every call fails with `The contract is not initialized`.
- **A failed check panics** with a message from `contract/src/error.rs`, and
  the whole receipt reverts. All actions of one transaction run in one
  receipt, so `approve_card` + `execute` in one transaction succeed or fail
  together. The RPC reports
  `{"ActionError":{"index":N,"kind":{"FunctionCallError":{"ExecutionError":"Smart contract panicked: <message>"}}}}`;
  `NearClient.readableError` strips the prefix, so the app shows the
  message from [Errors](#errors).
- **Signatures in arguments are public** once a transaction is broadcast.
  Anyone can resubmit them. That can't change what is approved (the
  signature covers it), but it can make the original transaction fail
  (`Signer already approved`; for `propose`, `P-256 signature invalid`
  because the proposal id moved on). The proposal or approval still exists.
  After a resubmitted `propose`, sending the same payment again in the app
  finds the proposal and finishes it; after a resubmitted `approve_card`,
  the payment is fully approved and Activity shows Execute once it can run
  (sending it again would create a second payment).

## Argument types

Defined in `contract/src/types.rs` (plus `Possession` in `setup.rs`) and
mirrored in `Model.kt`. The Borsh form matters because it is what gets
hashed into signed messages.

### `SignerKey`

```json
{"P256": {"pubkey": "<Base64, 33 bytes>", "webauthn": null}}
{"P256": {"pubkey": "<Base64, 33 bytes>", "webauthn": {"rp_id_hash": "<Base64, 32 bytes>", "require_uv": true}}}
{"EmvCard": {"modulus": "<Base64, 64 to 248 bytes>", "exponent": 3, "un_offset": 0, "un_len": 4, "terminal_data_len": 19}}
```

- `P256.pubkey`: SEC1 compressed point, 33 bytes, first byte `02` or `03`,
  on the curve. With `webauthn`, the key is a FIDO2 key or passkey:
  `rp_id_hash` (SHA-256 of the relying-party id) must be 32 bytes and
  `require_uv` must be `true`, because a P-256 signer counts as a
  deliberate, user-verified approval.
- `EmvCard.modulus`: the card's ICC RSA modulus, big-endian, 64 to 248
  bytes (512 to 1984 bits), no leading zero byte, odd. `exponent`: 3 or
  65537. `un_offset`, `un_len`, `terminal_data_len`: where the challenge
  bytes sit in the terminal data the card signs, how many there are (4 to
  8), and the exact length of that terminal data (at most 64 bytes;
  `un_offset + un_len <= terminal_data_len`). See
  [The card signature check](#the-card-signature-check).
- Checked by `SignerKey::validate` in `new`, `setup_add_signer`, `propose`
  (for `AddSigner` ops) and every config validation.
- A signer's identity is its key hash (`key_hash_p256`, `key_hash_emv`; see
  [Signed messages](#signed-messages)). The card layout is not part of it.
- `is_deliberate()` is true for every `P256` key and false for every card.

### `P256Sig`, `WebAuthnPayload`

```json
{"signature": "<Base64, 64 bytes: r || s>", "webauthn": null}
{"signature": "<Base64, 64 bytes>", "webauthn": {"authenticator_data": "<Base64>", "client_data_json": "<Base64>", "challenge_index": 23, "type_index": 1}}
```

`signature` is the raw 64-byte `r || s` form, not DER (`P256.derToRaw`
converts the Keystore's DER output). `webauthn` is required for a WebAuthn
signer and ignored otherwise; the indices are byte offsets into
`client_data_json` (see [The P-256 check](#the-p-256-check)).

### `CardSig`

```json
{"sdad": "<Base64, exactly the modulus length>", "terminal_data": "<Base64, exactly terminal_data_len bytes>"}
```

`sdad` is the card's Signed Dynamic Application Data (EMV tag `9F4B`, or
tag `80` in a format-1 INTERNAL AUTHENTICATE answer). `terminal_data` is
the terminal dynamic data the card hashed, with the challenge's first
`un_len` bytes at `un_offset`.

### `SignerProof`

```json
{"P256": {"signer_index": 0, "sig": {"signature": "<Base64>", "webauthn": null}}}
{"Card": {"signer_index": 1, "sig": {"sdad": "<Base64>", "terminal_data": "<Base64>"}}}
```

Used by `propose`, `cancel` and `freeze`. The variant must match the kind of
signer at `signer_index`, or the call fails with
`Signer is the wrong kind for this operation`.

### `Possession` (setup only)

```json
{"P256": {"signature": "<Base64>", "webauthn": null}}
{"Card": {"sdad": "<Base64>", "terminal_data": "<Base64>"}}
```

### `Asset`, `Action`, `ConfigOp`

```json
"Near"
{"Ft": {"token": "usdc.fakes.testnet"}}

{"Transfer": {"asset": "Near", "amount": "500000000000000000000000", "receiver": "friend.testnet"}}
{"Config": {"ops": ["Unfreeze"]}}
```

| `ConfigOp` | JSON | Effect when executed |
|---|---|---|
| `AddSigner(SignerKey)` | `{"AddSigner": {"EmvCard": {...}}}` | Appends a signer (no proof of possession; see [DESIGN.md](DESIGN.md) section 7) |
| `RemoveSigner { key_hash }` | `{"RemoveSigner": {"key_hash": "<Base64, 32 bytes>"}}` | Removes the signer with that key hash; later signers move down one index |
| `SetThreshold(u8)` | `{"SetThreshold": 3}` | Sets the threshold |
| `SetLimit(LimitInit)` | `{"SetLimit": {"asset": "Near", "instant_max": "1000000000000000000000000", "daily_max": "2000000000000000000000000"}}` | Changes an asset's maxima (keeps its `used` and `updated_at`), or adds a limit for a new asset |
| `RemoveLimit { asset }` | `{"RemoveLimit": {"asset": "Near"}}` | Removes the limit: every transfer of that asset is then delayed |
| `SetDelays(DelaysCfg)` | `{"SetDelays": {"transfer": 3600, "config": 7200, "recovery": 86400}}` | Sets the three delays (seconds) |
| `Unfreeze` | `"Unfreeze"` | Unfreezes and picks a new random freeze nonce |

`Action::validate` (run by `propose`): a transfer's amount must be more
than 0; a config action must have 1 to 4 ops (`MAX_CONFIG_OPS`); every
`AddSigner` key must be valid. Everything else (threshold range, delay
bounds, duplicate signers, removing a signer that doesn't exist) is only
checked when the proposal executes.

### `LimitInit`, `DelaysCfg`, `FinalizeArgs`

```json
{"threshold": 2,
 "delays": {"transfer": 3600, "config": 3600, "recovery": 86400},
 "limits": [{"asset": "Near", "instant_max": "1000000000000000000000000", "daily_max": "2000000000000000000000000"}]}
```

### Borsh encoding (what gets hashed)

| Type | Borsh |
|---|---|
| string, bytes | u32 little-endian length, then the bytes (UTF-8 for strings) |
| `Option<T>` | `00`, or `01` then `T` |
| `Asset` | `Near` = `00`; `Ft` = `01` + string `token` |
| `SignerKey` | `P256` = `00` + bytes `pubkey` + option(bytes `rp_id_hash` + bool `require_uv`); `EmvCard` = `01` + bytes `modulus` + u32 LE `exponent` + u8 `un_offset` + u8 `un_len` + u8 `terminal_data_len` |
| `DelaysCfg` | i64 LE `transfer`, `config`, `recovery` |
| `LimitInit` | `Asset` + u128 LE `instant_max` + u128 LE `daily_max` |
| `ConfigOp` | u8 variant index (0 `AddSigner`, 1 `RemoveSigner`, 2 `SetThreshold`, 3 `SetLimit`, 4 `RemoveLimit`, 5 `SetDelays`, 6 `Unfreeze`) + the payload |
| `Action` | `Transfer` = `00` + `Asset` + u128 LE `amount` + string `receiver`; `Config` = `01` + u32 LE count + each `ConfigOp` |
| `FinalizeArgs` | u8 `threshold` + `DelaysCfg` + u32 LE count + each `LimitInit` |

Example from `android/core/src/test/resources/vectors.json`: sending
1.5 NEAR to `bob.testnet` is
`0000000080716433b629a33d0100000000000b000000626f622e746573746e6574`
(`00` Transfer, `00` Near, the amount as 16 little-endian bytes, then the
11-byte receiver).

## Setup methods

During setup (`setup_mode == true`) the wallet's own full-access key still
exists, so whoever holds it controls the account. The protections start
when `setup_finalize` deletes that key. `propose`, the approvals and
`freeze` refuse to run during setup (`Wallet is still in setup`); with no
proposals, `execute`, `cancel` and `close_stale` have nothing to act on
(`Proposal not found`).

### `new`

```rust
#[init] pub fn new(first_signer: SignerKey, sig: P256Sig) -> Self
```

- **Who may send:** only the wallet account itself
  (`predecessor_account_id == current_account_id`). This is checked by hand
  because near-sdk ignores `#[private]` on `#[init]` methods. In practice
  the call rides in the same transaction as the deploy (see
  [Creating a wallet](#creating-a-wallet)), so nobody can initialise
  someone else's freshly deployed wallet.
- **Arguments** (as `Onboarding.deployAndInit` sends them, 100 Tgas):

  ```json
  {"first_signer": {"P256": {"pubkey": "AxgQ7IEc0KZlh34ybRFjrHhbM3THSK85rsNCUhR/jrcj", "webauthn": null}},
   "sig": {"signature": "<Base64, 64 bytes>", "webauthn": null}}
  ```

- **Checks, in order:** no state yet (near-sdk: `The contract has already
  been initialized`); sender is the wallet account (`Only the wallet account
  itself can initialise it`); `first_signer` is P-256 (`Signer is the wrong
  kind for this operation`); the key is valid (`Invalid signer key`); `sig`
  verifies over `create_message(account, key_hash(first_signer))`
  (`P-256 signature invalid`, or a WebAuthn error).
- **State:** `setup_mode = true`, not frozen, `threshold = 0`,
  `signer_epoch = 0`, `freeze_nonce = 0`, `next_proposal_id = 0`, delays
  `Delays::DEFAULT` (86,400 / 86,400 / 259,200 s; replaced at finalize),
  `signers = [first_signer]`, no limits, no proposals.
- **Events:** none.
- **Gas:** 56.4 Tgas for the whole deploy + `new` receipt.

### `setup_add_signer`

```rust
pub fn setup_add_signer(&mut self, new_signer: SignerKey, authorizer_index: u8,
                        authorizer_sig: P256Sig, possession: Possession)
```

- **Who may send:** anyone; the authority is the authorizer's signature. The
  app sends it from the wallet account (`Onboarding.addCard`, 100 Tgas).
- **Arguments** (adding a Visa-style card):

  ```json
  {"new_signer": {"EmvCard": {"modulus": "<Base64>", "exponent": 3, "un_offset": 0, "un_len": 4, "terminal_data_len": 19}},
   "authorizer_index": 0,
   "authorizer_sig": {"signature": "<Base64, 64 bytes>", "webauthn": null},
   "possession": {"Card": {"sdad": "<Base64>", "terminal_data": "<Base64, 19 bytes>"}}}
  ```

- **Checks, in order:**
  1. In setup (`Wallet setup is already finalized`).
  2. Fewer than 8 signers (`Too many signers`).
  3. `new_signer` valid (`Invalid signer key`) and not already enrolled
     (`Signer already enrolled`).
  4. `count` = the number of signers before this add. The authorizer
     (`authorizer_index`, `Signer index out of range`) must be P-256
     (`Signer is the wrong kind for this operation`) and must have signed
     `setup_add_message(account, count, key_hash(new_signer))`.
  5. Proof of possession: a new P-256 signer signs the same setup message
     (`Possession::P256`); a new card signs
     `card_enroll_challenge(account, count, key_hash(new_signer))` with the
     layout it declares (`Possession::Card`, `Card signature verification
     failed`). A possession variant that doesn't match the new signer's kind
     fails with `Signer is the wrong kind for this operation`.
- **State:** the new signer is appended at index `count`.
- **Events:** none.
- **Single use:** `count` is in both signed messages, so an authorisation
  can't add a second signer. A failed call can be retried with the same
  signatures (the app keeps the card's proof in `Store.pendingCard`).
- **Gas:** 34.7 Tgas with a 1024-bit e = 3 card, 53.4 Tgas with a 1984-bit
  e = 65537 card (P-256 check plus the card check).

### `setup_finalize`

```rust
pub fn setup_finalize(&mut self, args: FinalizeArgs, authorizer_index: u8, sig: P256Sig)
```

- **Who may send:** a transaction **signed by the wallet account**
  (`env::signer_account_id() == current_account_id`). The contract deletes
  the access key that signed it (`env::signer_account_pk()`), so this check
  is what makes "finalize" also mean "lock".
- **Arguments** (`Onboarding.finalize`, 100 Tgas; the app always uses
  threshold 2, `Delays.SHORTEST` and one NEAR limit from the "Set your
  spending limits" screen):

  ```json
  {"args": {"threshold": 2,
            "delays": {"transfer": 3600, "config": 3600, "recovery": 86400},
            "limits": [{"asset": "Near", "instant_max": "1000000000000000000000000", "daily_max": "2000000000000000000000000"}]},
   "authorizer_index": 0,
   "sig": {"signature": "<Base64, 64 bytes>", "webauthn": null}}
  ```

- **Checks, in order:** in setup (`Wallet setup is already finalized`);
  signed by the wallet account (`setup_finalize must be sent by the wallet
  account itself, so its key is deleted`); the authorizer is P-256 and
  signed `finalize_message(account, borsh(args))`. Then the new
  configuration is applied and `validate_config` runs:
  - at most 8 signers (`Too many signers`);
  - `2 <= threshold <= number of signers` (`Invalid threshold`), so at least
    two signers must be enrolled first; v0.3: or threshold 1 with exactly
    one signer, the phone (a phone-only wallet, [Contract v0.3](#contract-v03));
  - at least one P-256 signer (`At least one P-256 (phone / security key)
    signer is required`);
  - every key valid (`Invalid signer key`) and unique (`Signer already
    enrolled`);
  - delays within bounds (`Invalid delays`);
  - at most 8 limits, `instant_max <= daily_max`, one limit per asset
    (`Invalid limits`).
- **State:** threshold, delays and limits set (each limit starts with
  `used = 0`, `updated_at = 0`); `setup_mode = false`. A `DeleteKey`
  receipt for the signing key is scheduled on the wallet account.
- **Events:** `setup_finalized` `{"threshold": 2}`.
- **After it:** check that the account has no access keys left
  ([Verify a wallet](#verify-a-wallet)). The contract deletes only the key
  that signed this transaction; any other full-access key on the account
  would still control everything. The app checks this right away and logs
  `WARNING: wallet still has keys: ...` if any remain.
- **Gas:** 35.1 Tgas.

## Proposal methods

### `propose`

```rust
pub fn propose(&mut self, action: Action, proof: SignerProof) -> U64
```

- **Who may send:** anyone (the app: its fee account, 100 Tgas). The
  proposer is whoever `proof` proves.
- **Arguments** (`TapvaultWallet.proposeByPhone`):

  ```json
  {"action": {"Transfer": {"asset": "Near", "amount": "500000000000000000000000", "receiver": "friend.testnet"}},
   "proof": {"P256": {"signer_index": 0, "sig": {"signature": "<Base64, 64 bytes>", "webauthn": null}}}}
  ```

  Request unfreeze uses `{"Config": {"ops": ["Unfreeze"]}}` as the action.
  A card proposer (`proposeByCard`; the app doesn't use it) sends
  `{"Card": {"signer_index": 1, "sig": {"sdad": ..., "terminal_data": ...}}}`.
- **Checks, in order:**
  1. Not in setup (`Wallet is still in setup`).
  2. `Action::validate` (`Invalid action`, `Invalid signer key`).
  3. A transfer can't be proposed while frozen (`Wallet is frozen`). Config
     proposals can, which is how a frozen wallet gets unfrozen.
  4. `id = next_proposal_id`;
     `intent = intent_hash(account, id, borsh(action))`.
  5. The proof: a P-256 signer must have signed `intent`, and that counts
     as its approval. A card must have signed
     `card_propose_challenge(intent)`; that proves possession (it stops
     anonymous spam) but is **not** an approval, because it can be computed
     before the proposal exists. Errors: `Signer index out of range`,
     `Signer is the wrong kind for this operation`, `P-256 signature
     invalid`, `Card signature verification failed`.
  6. Open-proposal cap: the proposer may have fewer than 4 live proposals
     (`This signer has too many open proposals; cancel or wait for some`).
     See [Rate limit and open-proposal cap](#rate-limit-and-open-proposal-cap).
  7. Rate limit: at most 24 per signer per window (`Proposal rate limit
     reached for today`).
- **State:** inserts the proposal (`created_ts`, `created_height`, current
  `signer_epoch`, `proposer`, the approval bitmap, no seed yet) and
  increments `next_proposal_id`. Schedules `seal_challenge(id)` on the
  wallet itself with 5 Tgas of static gas (`SEAL_GAS`).
- **Events:** `proposal_created` `{"id": "4", "intent_hash": "<hex>"}`. The
  proposer's own approval does not emit `approved`.
- **Returns:** the proposal id as a JSON string, e.g. `"4"`.
- **Race:** the intent includes the id. The client reads `next_proposal_id`
  (`get_wallet`), signs for it, and the call fails with a signature error if
  another proposal lands first; sign again for the new id.
- **Gas:** 34.8 Tgas by phone (about 32 of it the P-256 check), plus 2.0
  Tgas for the `seal_challenge` receipt.

### `seal_challenge`

```rust
#[private] pub fn seal_challenge(&mut self, id: U64)
```

- **Who may send:** only the contract itself (near-sdk: `Method
  seal_challenge is private`). Since the account has no keys after setup,
  only the receipt `propose` schedules can call it.
- **Arguments** (sent by the contract): `{"id": "4"}`.
- **What it does:** if proposal `id` still exists and has no seed, stores
  `seed = env::random_seed_array()` and `sealed_ts = now`. Otherwise it
  does nothing (a proposal gets exactly one seed, never overwritten).
- **Why a separate receipt:** a receipt created during execution runs in a
  later block, so the block randomness it captures didn't exist when the
  action was committed. No card signature collected earlier can have
  targeted it. Details: [DESIGN.md](DESIGN.md) section 2.
- **Events:** `challenge_sealed` `{"card_challenge": "<hex>", "id": "4"}`,
  where `card_challenge = card_approve_challenge(intent, seed)`.
- **Timing:** about one block (~1 s) after `propose`. The app waits for
  `send_tx` with `EXECUTED_OPTIMISTIC` (all receipts done) and then polls
  `get_proposal` every 700 ms for up to 20 s
  (`TapvaultWallet.awaitCardChallenge`).
- **Gas:** 2.0 Tgas.

### `approve_p256`

```rust
pub fn approve_p256(&mut self, id: U64, signer_index: u8, sig: P256Sig)
```

- **Who may send:** anyone (the app: `approvePhone`, 100 Tgas).
- **Arguments:**
  `{"id": "4", "signer_index": 0, "sig": {"signature": "<Base64>", "webauthn": null}}`.
- **Checks:** not in setup; the proposal exists (`Proposal not found`); it
  is from the current signer epoch (`Signer set changed since this proposal
  was created`); the index exists; that signer hasn't approved yet (`Signer
  already approved`); the signer is P-256 and signed the proposal's intent
  hash. No time limit and no frozen check: approvals are allowed while
  frozen; executing a transfer isn't.
- **State:** sets the signer's bit. If the proposal now has every approval
  it needs, `ready_ts = now` (once).
- **Events:** `approved` `{"id": "4", "signer_index": 0}`, then `ready`
  `{"id": "4"}` if it became ready.
- **Client note:** `TapvaultWallet.approvePhone` doesn't sign the
  `intent_hash` that `get_proposal` returns as is: it recomputes it from the
  view's action (`ProposalInfo.verifiedAction`) and refuses a mismatch. Any
  client that doesn't trust its RPC should do the same. See
  [DESIGN.md](DESIGN.md) section 7.
- **Gas:** not measured; dominated by the ~32 Tgas P-256 check.

### `approve_card`

```rust
pub fn approve_card(&mut self, id: U64, signer_index: u8, sig: CardSig)
```

- **Who may send:** anyone. The app sends it alone (`approveCard`, 100
  Tgas) or, when the payment can execute at once, in one transaction with
  `execute` (`approveCardAndExecute`, 100 + 150 Tgas):

  ```
  one transaction, signer = fee account, receiver = wallet, two FunctionCall actions:
    approve_card  {"id": "4", "signer_index": 1, "sig": {"sdad": "<Base64>", "terminal_data": "<Base64>"}}  100 Tgas
    execute       {"id": "4"}                                                                           150 Tgas
  ```

  If the contract refuses the `execute` part (`Delay has not elapsed`,
  `Not enough approvals`, `Wallet is frozen`), the whole transaction
  reverts; the app then sends the same card signature with `approve_card`
  alone, so the approval isn't lost.

- **Checks, in order:** the same proposal checks as `approve_p256`; the
  seed is sealed (`Card challenge not sealed yet; retry in the next
  block`); `now <= sealed_ts + 600` (`Card approval window has closed;
  propose again`); the signer is a card and its signature verifies over
  `card_approve_challenge(intent, seed)` with its enrolled layout (`Card
  signature verification failed`).
- **State and events:** as `approve_p256`.
- **Why the window:** a card signature carries no time, so the window
  doesn't age signatures collected earlier (the seal does that). It stops a
  challenge that was read after the seal from being used much later.
- **Gas:** 2.7 Tgas for a 1024-bit e = 3 card, 21.3 Tgas for a 1984-bit
  e = 65537 card (the worst case EMV allows).

## Execution methods

### `execute`

```rust
pub fn execute(&mut self, id: U64) -> PromiseOrValue<bool>
```

- **Who may send:** anyone; approvals and the policy are the authority.
  The app sends `{"id": "4"}` with 150 Tgas (`TapvaultWallet.execute`), or
  together with `approve_card`. Nothing calls it automatically: a delayed
  payment or an unfreeze waits until someone sends it.
- **Checks for a transfer, in order:**
  1. The proposal exists (`Proposal not found`).
  2. Not frozen (`Wallet is frozen`), even if already approved.
  3. `instant` = the asset has a limit and the amount is within it now
     (`LimitState::is_instant`). An asset without a limit is never instant.
  4. `gate`: not in setup; current signer epoch (`Signer set changed since
     this proposal was created`); then `policy::check_execution`:
     `Not enough approvals`; `A transfer needs at least one phone /
     security-key approval`; `Delay has not elapsed` (`now < ready_ts +
     delay`, delay 0 if instant, else `delays.transfer`); `Proposal expired`
     (more than 7 days after the delay ended).
- **Effects for a transfer:** the amount is recorded against the asset's
  allowance (instant or delayed, both count), the proposal is deleted,
  `executed` is emitted, and the transfer is sent:
  - `Near`: a `Transfer` of `amount` yoctoNEAR to `receiver`.
  - `Ft { token }`: `token.ft_transfer({"receiver_id": receiver, "amount":
    amount})` with 1 yoctoNEAR attached and 15 Tgas (`FT_TRANSFER_GAS`).
    Untested against a real token contract; the app only sends NEAR.
  - Then the callback `on_transfer(id, asset, amount)` with 5 Tgas of
    static gas (`ON_TRANSFER_GAS`).
  - Returns the callback's result: `true` if the transfer went through.
- **Checks and effects for a config change:** the proposal exists; `gate`
  with the config rules (delay `delays.config` if any P-256 signer
  approved, `delays.recovery` if none); no frozen check (config changes run
  while frozen). The ops apply in order, then `validate_config` checks the
  result, then `signer_epoch` is incremented (every other pending proposal
  becomes stale) and every signer's rate counter resets to 0. The proposal
  is deleted, `executed` is emitted, and it returns `true`. Op errors:
  `Too many signers` (`AddSigner` past 8), `Signer not found`, `Invalid
  limits` (`SetLimit` for a 9th asset), `Limit not found`, `Wallet is not
  frozen` (`Unfreeze`), plus any `validate_config` error listed under
  [`setup_finalize`](#setup_finalize).
- **Balance:** if the wallet can't afford a NEAR transfer and still cover
  its storage stake, the receipt fails (the runtime reports
  `LackBalanceForState`, which the app shows as "Not enough NEAR: the
  account must keep enough to pay for its storage") and the proposal stays.
  The app checks the spendable balance before asking for a fingerprint.
- **Events:** `executed` `{"id": "4"}`; `transfer_failed` later if the
  transfer failed.
- **Gas:** 2.7 Tgas for a NEAR transfer, plus 1.6 Tgas for `on_transfer`.

### `on_transfer`

```rust
#[private] pub fn on_transfer(&mut self, id: U64, asset: Asset, amount: U128) -> bool
```

- **Who may send:** only the contract itself (`Method on_transfer is
  private`).
- **Arguments** (sent by the contract):
  `{"id": "4", "asset": "Near", "amount": "500000000000000000000000"}`.
- **What it does:** reads the transfer's result. If it failed (a receiver
  that doesn't exist, a token receiver without storage registration, a
  token panic), the funds stay with or return to the wallet (NEAR refunds a
  failed transfer; a failed `ft_transfer` moves no tokens), and the callback
  gives the allowance back (`LimitState::unrecord`) and emits
  `transfer_failed` `{"id": "4"}`. Returns whether the transfer succeeded.
- **Note:** the proposal was deleted by `execute`; a failed transfer has to
  be proposed again. A client that treats any failed receipt as failure
  (`NearClient.parseOutcome` does) reports the whole transaction as failed
  even though `execute` itself succeeded.

## Guard methods

These only stop things, so one signer is enough.

### `cancel`

```rust
pub fn cancel(&mut self, id: U64, proof: SignerProof)
```

- **Who may send:** anyone with a signer's proof (`cancelByPhone`).
- **Arguments:**
  `{"id": "4", "proof": {"P256": {"signer_index": 0, "sig": {"signature": "<Base64>", "webauthn": null}}}}`.
- **Checks:** the proposal exists; it is from the current epoch (`Signer
  set changed since this proposal was created`; use `close_stale` for stale
  ones); the signer index exists. A P-256 signer must have signed
  `cancel_message(intent)` and may cancel **any** proposal. A card must have
  signed `card_cancel_challenge(intent)` and may cancel any proposal
  **except one that removes that card** (`This signer may not cancel a
  proposal that removes it`). No setup, frozen or time checks.
- **State:** the proposal is deleted.
- **Events:** `cancelled` `{"id": "4"}`.
- **Note:** a card's cancel challenge is deterministic: anyone who reads the
  card while a proposal is open can cancel that proposal. That is a nuisance,
  not a theft path ([DESIGN.md](DESIGN.md) section 7).
- **Gas:** not measured; dominated by the signature check (~32 Tgas by
  phone, about the same as `approve_card` by card).

### `freeze`

```rust
pub fn freeze(&mut self, proof: SignerProof)
```

- **Who may send:** anyone with a signer's proof (`freezeByPhone`,
  `freezeByCard`).
- **Arguments:**
  `{"proof": {"Card": {"signer_index": 1, "sig": {"sdad": "<Base64>", "terminal_data": "<Base64>"}}}}`.
- **Checks:** not in setup (`Wallet is still in setup`); not already
  frozen (`Wallet is frozen`); a P-256 signer signed
  `freeze_message(account, freeze_nonce)`, or a card signed
  `card_freeze_challenge(account, freeze_nonce)`. Not rate-limited.
- **State:** `frozen = true`; `freeze_nonce` becomes a new random value
  (from `env::random_seed_array`, bumped by one if it happens to equal the
  old one), so the signature just used can't freeze again after an
  unfreeze.
- **Effect:** every transfer is refused: new transfer proposals, and
  execution of ones already approved. Config proposals still work.
  Unfreezing is a config proposal with `Unfreeze`, delayed like any other
  config change.
- **Events:** `frozen` `{"signer_index": 1}`.
- **Note:** the current nonce is public (`get_wallet`, `freeze_challenge`)
  and starts at 0, so a card freeze signature can be collected ahead of the
  *next* freeze; it only stops working once the nonce changes. See
  [DESIGN.md](DESIGN.md) section 7.
- **Gas:** 2.4 Tgas by card.

### `close_stale`

```rust
pub fn close_stale(&mut self, id: U64)
```

- **Who may send:** anyone (`closeStale`; Activity → "Clean up"). The app
  shows "Clean up" when the rule below allows it: for stale proposals, for
  ones never fully approved after a day (`createdTs + 86,400`), and for
  approved ones past `ready_ts` + the longest delay + 7 days. Once an
  approved proposal is past its own execution window (`ready_ts` + its
  delay + 7 days), Activity hides Execute and shows "Expired: it can no
  longer execute; cancel it"; "Cancel" removes it at once, "Clean up" once
  this method accepts it.
- **Arguments:** `{"id": "4"}`.
- **Checks:** the proposal exists, and it is dead: from an older signer
  epoch, or `now > dead_after` where `dead_after` is
  `ready_ts + max(transfer, config, recovery) + 7 days` for a proposal that
  became ready and `created_ts + 1 day` (`UNAPPROVED_TTL`) for one that
  never did. Otherwise `Proposal is not stale or expired`.
- **State:** the proposal is deleted, freeing its storage.
- **Events:** none; a plain log line `closed stale proposal 4`.

## Views

View calls are free and need no keys. Hashes and challenges come back as
lowercase hex. Clients should compute what they sign themselves; these
views exist for display and cross-checking.

| View | Arguments | Returns |
|---|---|---|
| `get_wallet` | `{}` | `WalletView` |
| `get_proposal` | `{"id": "4"}` | `ProposalView`, or `null` if there is no such proposal |
| `list_proposals` | `{"from_index": 0, "limit": 20}`, both optional (u32) | up to `limit` (default 20, at most 50) `ProposalView`s, skipping `from_index` entries |
| `intent_for` | `{"action": {...}, "id": "4"}` | hex of `intent_hash(account, id, borsh(action))` |
| `freeze_challenge` | `{}` | `{"p256_message": "<hex>", "card_challenge": "<hex>"}` for the current freeze nonce |
| `cancel_challenge` | `{"id": "4"}` | `{"p256_message": "<hex>", "card_challenge": "<hex>"}`; fails with `Proposal not found` |

`get_wallet` of the live test wallet `tv-e2e-cc807a553f.testnet`, created by
`TestnetE2ETest` with `TAPVAULT_CARD_PROFILE=VISA_FDDA` (12 Nov, after the
test froze it):

```json
{"setup_mode": false, "frozen": true, "threshold": 2, "signer_epoch": 0,
 "freeze_nonce": "4472569340498811704", "next_proposal_id": "4",
 "delays": {"transfer": 3600, "config": 3600, "recovery": 86400},
 "signers": [
   {"index": 0, "key_hash": "ef9871a0c7361858fe6bad05d7c729bdd8545bfb33aadc1531c57a5a1d6b8db2",
    "key": {"P256": {"pubkey": "AxgQ7IEc0KZlh34ybRFjrHhbM3THSK85rsNCUhR/jrcj", "webauthn": null}}},
   {"index": 1, "key_hash": "2ff11a21b407e4bb6fd9db097106d459d110e71e589e397bab9003c7aeca778b",
    "key": {"EmvCard": {"modulus": "tCQNO/D0GiqggRI8vC/Vql0kJK8KIN/Z/COgklY/PlCcsN8tMowTTeb2DdmhDLJs7M+hlxfgqg4tdBgQze8wfO4bKXwUX7Rz/1hCl5W+Ymz5ppOcmhhGpikairRC0PJA2WyuMkoy5DrajmkZa3nePwgKNGBYXBWLQoi2hCPPVYc=",
                        "exponent": 3, "un_offset": 0, "un_len": 4, "terminal_data_len": 19}}}],
 "limits": [{"asset": "Near", "instant_max": "1000000000000000000000000",
             "daily_max": "2000000000000000000000000", "updated_at": 1794470112,
             "used": "500000000000000000000000"}]}
```

(The card is the tests-only simulated card (`SimCard.kt`) with its public
test key; the app has no simulated card. The 19-byte layout means it signs
like a Visa card online.)

- `limits[].used` is the allowance used as of `updated_at`, not now.
  Compute the current value with the refill rule in
  [Limits](#limits) (Kotlin: `LimitInfo.isInstant`, `availableNow`).
- `freeze_nonce` is a `U64` string; it can exceed a signed 64-bit integer.

A `ProposalView` from the same wallet:

```json
{"id": "3", "intent_hash": "13ee68354f5c5f1d23aafb0535adf4727a83a727c2da7143a44e20bc18a12178",
 "created_ts": 1794470137, "created_height": "277334786", "approvals": 1, "proposer": 0,
 "ready_ts": null, "card_deadline_ts": 1794470738, "stale": false,
 "action": {"Transfer": {"asset": "Near", "amount": "100000000000000000000000",
            "receiver": "2834ff4c1b98ec15d5c02bd29dc8c2339a3ab966d181b34de733016b795428a8"}},
 "card_challenge": "261f8f2d209ea736c9a63df51c31b8c532c1ac1eb1c7651fce6c743efb968f11"}
```

| Field | Meaning |
|---|---|
| `approvals` | Bitmap: bit i set means signer i approved (here: the phone) |
| `proposer` | Signer index that proposed (counts toward its caps) |
| `ready_ts` | When it first had every approval it needs; delays run from here. `null` until then |
| `card_deadline_ts` | `sealed_ts + 600`: card approvals are accepted until then. `null` until sealed |
| `stale` | Signer epoch moved on: it can't be approved, executed or cancelled, only closed |
| `card_challenge` | What a card must sign to approve (its first `un_len` bytes are the UN). `null` until sealed |

The view does not return the seed itself, so a client can't recompute
`card_challenge`; it has to take it from the RPC (see
[DESIGN.md](DESIGN.md) section 7). `list_proposals` iterates in insertion
order, except that deleting a proposal moves the last one into its slot;
`from_index` is a position, not an id.

## Events

Every event is one log line in NEP-297 form (`contract/src/events.rs`).
The keys come out in alphabetical order (near-sdk's `serde_json` without
`preserve_order`), as in this line from a live testnet payment:

```
EVENT_JSON:{"data":[{"id":"0","signer_index":1}],"event":"approved","standard":"tapvault","version":"1.0.0"}
```

| Event | Emitted by | `data[0]` |
|---|---|---|
| `setup_finalized` | `setup_finalize` | `{"threshold": 2}` |
| `proposal_created` | `propose` | `{"id": "4", "intent_hash": "<hex>"}` |
| `challenge_sealed` | `seal_challenge` | `{"card_challenge": "<hex>", "id": "4"}` |
| `approved` | `approve_p256`, `approve_card` (not the proposer's own approval) | `{"id": "4", "signer_index": 1}` |
| `ready` | the approval that completes a proposal | `{"id": "4"}` |
| `executed` | `execute` (transfers and config changes) | `{"id": "4"}` |
| `transfer_failed` | `on_transfer` | `{"id": "4"}` |
| `cancelled` | `cancel` | `{"id": "4"}` |
| `frozen` | `freeze` | `{"signer_index": 1}` |

No event for `new`, `setup_add_signer` or an unfreeze (its proposal emits
`executed`). `close_stale` writes a plain log line, not an event. When a
signature check fails, the contract logs the precise reason just before it
panics (`emv: EmvHashMismatch`, `webauthn: WebAuthnRpIdMismatch`; the names
are the `CoreError` variants in `crates/tapvault-core/src/error.rs`); look
for it in the failed receipt's logs on the explorer.

## Errors

Every message from `contract/src/error.rs`, and where it comes from.

| Constant | Message | Raised by |
|---|---|---|
| `ERR_IN_SETUP` | `Wallet is still in setup` | `propose`, `approve_p256`, `approve_card`, `freeze` before `setup_finalize` |
| `ERR_NOT_IN_SETUP` | `Wallet setup is already finalized` | `setup_add_signer`, `setup_finalize` after setup |
| `ERR_FROZEN` | `Wallet is frozen` | transfer `propose` or `execute` while frozen; `execute_upgrade` while frozen (v0.2); `freeze` when already frozen |
| `ERR_NOT_FROZEN` | `Wallet is not frozen` | executing `Unfreeze` when not frozen |
| `ERR_TOO_MANY_SIGNERS` | `Too many signers` | `setup_add_signer` at 8 signers; `AddSigner` past 8 |
| `ERR_DUPLICATE_SIGNER` | `Signer already enrolled` | `setup_add_signer`; config validation |
| `ERR_INVALID_SIGNER_KEY` | `Invalid signer key` | `SignerKey::validate` (bad point, bad modulus or exponent, bad layout, WebAuthn without `require_uv`) |
| `ERR_WRONG_SIGNER_KIND` | `Signer is the wrong kind for this operation` | a proof, authorizer or possession of the wrong kind for the signer |
| `ERR_SIGNER_INDEX` | `Signer index out of range` | any index past the end of `signers` |
| `ERR_P256_INVALID` | `P-256 signature invalid` | P-256 signature doesn't verify (wrong key, wrong message, wrong proposal id) |
| `ERR_MISSING_WEBAUTHN` | `WebAuthn signer requires an assertion payload` | WebAuthn signer without `webauthn` in its `P256Sig` |
| `ERR_WEBAUTHN_INVALID` | `WebAuthn assertion invalid` | assertion checks failed (reason logged as `webauthn: ...`) |
| `ERR_CARD_INVALID` | `Card signature verification failed` | any card check failed (reason logged as `emv: ...`) |
| `ERR_INVALID_THRESHOLD` | `Invalid threshold` | threshold below 2 (v0.3: below 1 with one signer) or above the signer count |
| `ERR_NO_DELIBERATE_SIGNER` | `At least one P-256 (phone / security key) signer is required` | config leaves no P-256 signer |
| `ERR_INVALID_DELAYS` | `Invalid delays` | delays out of bounds |
| `ERR_INVALID_LIMITS` | `Invalid limits` | more than 8 limits, `instant_max > daily_max`, duplicate asset |
| `ERR_INVALID_ACTION` | `Invalid action` | zero amount; config with 0 or more than 4 ops |
| `ERR_RATE_LIMITED` | `Proposal rate limit reached for today` | 25th proposal by one signer in a window |
| `ERR_NO_PROPOSAL` | `Proposal not found` | unknown id (executed, cancelled or closed ones are gone) |
| `ERR_STALE_PROPOSAL` | `Signer set changed since this proposal was created` | approving, executing or cancelling a stale proposal |
| `ERR_ALREADY_APPROVED` | `Signer already approved` | second approval by the same signer |
| `ERR_CHALLENGE_EXPIRED` | `Card approval window has closed; propose again` | `approve_card` more than 600 s after the seal |
| `ERR_TOO_MANY_OPEN` | `This signer has too many open proposals; cancel or wait for some` | a 5th live proposal by one signer |
| `ERR_INIT_NOT_SELF` | `Only the wallet account itself can initialise it` | `new` from another account |
| `ERR_FINALIZE_SIGNER` | `setup_finalize must be sent by the wallet account itself, so its key is deleted` | `setup_finalize` signed by another account |
| `ERR_CHALLENGE_NOT_READY` | `Card challenge not sealed yet; retry in the next block` | `approve_card` before `seal_challenge` ran |
| `ERR_NOT_ENOUGH_APPROVALS` | `Not enough approvals` | `execute` below threshold |
| `ERR_NEEDS_DELIBERATE` | `A transfer needs at least one phone / security-key approval` | `execute` of a transfer approved by cards only |
| `ERR_TOO_EARLY` | `Delay has not elapsed` | `execute` before `ready_ts + delay` |
| `ERR_EXPIRED` | `Proposal expired` | `execute` more than 7 days after the delay ended |
| `ERR_NOT_CLOSABLE` | `Proposal is not stale or expired` | `close_stale` on a live proposal |
| `ERR_SIGNER_NOT_FOUND` | `Signer not found` | executing `RemoveSigner` with an unknown key hash |
| `ERR_LIMIT_NOT_FOUND` | `Limit not found` | executing `RemoveLimit` for an asset without a limit |
| `ERR_CANNOT_CANCEL` | `This signer may not cancel a proposal that removes it` | a card cancelling its own removal |

From near-sdk: `Method <name> doesn't accept deposit`, `Method
seal_challenge is private`, `Method on_transfer is private`, `The contract
has already been initialized`, `The contract is not initialized`, `Failed to
deserialize input from JSON.`. From the NEAR runtime, for example:
`LackBalanceForState` (a transfer would eat into the storage stake),
`AccountDoesNotExist` (the receiver of a NEAR transfer; the transfer fails
and `on_transfer` restores the allowance).

What the app says for each of these: [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Constants and rules

### Constants

| Constant | Value | Defined in | Meaning |
|---|---|---|---|
| `CARD_APPROVAL_WINDOW` | 600 s | `contract/src/lib.rs` | Card approvals accepted until `sealed_ts + 600` |
| `MAX_OPEN_PER_SIGNER` | 4 | `contract/src/lib.rs` | Live proposals per proposer |
| `UNAPPROVED_TTL` | 86,400 s (`policy::DAY`) | `contract/src/lib.rs` | A never-ready proposal can be closed after this |
| `EXECUTION_WINDOW` | 604,800 s (7 days) | `policy.rs` | Time to execute after the delay ends |
| `MAX_PROPOSALS_PER_DAY` | 24 | `policy.rs` | Proposals per signer per rate window |
| `DAY` | 86,400 s | `policy.rs` | Rate window; allowance refill period |
| `MIN_THRESHOLD` | 2 | `policy.rs` | Lowest threshold after setup with two or more signers |
| `PHONE_ONLY_THRESHOLD` | 1 | `policy.rs` | v0.3: threshold of a wallet whose one signer is the phone |
| `MAX_SIGNERS` | 8 | `types.rs`, `policy.rs` (asserted equal) | The approval bitmap is a `u8` |
| `MAX_LIMITS` | 8 | `types.rs` | Assets with a limit |
| `MAX_CONFIG_OPS` | 4 | `types.rs` | Ops per config action |
| `P256_PUBKEY_LEN`, `P256_SIG_LEN` | 33, 64 | `types.rs` | Compressed key, raw `r \|\| s` signature |
| `MIN_TRANSFER_DELAY`, `MIN_CONFIG_DELAY` | 3,600 s | `policy.rs` | Lower bounds |
| `MIN_RECOVERY_DELAY` | 86,400 s | `policy.rs` | Lower bound |
| `MAX_DELAY` | 2,592,000 s (30 days) | `policy.rs` | Upper bound for every delay |
| `SEAL_GAS`, `ON_TRANSFER_GAS` | 5 Tgas | `proposals.rs`, `execute.rs` | Static gas of the two callbacks |
| `FT_TRANSFER_GAS` | 15 Tgas | `execute.rs` | Gas for `ft_transfer` |
| `MIN_ICC_MODULUS_BYTES`, `MAX_ICC_MODULUS_BYTES` | 64, 248 | `emv.rs` | Card key 512 to 1984 bits |
| `MIN_UN_LEN`, `MAX_UN_LEN` | 4, 8 | `emv.rs` | Challenge bytes in the UN |
| `MAX_TERMINAL_DATA_LEN` | 64 | `emv.rs` | Terminal data the card signs |
| `bigint::MAX_MODULUS_BYTES` | 256 | `bigint.rs` | RSA routine's hard limit |

### Delays

| Delay | Applies to | `Delays::DEFAULT` | App wallets (`Delays.SHORTEST`) | Allowed range |
|---|---|---|---|---|
| `transfer` | transfers not within the limits | 86,400 s (24 h) | 3,600 s (1 h) | 3,600 to 2,592,000 s |
| `config` | config changes with at least one P-256 approval | 86,400 s (24 h) | 3,600 s (1 h) | 3,600 to 2,592,000 s |
| `recovery` | config changes with no P-256 approval (lost phone) | 259,200 s (72 h) | 86,400 s (24 h) | 86,400 to 2,592,000 s, and at least `config` |

Delays count from `ready_ts`, the moment the proposal first had every
approval it needs, not from when it was proposed.

### Approval and execution rules

`ready_ts` is stamped the first time a proposal has `threshold` approvals
and, for a transfer, at least one of them from a P-256 signer. `execute`
then applies `policy::check_execution`:

1. approvals >= threshold, else `Not enough approvals`;
2. a transfer needs a P-256 approval, else `A transfer needs at least one
   phone / security-key approval`; its delay is 0 if it is within the limits
   when `execute` runs, else `delays.transfer`;
3. a config change waits `delays.config` if a P-256 signer approved,
   `delays.recovery` if none did;
4. `now >= ready_ts + delay`, else `Delay has not elapsed`;
5. `now <= ready_ts + delay + EXECUTION_WINDOW`, else `Proposal expired`.

Plus: not in setup, same signer epoch, and for transfers not frozen.
Executing any config change increments `signer_epoch`, which voids every
other pending proposal (approval bitmaps are indexed by signer position,
which may have moved).

### Limits

One `MintLimit` per asset (at most 8): `instant_max`, `daily_max`, and the
leaky-bucket state `used` and `updated_at` (`policy::LimitState`):

- **Refill:** `used_at(now)` is 0 if `now - updated_at >= 86,400`, else
  `used - floor(daily_max * (now - updated_at) / 86,400)`, never below 0.
  The allowance refills continuously at `daily_max` per day, so there is no
  window edge to exploit.
- **Instant:** `amount <= instant_max` and
  `used_at(now) + amount <= daily_max`.
- **Record:** every executed transfer of that asset, instant or delayed,
  sets `used = used_at(now) + amount`, `updated_at = now`. A large delayed
  transfer therefore uses up the allowance for up to a day.
- **Unrecord:** a failed transfer gives its amount back (`on_transfer`).
- **No limit for an asset** means every transfer of it is delayed.
- **Rules** (`validate_config`): `instant_max <= daily_max`, one limit per
  asset, at most 8. Zero is allowed (nothing instant).

### Rate limit and open-proposal cap

- **Rate limit** (`policy::rate_limit`): each signer index may create at
  most 24 proposals per window. The window is shared: it starts at the first
  proposal after the previous window ended (`rate_window_start`) and lasts
  86,400 s, and all counts (`proposals_in_window`) reset when it rolls over.
  A signer can therefore make 24 just before a rollover and 24 more just
  after; on average it is at most 24 a day. Executing a config change also
  resets every count. Failed calls don't count (they revert).
- **Open cap:** a proposer may have at most 4 live proposals. Live means
  the current signer epoch and either fully approved (`ready_ts` set, until
  it is executed, cancelled or closed, even after it expires) or created no
  more than 660 s ago (`CARD_APPROVAL_WINDOW + 60`). A proposal that was
  never fully approved stops blocking its proposer 660 s after creation,
  without being cleaned up.
- **In app wallets** the phone proposes everything, so the phone's quota is
  the wallet's: at most 24 payments and unfreeze requests per window, and at
  most 4 live at once (approved ones waiting for Execute, plus ones still
  inside their first 660 s).

### Proposal lifecycle

```
                     propose (P-256 proof = first approval; card proof = none)
                        |
                        v
   created ---- seal_challenge (a later block) ----> sealed: card_challenge set,
      |                                              card approvals until sealed_ts + 600
      |  approve_p256 (any time) / approve_card (inside the window)
      v
   ready (ready_ts) once threshold is met (+ a P-256 approval for transfers)
      |
      |  delay: 0 for a transfer within limits, else transfer / config / recovery
      v
   executable until ready_ts + delay + 7 days --- execute ---> deleted (+ transfer)

   cancel: deletes it any time (unless stale)
   a config change executes elsewhere: stale; can only be closed
   close_stale: stale, or never ready after 1 day, or past ready_ts + max delay + 7 days
```

## Signed messages

Every message is SHA-256 over a domain tag followed by fields
(`crates/tapvault-core/src/domain.rs`, mirrored by `Domain.kt`). Encoding:
`len32(account)` is the account id's UTF-8 byte length as a u32
little-endian, `u64` values are little-endian, `signer_count` is one byte.

| Rust / Kotlin | Tag | Fields after the tag | Signed by | Checked in |
|---|---|---|---|---|
| `create_message` / `createMessage` | `tapvault/near/v1/create` | `len32(account) ‖ account ‖ first_key_hash` | the first P-256 signer (the phone) | `new` |
| `setup_add_message` / `setupAddMessage` | `tapvault/near/v1/setup-add` | `len32 ‖ account ‖ signer_count ‖ new_key_hash` | the P-256 authorizer; also a new P-256 signer as its possession proof | `setup_add_signer` |
| `card_enroll_challenge` / `cardEnrollChallenge` | `tapvault/near/v1/card-enroll` | `len32 ‖ account ‖ signer_count ‖ new_key_hash` | the new card | `setup_add_signer` |
| `finalize_message` / `finalizeMessage` | `tapvault/near/v1/finalize` | `len32 ‖ account ‖ borsh(FinalizeArgs)` | the P-256 authorizer | `setup_finalize` |
| `intent_hash` / `intentHash` | `tapvault/near/v1/intent` | `len32 ‖ account ‖ u64 proposal_id ‖ borsh(Action)` | a P-256 proposer (its approval) and P-256 approvers | `propose`, `approve_p256` |
| `card_propose_challenge` / `cardProposeChallenge` | `tapvault/near/v1/card-propose` | `intent` | a card proposer (not an approval) | `propose` |
| `card_approve_challenge` / `cardApproveChallenge` | `tapvault/near/v1/card-approve` | `intent ‖ seed` | a card approver | `approve_card` |
| `cancel_message` / `cancelMessage` | `tapvault/near/v1/cancel` | `intent` | a P-256 signer | `cancel` |
| `card_cancel_challenge` / `cardCancelChallenge` | `tapvault/near/v1/card-cancel` | `intent` | a card | `cancel` |
| `freeze_message` / `freezeMessage` | `tapvault/near/v1/freeze` | `len32 ‖ account ‖ u64 freeze_nonce` | a P-256 signer | `freeze` |
| `card_freeze_challenge` / `cardFreezeChallenge` | `tapvault/near/v1/card-freeze` | `len32 ‖ account ‖ u64 freeze_nonce` | a card | `freeze` |
| `key_hash_p256` / `keyHashP256` | `tapvault/near/v1/key/p256` | `pubkey` (33 bytes) | nobody: a signer's identity | duplicates, `RemoveSigner`, views |
| `key_hash_emv` / `keyHashEmv` | `tapvault/near/v1/key/emv` | `u32 LE exponent ‖ modulus` | nobody: a card's identity (no PAN in it) | same |

How each kind of signer signs a 32-byte message:

- **Phone (raw P-256):** ECDSA P-256 with SHA-256 over the 32 bytes
  (`SHA256withECDSA` in the Keystore), sent as 64-byte `r || s`.
- **WebAuthn signer:** the 32 bytes are the WebAuthn challenge; the
  authenticator signs `authenticatorData || SHA-256(clientDataJSON)`.
- **Card:** the first `un_len` bytes (4 on real cards) become the
  Unpredictable Number in the terminal data the card signs.

Properties:

- **No cross-purpose replay.** Thirteen distinct tags, none a prefix of
  another (test `domains_are_distinct`): a cancel can't become an approval,
  a freeze challenge can't approve a transfer.
- **Bound to one wallet.** Each message contains the account id, directly
  or through the intent hash. The account id is length-prefixed so it can't
  be shifted into the next field (test `account_id_is_length_prefixed`).
- **Single use.** The intent contains the proposal id and an approval is a
  bit that can be set once; freeze messages contain the freeze nonce, which
  changes on every freeze and unfreeze; setup messages contain the signer
  count; `create` works once (state then exists); `finalize` works once
  (setup mode ends).
- **Reference values.** `android/core/src/test/resources/vectors.json`
  (account `tv-demo.testnet`) pins every message, key hash and Borsh
  encoding. For example `freeze_message("tv-demo.testnet", 3)` is
  `dbf3867127843b4a7d35025f087886a907b6995d2f837bbbda50d78d7576d25c`.

## The card signature check

`auth::verify_card` calls `emv::verify_card_challenge(layout, sdad,
terminal_data, challenge)` from `crates/tapvault-core/src/emv.rs`. A card
answers with Signed Dynamic Application Data (SDAD); recovered with the
card's public key it must read (EMV Book 2, section 6.5.2, table 17):

```
6A | format (05 or 95) | 01 | Ldd | ICC dynamic data (Ldd bytes) | BB ... BB | SHA-1 (20 bytes) | BC
```

The checks, in order (each failure is logged as `emv: <CoreError>` and
reported as `Card signature verification failed`):

| # | Check | `CoreError` |
|---|---|---|
| 1 | The enrolled layout is valid (modulus 64 to 248 bytes, no leading zero, odd; exponent 3 or 65537; `un_len` 4 to 8; `terminal_data_len <= 64`; `un_offset + un_len <= terminal_data_len`) | `EmvModulusTooLarge`, `BadLength`, `EmvUnsupportedExponent`, `EmvTerminalDataLayout` |
| 2 | `len(terminal_data) == terminal_data_len` | `EmvTerminalDataLayout` |
| 3 | `terminal_data[un_offset .. un_offset + un_len] == challenge[0 .. un_len]` | `EmvChallengeMismatch` |
| 4 | `len(sdad) == len(modulus)` | `BadLength` |
| 5 | `sdad < modulus` as integers | `SignatureNotReduced` |
| 6 | `rec = sdad^e mod n`, big-endian, modulus length (`bigint::rsa_public`) | |
| 7 | `rec[0] == 6A` | `EmvBadHeader` |
| 8 | `rec[n-1] == BC` | `EmvBadTrailer` |
| 9 | `rec[1]` is `05` or `95` | `EmvBadFormat` |
| 10 | `rec[2] == 01` (SHA-1) | `EmvBadHashAlgorithm` |
| 11 | `Ldd = rec[3]` and `Ldd + 25 <= n` | `EmvBadDynamicDataLength` |
| 12 | every byte from `rec[4 + Ldd]` up to the hash is `BB` | `EmvBadPadding` |
| 13 | `SHA-1(rec[1 .. n-21] ‖ terminal_data) == rec[n-21 .. n-1]` | `EmvHashMismatch` |

SHA-1 and the RSA operation run in WASM (`sha1.rs`, `bigint.rs` with
32-bit limbs); NEAR has no host function for either. That is why a
1984-bit, e = 65537 card costs 21.3 Tgas and a 1024-bit, e = 3 card 2.7.

### Signed Data Format `05` or `95`

`05` is what DDA, CDA and offline fDDA produce. `95` is what a Visa card
produces for fDDA on an online transaction (when it also generates an
ARQC). Both are hashed the same way, and the format byte is inside both the
RSA-signed block and the SHA-1 input, so a signature can't be relabelled
from one to the other. Every other format byte is refused (test
`only_formats_05_and_95_are_accepted`). Builds before 12 Nov accepted only
`05`; see [The shipped build](#the-shipped-build). Why this is safe:
[DESIGN.md](DESIGN.md) section 1.

### Terminal-data layouts

Each card is enrolled with the layout its signature had at enrolment, and
every later signature must have exactly that layout.

| Method | Command that makes the card sign | Terminal data the hash covers | Layout (`un_offset`, `un_len`, `terminal_data_len`) | Format |
|---|---|---|---|---|
| DDA | INTERNAL AUTHENTICATE (`00 88`) with the DDOL values | the DDOL values in DDOL order; the default DDOL `9F37 04` is just the UN | `0, 4, 4` with the default DDOL; otherwise the UN's position in the DDOL | `05` |
| Visa fDDA | GET PROCESSING OPTIONS (`80 A8`), UN in the PDOL's `9F37` | UN (4) ‖ Amount Authorised `9F02` (6) ‖ Transaction Currency Code `5F2A` (2) ‖ Card Authentication Related Data `9F69` (7 for fDDA version 01) | `0, 4, 19`; also `0, 4, 12` (no `9F69`) or `0, 4, 4` (fDDA version 00) | `95` online, `05` offline |
| CDA | GENERATE AC (`80 AE`, P1 `0x90` ARQC or `0x50` TC, both with the CDA bit) with the CDOL1 values | the UN only (EMV Book 2, section 6.6.2) | `0, 4, 4` | `05` |

How the app picks the method and fills the other fields:
[CARDS.md](CARDS.md).

### What is not checked on-chain

- **The certificate chain** (CA → issuer → card). The certificates contain
  the card number and anyone can RSA-recover them, so they never go
  on-chain. The phone checks the chain at enrolment
  (`EmvSession.recoverCardKey`); the contract trusts the enrolled key, which
  had to sign its enrolment challenge.
- **The ICC dynamic data** (the card's own number, ATC, cryptogram,
  transaction data hash): accepted as whatever the card signed.
- **Terminal data other than the UN** (amount, currency, date, `9F69`,
  other DDOL fields): not compared to anything. They are inside the card's
  SHA-1, so they are whatever the card actually saw; only the UN carries
  the challenge.
- **Card status:** expiry, revocation, whether the issuer still honours the
  card. The contract can't know.
- **Which method produced the signature.** DDA, fDDA and CDA signatures
  with the same layout are interchangeable, which is what lets the app fall
  back between methods (`CardService.sign`).

## The P-256 check

`auth::verify_p256` and `auth::p256_ok` (`contract/src/auth.rs`):

- **Key:** SEC1 compressed, 33 bytes, `02` or `03` prefix, a valid curve
  point (`p256::PublicKey::from_sec1_bytes`), checked at enrolment and in
  every config validation.
- **Signature:** exactly 64 bytes, `r || s`, each 32 bytes big-endian. DER
  is not accepted.
- **Verification:** the digest is SHA-256 of the signed bytes, computed with
  NEAR's `sha256` host function; the curve math runs in WASM (`p256` crate
  0.13, `VerifyingKey::verify_prehash`). NEAR has no P-256 host function,
  so this costs about 32 Tgas.
- **Raw signer (the phone):** the signed bytes are the 32-byte message.
- **WebAuthn signer** (`webauthn::signed_message`): the signed bytes are
  `authenticatorData || SHA-256(clientDataJSON)`, after these checks:
  - `authenticatorData` is at least 37 bytes and starts with the enrolled
    `rp_id_hash` (`WebAuthnAuthDataTooShort`, `WebAuthnRpIdMismatch`);
  - the User Present flag (`0x01`) is set, and User Verified (`0x04`)
    because enrolment requires `require_uv` (`WebAuthnUserNotPresent`,
    `WebAuthnUserNotVerified`);
  - `"type":"webauthn.get"` appears at byte `type_index` of
    `clientDataJSON` (`WebAuthnTypeMismatch`);
  - `"challenge":"<base64url, no padding, of the 32-byte message>"` appears
    at byte `challenge_index` (`WebAuthnChallengeMismatch`).

  `clientDataJSON` is covered by the signature, so the indices only say
  where to look. The contract supports WebAuthn signers; the app has no
  client for them yet. Tested only by the unit tests in `webauthn.rs` and
  the flow test `passkeys_must_require_user_verification`; no test sends a
  real assertion through the contract.
- **Replay protection never relies on a signature being unique.** It comes
  from what is signed (proposal id and approval bitmap, freeze nonce, signer
  count).

## State and storage

The contract state (`Tapvault` in `contract/src/lib.rs`):

| Field | Type | Meaning |
|---|---|---|
| `setup_mode` | bool | true from `new` until `setup_finalize` |
| `frozen` | bool | transfers blocked |
| `threshold` | u8 | 0 during setup, then 2..=signers |
| `signer_epoch` | u32 | incremented by every executed config change |
| `freeze_nonce` | u64 | 0 until the first freeze, then random; changes on every freeze and unfreeze |
| `next_proposal_id` | u64 | the id the next proposal gets |
| `rate_window_start`, `proposals_in_window` | i64, `[u16; 8]` | rate limit state |
| `delays` | `DelaysCfg` | seconds |
| `signers` | `Vec<SignerKey>` | index = position |
| `limits` | `Vec<MintLimit>` | per-asset limit and allowance state |
| `proposals` | `IterableMap<u64, Proposal>` | open proposals, under storage prefix `StorageKey::Proposals` |

A `Proposal` holds `intent_hash`, `created_ts`, `created_height`,
`signer_epoch`, `proposer`, `ready_ts`, `approvals` (u8 bitmap), `seed`
and `sealed_ts` (set once by `seal_challenge`) and the `action`.

Storage is paid from the wallet account's own balance (NEAR storage staking,
10^19 yoctoNEAR per byte). The contract never asks callers for a storage
deposit.

- The code is 334,258 bytes, about 3.34 NEAR locked for as long as the
  wallet exists.
- The state is small. Each open proposal adds a few hundred bytes until it
  is executed, cancelled or closed; nothing deletes proposals by itself.
- What a wallet can send is its balance minus `storage_usage × 10^19`
  (`NearClient.spendable`; Home shows the locked part as "held for the
  wallet contract's storage"). Keep some NEAR in a wallet that mostly holds
  tokens.

## Creating a wallet

The app (`AppViewModel.continueSetup`, `Onboarding` in `Wallet.kt`) does
this; any client must do the same steps.

```
App (phone)                                          NEAR testnet
-----------                                          ------------
create the P-256 key in the Keystore
create two accounts (faucet, or sponsor) --------->  wallet  tv-<hex>.testnet      key W
                                                     fee     tv-<hex>-gas.testnet  key G
tap the card: read its key through the
certificate chain; card signs
card_enroll_challenge(wallet, 1, key_hash)
(no contract needed yet)
tx signed by W, to wallet:                --------->  DeployContract(wasm) + new(phone key, sig)
  fingerprint: create_message                         one receipt: both land, or neither
tx signed by W, to wallet:                --------->  setup_add_signer(card, 0, sig, Card(proof))
  fingerprint: setup_add_message
tx signed by W, to wallet:                --------->  setup_finalize(args, 0, sig)
  fingerprint: finalize_message                       then a DeleteKey(W) receipt
view_access_key_list(wallet)              <---------  [] : only the contract can move funds
```

- **Deploy and `new` in one batch.** One transaction from the wallet account
  to itself with two actions, `DeployContract(wasm)` and
  `FunctionCall("new", ..., 100 Tgas)`. Both run in one receipt: if `new`
  fails, the deploy is undone too. Because the wallet account is the
  predecessor, `new`'s self-check passes; a contract deployed without `new`
  can't be initialised by anyone else (the sandbox test checks this).
- **The card is read before the deploy.** The enrolment challenge depends
  only on the wallet's account id, the signer count (1) and the card's key
  hash, so the card can sign it before the contract exists. A card that
  can't sign stops setup before anything is deployed; the key and proof are
  kept (`Store.pendingCard`) until setup finishes (cleared after
  `setup_finalize`), so a retry doesn't need another tap.
- **Funding.** The faucet gives each account 10 NEAR; the sponsor gives the
  wallet 5.5 NEAR and the fee account 0.5 NEAR
  (`Onboarding.Sponsor.WALLET_FUNDING`, `GAS_FUNDING`). The wallet needs
  about 3.34 NEAR for the code before `new` can run.
- **`setup_finalize` deletes the key** that signed it. The account then has
  no access keys. The app checks `view_access_key_list` and forgets the key
  (`Store.walletKey = null`).
- **Resumable.** Each step checks the chain first (`get_wallet`) and skips
  what already landed.

## The shipped build

`contract/res/tapvault_contract.wasm` is the reviewed build that the app
deploys to every new wallet. Details: [`contract/res/README.md`](../contract/res/README.md).

| | |
|---|---|
| SHA-256 (hex) | `778a310ad4db8a87d6d0d52e8509d7ea4f5014782a0832106ab1cd2946e582c6` |
| SHA-256 (base58, NEAR `code_hash`) | `93djY2LQU6NbNHJ93GmCDtKXecdrZhQPVCZTUcWt1Kxy` |
| Size | 369,809 bytes |
| Built with | rustc 1.86.0 (`rust-toolchain.toml`), cargo-near 0.17.0, `cargo near build non-reproducible-wasm` |
| Shipped since | 14 Nov 2026 (afternoon): contract v0.4.0 (inheritance, plus everything in v0.3 and v0.2). Wallets made earlier on 14 Nov run `DdhpDh3c2gbVJK4iFQLU7C3c2W4Bgg6JYTBSBtBDRcS7` (v0.3.0, no inheritance); wallets made from 12 Nov until then run `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf` (v0.1; accepts Signed Data Format `05` and `95`) |
| Previous build | `GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow` (wallets created before 12 Nov): accepts only `05`, so it refuses Visa online fDDA signatures |

- **Hash-checked by the Android build.** In `android/app/build.gradle.kts`,
  task `verifyWasm` refuses to build unless the file's SHA-256 equals
  `contractWasmSha256`; `copyWasm` then copies it to
  `android/app/src/main/assets/tapvault_contract.wasm` (that copy is
  gitignored) before `preBuild`. `AppViewModel.continueSetup` reads the
  asset and `Onboarding.deployAndInit` deploys those bytes.
- **Not reproducible elsewhere.** A non-reproducible build embeds local
  paths, so rebuilding the same source on another machine gives a different
  hash. Keep the committed file unless the contract really changed.
  (`contract/Cargo.toml` has a `reproducible_build` section, but its
  `image_digest` is empty, so `cargo near build reproducible-wasm` isn't set
  up.)
- **No upgrades.** Each wallet keeps the code it was deployed with. A
  wallet on the previous build stays on it; to get the new code, create a
  new wallet and move the funds with a normal payment.

### Verify a wallet

The `code_hash` of the wallet account must equal the shipped build's base58
hash, and the account must have no access keys:

```bash
ACCOUNT=tv-e2e-cc807a553f.testnet
RPC=https://rpc.testnet.fastnear.com

curl -s "$RPC" -H 'Content-Type: application/json' -d '{
  "jsonrpc": "2.0", "id": "tv", "method": "query",
  "params": {"request_type": "view_account", "finality": "final", "account_id": "'"$ACCOUNT"'"}
}' | python3 -c 'import json, sys; print(json.load(sys.stdin)["result"]["code_hash"])'
# 93djY2LQU6NbNHJ93GmCDtKXecdrZhQPVCZTUcWt1Kxy

curl -s "$RPC" -H 'Content-Type: application/json' -d '{
  "jsonrpc": "2.0", "id": "tv", "method": "query",
  "params": {"request_type": "view_access_key_list", "finality": "final", "account_id": "'"$ACCOUNT"'"}
}' | python3 -c 'import json, sys; print(json.load(sys.stdin)["result"]["keys"])'
# []
```

The base58 hash of a local file:

```bash
python3 - contract/res/tapvault_contract.wasm <<'EOF'
import hashlib, sys
h = hashlib.sha256(open(sys.argv[1], "rb").read()).digest()
A = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
n, s = int.from_bytes(h, "big"), ""
while n:
    n, r = divmod(n, 58)
    s = A[r] + s
print(h.hex())
print("1" * (len(h) - len(h.lstrip(b"\0"))) + s)
EOF
```

A wallet with any other code hash, or with access keys left, is not
protected by the rules on this page.

## Changing the contract safely

Changing the contract changes what every client must sign, and the result
only reaches wallets created afterwards.

1. **Edit** `crates/tapvault-core` for checks, policy and messages,
   `contract/src` for the NEAR side. Keep security logic in the core crate,
   where it is tested natively.
2. **Run the Rust tests** from the repository root: `cargo test` (20 core
   unit tests, 7 EMV vector tests, 24 contract flow tests, the vectors
   test). Add a flow test in `contract/tests/flows.rs` for any new rule.
3. **If any type, Borsh layout or signed message changed**, regenerate the
   reference vectors and update the Kotlin mirror in the same change:

   ```bash
   UPDATE_VECTORS=1 cargo test -p tapvault-contract --test vectors
   # rewrites android/core/src/test/resources/vectors.json
   cd android && ./gradlew :core:test   # VectorsTest must pass against the new file
   ```

   Then update `Model.kt`, `Borsh.kt` or `Domain.kt` until `VectorsTest`
   passes. Without `UPDATE_VECTORS` the vectors test fails if the file is
   stale, which is the point.
4. **If the card check changed**, update its Kotlin twin `Rsa.verifySdad` in
   `Emv.kt` by hand (the app checks every card signature locally before
   sending it, so a mismatch makes the app refuse cards the contract accepts
   or the other way round), extend `EmvTest`, and if needed the EMV vectors
   (`tools/gen_emv_vectors.py`, which needs the Python `cryptography`
   package).
5. **Build the wasm** with the pinned toolchain (rustc 1.86.0, cargo-near
   0.17.0; near-sdk ~5.14 and near-workspaces 0.20 with sandbox nearcore
   2.6.5 are pinned because newer combinations broke the build and tests):

   ```bash
   cargo install --locked cargo-near@0.17.0   # once
   cd contract && cargo near build non-reproducible-wasm
   # -> target/near/tapvault_contract/tapvault_contract.wasm (repository root)
   ```

6. **Run the sandbox test** and compare the gas with the table at the top:

   ```bash
   cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture
   ```

   It compiles the contract from source (`near_workspaces::compile_project`)
   and runs it on a downloaded nearcore 2.6.5 node; it doesn't use the file
   in `contract/res`.
7. **Ship the file:** copy it to `contract/res/tapvault_contract.wasm`,
   compute its hashes (`shasum -a 256`, and the base58 snippet above), then
   update `contractWasmSha256` and the base58 hash in the comment above it
   in `android/app/build.gradle.kts`, the hash table in
   `contract/res/README.md`, the hash in the top-level `README.md`, this
   page and [DESIGN.md](DESIGN.md) section 6.
8. **Run the live testnet test** once per simulated card kind. It deploys
   `contract/res/tapvault_contract.wasm`, the file shipped in step 7:
   `android/core/build.gradle.kts` passes that path to the test JVM as
   `TAPVAULT_WASM` unless `TAPVAULT_WASM` is set in the environment. To
   test another build, set `TAPVAULT_WASM` to that file's absolute path.
   From the repository root:

   ```bash
   cd android
   for p in DDA VISA_FDDA MASTERCARD_CDA; do
     TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=$p ./gradlew :core:test --tests '*TestnetE2E*' --rerun
   done
   ```

   `--rerun` matters: `TAPVAULT_WASM` is a task input (changing it reruns
   the test), but the other variables aren't, so without `--rerun` the
   second and third runs can be skipped as up to date. Each run creates
   two fresh accounts (the faucet allows about 10 per IP per 12 h;
   `TAPVAULT_SPONSOR_ACCOUNT` and `TAPVAULT_SPONSOR_KEY` add a fallback;
   with those set, `TAPVAULT_FORCE_SPONSOR=1` skips the faucet). Never run two Gradle builds of the same project at
   once: a concurrent run once made a test task report a false failure.
9. **Rebuild the app** (`android/build-apk.sh`) and check a new wallet's
   `code_hash` ([Verify a wallet](#verify-a-wallet)). Existing wallets keep
   their old code; record which build each test wallet runs.

Things that can't change without a redesign:

- `MAX_SIGNERS` is 8 because the approval bitmap is a `u8` and the rate
  counters are `[u16; 8]`.
- The domain tags and field orders are the protocol. Changing one makes
  every signature made for the old form invalid; give a new form a new tag
  (for example `tapvault/near/v2/...`).
- There is no migration: state layout changes only affect new wallets, and
  old wallets can never be moved to new code.
