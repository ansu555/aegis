# Status

Last updated 13 Nov 2026. Submission deadline: 14 Nov 2026, 23:59 UTC.

**In one line:** the contract, the Kotlin client and the Android app work end to
end on NEAR testnet with simulated cards of all three kinds. Nothing has run
against a real bank card or a real phone's NFC yet; that is the next step and
the main risk.

## What is verified

| Area | Evidence | Result |
|---|---|---|
| Core logic (RSA/SDAD checks, policy, messages) | `cargo test` in `crates/tapvault-core`: 20 unit tests + 7 EMV vector tests | Pass |
| Contract flows | `contract/tests/flows.rs`: 24 tests in the mocked runtime, including a wallet whose card signs like a Visa card online (format 95, 19-byte terminal data) | Pass |
| Compiled contract on a local node | `contract/tests/sandbox.rs` (run with `--ignored`): every step of a wallet's life, gas printed | Pass; most expensive receipt 56.4 Tgas of 300 |
| Kotlin bytes == Rust bytes | `VectorsTest` (Kotlin) + `contract/tests/vectors.rs` (Rust) | Pass |
| Card protocol code | `EmvTest`: 15 tests, the simulated card answering real APDUs as a DDA card, a Visa-style fDDA card and a Mastercard-style CDA card | Pass |
| RPC error handling | `RpcProbeTest` against live testnet | Pass |
| Whole wallet on live testnet | `TestnetE2ETest`, once per card kind, with the shipped contract build: create accounts, deploy, enrol the card, lock the account, pay 0.5 NEAR, over-limit payment waits, cancel, a replayed card signature is refused, freeze by card | Pass for all three (below) |
| Android app on the emulator | Setup, send, approvals, freeze and card check ran on 11 Nov with the old in-app demo card; on 12 Nov the new build was checked to launch and to refuse setup cleanly on a phone without NFC. 13 Nov, the redesign (glass, tab bar, Your card, keypad): Home, Activity, Settings, Your card, Welcome, Limits and the wallet switcher checked on an Android 15 emulator with a view-only testnet wallet; Send's review sheet, the tap sheet and blur performance on a real phone not yet | Pass (emulator has no NFC) |

Live testnet runs on 12 Nov (contract code hash `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`; each wallet has no access keys left):

