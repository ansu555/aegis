# Status

Last updated 12 Nov 2026. Submission deadline: 14 Nov 2026, 23:59 UTC.

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
| Card protocol code | `EmvTest`: 14 tests, the simulated card answering real APDUs as a DDA card, a Visa-style fDDA card and a Mastercard-style CDA card | Pass |
| RPC error handling | `RpcProbeTest` against live testnet | Pass |
| Whole wallet on live testnet | `TestnetE2ETest`, once per card kind, with the shipped contract build: create accounts, deploy, enrol the card, lock the account, pay 0.5 NEAR, over-limit payment waits, cancel, a replayed card signature is refused, freeze by card | Pass for all three (below) |
| Android app on the emulator | Setup, send, approvals, freeze and card check ran on 11 Nov with the old in-app demo card; on 12 Nov the new build was checked to launch and to refuse setup cleanly on a phone without NFC | Pass (emulator has no NFC) |

Live testnet runs on 12 Nov (contract code hash `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf`; each wallet has no access keys left):

| Card kind (simulated) | Signs with | Wallet | Payment |
|---|---|---|---|
| Visa-style | fDDA in the GPO answer, format 95, 19 bytes of terminal data | [`tv-e2e-cc807a553f.testnet`](https://testnet.nearblocks.io/address/tv-e2e-cc807a553f.testnet) | [tx](https://testnet.nearblocks.io/txns/4NmY7XUN45wC6NBUfSnTAE3LD5LbBUA8LeQ3hXh2uRjS) |
| Mastercard-style | CDA via GENERATE AC (zero amount declined, then amount 100), format 05, 4 bytes | [`tv-e2e-104d487bcc.testnet`](https://testnet.nearblocks.io/address/tv-e2e-104d487bcc.testnet) | [tx](https://testnet.nearblocks.io/txns/ENyQDKGoR9FWYZwLtxRG6vNbypsm22vFqLugpEN3U6t1) |
| Classic DDA | INTERNAL AUTHENTICATE, format 05, 4 bytes | [`tv-e2e-0b6a860ed9.testnet`](https://testnet.nearblocks.io/address/tv-e2e-0b6a860ed9.testnet) | [tx](https://testnet.nearblocks.io/txns/4rRkvPbpXFPqNpWiH3r3nM8fYDpiFnnk3cnnfJhhhdjY) |

## What is not verified

| Item | Why it matters | How it gets verified |
|---|---|---|
| Real bank cards over NFC | The whole product depends on real cards signing. Support is built from the EMV specs and public captures of real Visa and Mastercard cards, not from a real tap. | [TESTING.md](TESTING.md), real-phone plan: Card check with every card, then a wallet |
| RuPay cards | NPCI's contactless specs aren't public; the signing method is unknown and a newer CA key index may be missing | Card check + "Share report" |
| A real phone's Keystore, fingerprint prompt and NFC antenna | Phones differ (StrongBox, face unlock, antenna position) | The real-phone plan |
| Android 9/10 | The fingerprint-only path is untested on a device | Only if the test phone runs Android 9/10 |
| Wallets created before 12 Nov | They run the previous contract build, which rejects Visa online signatures | Create a new wallet; old wallets can't be upgraded |

## The 12 Nov review

The app, client and contract were reviewed for a first real-phone, real-card
testnet run: EMV protocol, NFC handling, Keystore and biometrics, the NEAR
client and onboarding, the app/contract boundary, app flows, build and
install, and contract security, plus research on how real cards behave.
Every serious finding was checked by two independent reviewers before being
acted on. Fixes are in the commits from `82cb0b1` to `e613e89`.

Blockers found and fixed:

- Most contactless cards don't answer INTERNAL AUTHENTICATE over NFC. The app
  now signs with whichever method the card supports: DDA, Visa fDDA, or CDA
  (`CardService.enrol`, `EmvSession.plans`).
- Visa cards sign online fDDA data with Signed Data Format `95`. The contract
  and the app only accepted `05`; both now accept `05` or `95` (same hash
  input), and the new contract build ships in the app.
- Visa returns its per-transaction 9F69 data in the last AFL record; the app
  was using a stale copy. It now re-reads that record after every GPO.
- The RuPay CA keys in `capk.txt` were test keys, so no real RuPay card could
  pass. Production keys 04, 05, 06 and 6C, 6D were added (Visa 10 too).

Other fixes, by area:

- **Setup:** the card is read and signs before the contract is deployed;
  every step checks the chain first and skips what already landed; account
  names and keys are saved before anything is created; Start over deletes
  untouched accounts and returns their NEAR to the sponsor.
- **Payments:** Send checks NFC, the spendable balance and the receiver
  before the fingerprint; after a failed card tap, sending the same payment
  again finishes the same proposal instead of creating another (the contract
  allows 4 open proposals per signer); Execute appears only when the
  contract will accept it; approving by card in Activity also executes when
  within limits.
- **NFC:** the reader stays on while the app is on screen (no "No supported
  app for this NFC tag" pop-ups), the waiting tap lives in the view model,
  a different card is asked to be replaced, the tap sheet says "Keep holding
  the card…" while reading.
- **Phone key:** created in the TEE (some phones' StrongBox keys fail at
  signing time), not invalidated by adding a fingerprint, prompts wait until
  the app is on screen, AppCompat theme for Android 9/10.
- **Network:** three RPCs with fallback; a sent transaction is never
  re-signed; timeouts are looked up by hash; readable errors.
- **UI:** results pinned on screen, an offline banner, confirmations for
  Freeze and Start over, decimal commas, limits validated on Welcome; "Half"
  no longer crashes on Android 12 and older.
- **Build:** the reviewed contract build is committed in `contract/res` and
  hash-checked by the app build; `android/build-apk.sh` picks a JDK 17.

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
| `approve_card` and `execute` share one transaction | If execute fails, the card approval is discarded too | The pre-checks make this rare; tap again |
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