| Card kind (simulated) | Signs with | Wallet | Payment |
|---|---|---|---|
| Visa-style | fDDA in the GPO answer, format 95, 19 bytes of terminal data | [`tv-e2e-cc807a553f.testnet`](https://testnet.nearblocks.io/address/tv-e2e-cc807a553f.testnet) | [tx](https://testnet.nearblocks.io/txns/4NmY7XUN45wC6NBUfSnTAE3LD5LbBUA8LeQ3hXh2uRjS) |
| Mastercard-style | CDA via GENERATE AC (zero amount declined, then amount 100), format 05, 4 bytes | [`tv-e2e-104d487bcc.testnet`](https://testnet.nearblocks.io/address/tv-e2e-104d487bcc.testnet) | [tx](https://testnet.nearblocks.io/txns/ENyQDKGoR9FWYZwLtxRG6vNbypsm22vFqLugpEN3U6t1) |
| Classic DDA | INTERNAL AUTHENTICATE, format 05, 4 bytes | [`tv-e2e-0b6a860ed9.testnet`](https://testnet.nearblocks.io/address/tv-e2e-0b6a860ed9.testnet) | [tx](https://testnet.nearblocks.io/txns/4rRkvPbpXFPqNpWiH3r3nM8fYDpiFnnk3cnnfJhhhdjY) |

The three runs above used the code before commits `fe1d357` to `941865c` (the
last fixes below). On the code at `941865c`: `cargo test` passes (20 + 7 core,
24 flows, 1 vectors), `./gradlew :core:test` passes (`EmvTest` 15 — the 15th
covers truncated data object lists — and `VectorsTest` 3), and a live
Visa-style run passed again with the new defaults:
[`tv-e2e-9fdbc981d5.testnet`](https://testnet.nearblocks.io/address/tv-e2e-9fdbc981d5.testnet),
[payment](https://testnet.nearblocks.io/txns/D1vpzKzVpmpbRr9LUcwvgWcbDXYmiZs2CvHq1QAnT3JK).
The app build from `941865c` compiles; it hasn't been run on a device.

### 13 Nov: hardware-wallet phase 1 (app only, same contract)

[HARDWARE-WALLET-PLAN.md](HARDWARE-WALLET-PLAN.md) phase 1 is built: several
cards per wallet (Store keyed by card key hash, old single-card wallets
migrated), the tapped card's signer index from its key hash, Add backup card
with an on-phone possession check, Security Center, the Home status pill,
hold-to-sign, and `FLAG_SECURE`. No contract change: the shipped wasm and its
hash are unchanged (`android/build-apk.sh` passes `verifyWasm`), and no
signed bytes changed (`vectors.json` untouched).

| Check | Result |
|---|---|
| `./gradlew :core:test` | Pass: `EmvTest` 15, `MultiCardTest` 7 (identify the tapped card among two by key hash, all three card kinds; a stranger card refused before it signs anything, zero signatures for DDA and CDA; a lifted card noticed during the hold; possession check; card 1 refused as already a signer; card book migration and binding; config proposals name the cards they add and remove), `VectorsTest` 3 |
| `android/build-apk.sh` | Pass, wasm hash check unchanged |
| `TestnetE2ETest`, all three card kinds | Pass ([DDA](https://testnet.nearblocks.io/address/tv-e2e-9023ecafaa.testnet), [Visa-style](https://testnet.nearblocks.io/address/tv-e2e-908d9f3edb.testnet), [Mastercard-style](https://testnet.nearblocks.io/address/tv-e2e-852efa9847.testnet)): besides the earlier steps, card 1 is refused as a backup (already a signer), card 2 (own key and card number) passes the possession check, the phone proposes `AddSigner(card 2)`, card 2 is refused before signing because it isn't a signer yet, card 1 approves, and executing before the 1 h config delay is refused |
| `TestnetE2ETest` with `TAPVAULT_WAIT_DELAYS=1` (waits out both 1 h delays: AddSigner executes and voids the open payment, phone + card 2 pays, card 1 removed and refused, card 2 pays at its new index) | LONG_RUN_RESULT |
| On a phone | Not yet: hold timer reset when the card is lifted, a wrong card refused before any signature, the add-backup flow, Security Center, the pill, `FLAG_SECURE` |

### 13 Nov: hardware-wallet phase 2 (app only, same contract)

Built: lost card (plan 5.4: freeze first, remove with another card, unfreeze
in the same change), integrity check (5.7: code hash, account keys, phone
key, setup mode; every 6 h and from Security Center), review warnings (4.4),
the gap 6b fix (the phone recomputes the intent of any proposal it approves
or cancels and builds the prompt from it), watchtower alerts (5.9, a
JobScheduler job about every 15 min, off until turned on) and an app lock
(fingerprint or screen lock after more than a minute away). Security Center
gained the protection score and the Checks section; the Home pill shows
"Wallet check failed". No contract change, same wasm and hash, no signed
bytes changed.

| Check | Result |
|---|---|
| `./gradlew :core:test` | Pass: 35 tests, `Phase2Test` 8 new (gap 6b refusals, integrity verdicts, watchtower alerts, receivers, the shipped wasm's code hash) |
| `Phase2Test` live (`TAPVAULT_TESTNET=1`) | Pass: `tv-e2e-cc807a553f.testnet` reports code hash `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf` and no account keys |
| App build | Compiles and assembles |
| On a phone | Not yet: everything in [MANUAL-TESTS.md](MANUAL-TESTS.md), phase 2 |

### 13 Nov: hardware-wallet phase 3 (app + client, same contract rules)

Built: backup card during setup (a second `setup_add_signer`, no delay;
"Add a backup card now" on the limits screen), recover on a new phone (plan
5.5: Welcome → Recover a wallet; two cards propose and approve
`RemoveSigner(old phone) + AddSigner(this phone)`, 24 h, the old phone can
cancel and its watchtower says "Someone is recovering this wallet"),
fingerprint-only keys (5.8: a setup option, and for an existing wallet a key
switch through one settings change), and a guardian phone (Welcome → Be a
guardian shows a signed code; the owner adds it in Security Center with a
"full co-signer" warning).

| Check | Result |
|---|---|
| `./gradlew :core:test` | Pass: `Phase3Test` 6 (recovery plans and refusals, recovery recognised, guardian codes) |
| `RecoveryE2ETest` live | Partly: setup with two cards passed on testnet (`tv-e2e-ae0f9541b1.testnet`); the rest stopped at the new phone's fee account because the faucet refused (HTTP 429 for 11 h, no sponsor configured). The test now falls back to a sub-account of the first fee account. **Run it again** (MANUAL-TESTS.md, phase 3) |
| App build | Compiles |
| On a phone | Not yet: MANUAL-TESTS.md sections M to P |

### 13 Nov: hardware-wallet phase 4 (contract v0.2)

Built in the contract source: possession proofs for keys added by settings
changes (gap 8), the sealed seed in the proposal view so the phone checks
the card challenge (gap 6a), a receiver allowlist, and a delayed
`UpgradeCode` (gap 12). Details: [CONTRACT.md](CONTRACT.md#contract-v02).
The client uses each feature only when the wallet reports
`contract_version` 0.2 or later, so wallets on older builds work as before.
Wallets created before this build keep their code (invariant 4).

| Check | Result |
|---|---|
| `cargo test` | Pass: core 20 + 7, flows 29 (5 new), vectors 1 |
| `vectors.json` | Regenerated: two new actions, two new messages; `VectorsTest` passes |
| `./gradlew :core:test` | Pass: 47 tests, `Phase4Test` 5 (card challenge recomputed from the seed and mismatches refused, P-256 verify, possession JSON, 12 Nov wallets still pass the wallet check, allowlist makes unlisted receivers wait) |
| `sandbox.rs` (compiled wasm, nearcore 2.6.5) | Pass (13 Nov): every step; propose `AddSigner` with a possession proof 68.1 Tgas (the most expensive receipt, limit 300), `execute_upgrade` deploy + `migrate` 24.5 Tgas, state kept, open requests voided. The 24 h upgrade wait is skipped by moving `ready_ts` back in the sandbox state (sandbox blocks advance the clock ~0.3 s each); `flows.rs` covers the delay itself |
| Live testnet with the v0.2 build | Not run yet (faucet limit): MANUAL-TESTS.md, phase 4 |

## What is not verified

| Item | Why it matters | How it gets verified |
|---|---|---|
| Real bank cards over NFC | The whole product depends on real cards signing. Support is built from the EMV specs and public captures of real Visa and Mastercard cards, not from a real tap. | [TESTING.md](TESTING.md), real-phone plan: Card check with every card, then a wallet |
| RuPay cards | NPCI's contactless specs aren't public; the signing method is unknown and a newer CA key index may be missing | Card check + "Share report" |
| A real phone's Keystore, fingerprint prompt and NFC antenna | Phones differ (StrongBox, face unlock, antenna position) | The real-phone plan |
| Android 9/10 | The fingerprint-only path is untested on a device | Only if the test phone runs Android 9/10 |
| Several wallets on one phone (13 Nov) | Compiles; switching, adding and forgetting wallets and the per-wallet phone keys haven't run on a phone. Each wallet uses two new testnet accounts | On a phone: keep wallet 1, add wallet 2, send from each, switch back and forth, forget wallet 2 and check wallet 1 still sends |
| The redesigned app UI (13 Nov) | New look, onboarding slides, a separate limits screen, Receive QR, review sheet before sending, pull to refresh, a result banner that no longer shows through. Every screen was checked on the emulator with a view-only copy of an existing testnet wallet; card taps, fingerprint prompts and a full setup can't run there (no NFC) | The real-phone plan, which now uses the new labels |
| Wallets created before 12 Nov | They run the previous contract build, which rejects Visa online signatures | Create a new wallet; old wallets can't be upgraded |
| Phase 2 on a phone (13 Nov) | Lost card, wallet check, review warnings, alerts and the app lock compile and their logic is unit-tested; none has run on a device, and Android decides when background jobs actually run | [MANUAL-TESTS.md](MANUAL-TESTS.md), phase 2 |
| Phase 1 on a phone (13 Nov) | Hold-to-sign, the backup-card flow, Security Center, the Home pill and `FLAG_SECURE` compile and the client logic passes on live testnet with simulated cards; none of the screens has run on a device. `FLAG_SECURE` also makes screenshots, screen recording and `adb exec-out screencap` come out black | [TESTING.md](TESTING.md) steps 15 to 17, checklist 21 to 25 |

## The 12 Nov review

The app, client and contract were reviewed for a first real-phone, real-card
testnet run: EMV protocol, NFC handling, Keystore and biometrics, the NEAR
client and onboarding, the app/contract boundary, app flows, build and
install, and contract security, plus research on how real cards behave.
Every serious finding was checked by two independent reviewers before being
acted on. Fixes are in the commits from `82cb0b1` to `941865c`.

Blockers found and fixed:

- Most contactless cards don't answer INTERNAL AUTHENTICATE over NFC. The app
  now signs with whichever method the card supports: DDA, Visa fDDA, or CDA
  (`CardService.enrol`, `EmvSession.plans`).
- Visa cards sign online fDDA data with Signed Data Format `95`. The contract
  and the app only accepted `05`; both now accept `05` or `95` (same hash
  input), and the new contract build ships in the app.
- Visa returns its per-transaction 9F69 data in the last AFL record; the app
  was using a stale copy. It now re-reads that record after every GPO.
- The RuPay CA keys in `capk.txt` were test keys (6A, 6B), so no real RuPay
  card could pass. The production keys 04, 05 and 06 were added (Visa 10
  too). 6C and 6D were added as well, but eftlab.com labels 6A to 6D as test
  keys.

Other fixes, by area:

- **Setup:** the card is read and signs before the contract is deployed;
  every step checks the chain first and skips what already landed; account
  names and keys are saved before anything is created; Start over deletes
  untouched accounts and returns their NEAR to the sponsor, then lists what
  it deleted ("Started over"), and its dialog matches whether a sponsor is
  built in; a sponsor key that can't be read is ignored (faucet only)
  instead of stopping setup; NFC is required only while the card still has
  to be read; limits are saved exactly as typed (no longer cut to 4
  decimals); the Setup screen shows the wallet account and a "Share card
  report" button.
- **Payments:** Send checks NFC, the spendable balance and the receiver
  before the fingerprint; after a failed card tap, sending the same payment
  again finishes the same proposal instead of creating another (the contract
  allows 4 open proposals per signer); Execute appears only when the
  contract will accept it (it is hidden past the 7-day execution window:
  "Expired: it can no longer execute; cancel it"), and Clean up whenever
  `close_stale` would accept the proposal; approving by card in Activity
  also executes when within limits; if the contract refuses the execute
  part of `approve_card` + `execute`, the card approval is sent again on its
  own instead of being lost ("Approved, waiting" in Send, "Approved" in
  Activity).
- **NFC:** the reader stays on while the app is on screen (no "No supported
  app for this NFC tag" pop-ups), the waiting tap lives in the view model,
  a different card is asked to be replaced, the tap sheet says "Keep holding
  the card…" while reading; when a different card was among three failed
  taps, the error is "That's not the card in this wallet. Use the card you
  added during setup."; Request unfreeze checks NFC before the fingerprint
  and reuses an open unfreeze request.
- **Card:** a truncated PDOL, CDOL1 or DDOL is reported as an unreadable
  answer instead of an index error; enrolment checks the contract's rules
  for a card key (512 to 1984 bits, exponent 3 or 65537, a valid modulus)
  before any signing attempt ("this card can't be a signer: …"); up to five
  applications are tried (all five known ones when the card has no PPSE,
  Maestro and Visa Electron included), and the report notes why each one
  failed; a tap counts as a different card only when a signature in the
  enrolled layout fails to verify, and a card that answered only in another
  layout gets "the card signed, but not the way it did when it was added;
  tap it again".
- **Phone key:** created in the TEE (some phones' StrongBox keys fail at
  signing time), not invalidated by adding a fingerprint, prompts wait until
  the app is on screen, AppCompat theme for Android 9/10; readable errors
  when the key is missing or isn't a signer of the wallet, and the phone's
  signer index is read before the card tap.
- **Network:** three RPCs with fallback; a sent transaction is never
  re-signed; timeouts are looked up by hash; readable errors; a network
  error is no longer taken for a missing account, and a missing access key
  gives a readable error instead of a null-pointer one.
- **UI:** results pinned on screen, an offline banner, confirmations for
  Freeze and Start over, decimal commas, limits validated on Welcome; "Half"
  no longer crashes on Android 12 and older.
- **Build:** the reviewed contract build is committed in `contract/res` and
  hash-checked by the app build; `android/build-apk.sh` picks a JDK 17; the
  live testnet test deploys that build unless `TAPVAULT_WASM` names another
  (it used to need a copy in `target/`), and a sponsor account set without a
  key no longer crashes it.

Contract security (re-checked after the format-95 change): no exploitable or
fund-moving issue found. Format `05` and `95` signatures can't stand in for
each other (the format byte is inside the hashed data), layouts are
length-checked, and phone-only, card-only or network-only attackers can't
move funds. Notes from that pass:

- Online fDDA and CDA make the card produce a real online-authorisation
  cryptogram (ARQC) on every Tapvault tap, which is never sent to the bank.
  This isn't a contract issue, but it is a card-network/compliance question
  for a real product; offline signing (DDA, offline TC) avoids it where a
  card supports it.
- The 24-per-day rate limit is per signer, so a wallet with several cards
  gives a skimmer proportionally more challenges to match. A single card
  approval still can't move funds.
- In fDDA terminal data only the UN is bound to the challenge; amount,
  currency and 9F69 are free bytes. Safe, because the card's signature
  covers them and only the UN carries the intent.

## Known issues and accepted risks

| Issue | Impact | Notes |
|---|---|---|
| Real cards untested | Setup may fail at the card step | Run Card check first; share the report |
| A newer RuPay CA index may be missing | Card check says "no CA key for A000000524 index XX" | Add the key to `capk.txt` once its modulus is known |
| Delayed payments and unfreeze don't execute by themselves | Someone must open Activity and tap Execute after the delay | By design; within the 7-day execution window |
| Long network waits have no cancel button | The busy overlay stays until the RPC answers or times out | Every call has a timeout, but with all three RPCs unreachable a send can stay busy for several minutes before it reports "may still go through: check Activity" |
| A single-card wallet can't be recovered without the phone key | Uninstalling the app or clearing its data strands the wallet's test NEAR | Testnet only; don't uninstall during testing |
| The sponsor key ships inside APKs built with it | Anyone with the APK could spend the sponsor's testnet NEAR | Testnet only; leave `tapvault.sponsor.*` out of `local.properties` to build without it |
| The contract build isn't reproducible elsewhere | Rebuilding gives a different hash | The app ships the committed file; see [`contract/res/README.md`](../contract/res/README.md) |

## Accounts and funding

- Testnet faucet: 10 NEAR per new account, about 10 accounts per IP per 12 h.
- Sponsor (optional, used when the faucet refuses): `tapvault-skyyy.testnet`,
  about 51 NEAR on 12 Nov. Each sponsored wallet costs 6 NEAR (5.5 wallet +
  0.5 fee account).

## Next steps

1. Real-phone test with real cards, following [TESTING.md](TESTING.md).
   Collect the Card check report for every card.
2. Fix whatever the reports show (unknown CA keys, a card answering in a way
   not covered yet).
3. Record the demo ([DEMO.md](DEMO.md)) with a card that passed.
4. Make the repository public, post the demo on X tagging @NEARProtocol with
   #NEARHackathon, and set up the NEAR mainnet payout account.
