# Tapvault

A self-custodial NEAR wallet co-signed by **the bank card you already
carry** and **your phone's biometric key**. No seed phrase, no hardware
wallet to buy. NEAR testnet only.

- Your **contactless debit/credit card** has a chip with an RSA key that can't
  be copied out. Tapvault uses its standard EMV signature (the one terminals
  use to check the card is genuine) as a signer. No PIN, no payment, nothing
  sent to the bank.
- Your **phone** holds a P-256 key in the Android Keystore's secure hardware,
  unlocked by your fingerprint or screen lock for every single signature.
- A NEAR contract verifies both **on-chain** and enforces spending limits,
  delays and freeze. The contract also supports recovery and passkey / FIDO2
  signers; the app doesn't use them yet.

> **Status, 12 Nov 2026.** The contract, the Kotlin client and the Android
> app work end to end on NEAR testnet with simulated cards of all three kinds.
> Nothing has been run against a real bank card or a real phone's NFC yet;
> that is the next step and the main risk. Details:
> [docs/STATUS.md](docs/STATUS.md).

Read [`docs/DESIGN.md`](docs/DESIGN.md) for the threat model, including what
this design does **not** protect against.

## How it works

```
 phone (fingerprint)             NEAR: wallet account + Tapvault contract
 ───────────────────             ─────────────────────────────────────────
 sign intent hash  ──propose──▶  store action, schedule seal_challenge
                                 ...next block: seed = random_seed  (sealed)
 read card challenge ◀─────────  H(card-approve ‖ intent ‖ seed)
 tap card (NFC)
 card signs challenge ─approve_card + execute─▶  RSA check, policy, transfer
```

A card signs anything a reader sends it, so a challenge must be impossible
to know before the action is fixed. Tapvault commits the action first and
derives the card's challenge from block randomness captured in a **later
block**. A signature skimmed from your pocket can't match it.

Rules the contract enforces:

| | |
|---|---|
| Every transfer | `threshold` approvals, at least one from a phone / passkey. Wallets the app creates: phone + card, threshold 2 |
| Within the limits | executes instantly |
| Over the limits | waits a delay (1 h in wallets the app creates; contract default 24 h); meanwhile a signer can cancel it or freeze the wallet |
| Config changes | always delayed; longer if no phone approved (lost-phone recovery) |
| Freeze | any single signer, phone or card tap |
| After setup | the account's own key is deleted; only the contract moves funds |

The full rule table, the user journey and a glossary are in
[docs/OVERVIEW.md](docs/OVERVIEW.md).

## Documentation

- [docs/README.md](docs/README.md): index of every doc, with reading paths
  (testing on a phone, changing the app, changing the contract, the security
  model).
- [AGENTS.md](AGENTS.md): start here to change the code (layout, exact
  commands, invariants, where things are).
- Most used: [OVERVIEW](docs/OVERVIEW.md), [ARCHITECTURE](docs/ARCHITECTURE.md),
  [CARDS](docs/CARDS.md), [ANDROID](docs/ANDROID.md),
  [TESTING](docs/TESTING.md), [TROUBLESHOOTING](docs/TROUBLESHOOTING.md),
  [STATUS](docs/STATUS.md).

## Layout

```
crates/tapvault-core/   Dependency-free security logic (native-testable)
  src/bigint.rs         RSA public-key math (u32 limbs)
  src/emv.rs            EMV signature check (DDA, fDDA, CDA; formats 05 and 95)
  src/webauthn.rs       FIDO2 / passkey assertion checks
  src/domain.rs         Every signed message, domain-separated
  src/policy.rs         Limits, delays, approval rules, rate limits
  src/testcard.rs       Simulated EMV card for tests (feature test-card, not in the app)
contract/               NEAR contract (near-sdk 5.14)
  src/proposals.rs      propose, seal_challenge, approvals
  src/execute.rs        transfers (NEAR, NEP-141) and config changes
  src/guard.rs          cancel, freeze, close_stale
  tests/flows.rs        flows in the mocked runtime (24 tests)
  tests/vectors.rs      byte vectors shared with the Kotlin client
  tests/sandbox.rs      the compiled wasm on a local NEAR node, with gas report
  res/                  the contract build the app ships, and its hashes
android/
  build-apk.sh          finds a JDK 17 and builds the debug APK
  core/                 Kotlin client (plain JVM): NEAR RPC, contract calls,
                        onboarding, the EMV card protocol (DDA, fDDA, CDA),
                        CA keys; tests include a live testnet run
  app/                  Android app: Keystore phone key behind biometrics,
                        NFC card taps, setup, send, approvals, freeze, card check
tools/                  Test-vector, test-card and simulated-card key generators
docs/                   Documentation (start at docs/README.md)
AGENTS.md               Guide for coding agents and contributors
```

## Build and install the Android app

You need **JDK 17** (the Gradle 8.11.1 setup here fails on JDK 21 and newer)
and the **Android SDK** with `platforms;android-35` and `build-tools;34.0.0`
(the default build tools of AGP 8.7.3; the first build downloads them if they
are missing and the SDK licences are accepted).
Rust is **not** needed: the reviewed contract build is committed at
`contract/res/tapvault_contract.wasm`, and the Gradle build refuses any file
with a different hash.

```properties
# android/local.properties (gitignored: never commit it)
sdk.dir=/path/to/Android/sdk
```

```bash
android/build-apk.sh
# -> android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The phone needs Android 9 or newer with NFC, NFC switched on, and a screen
lock (Android 9 and 10: also a fingerprint). The APK is debug-signed, so it is
sideloaded; Play Protect, MIUI or ColorOS may warn or scan it. Setting up a
wallet takes about a minute: one card tap and three fingerprints.

Two things that lose a wallet on testnet:

- **Uninstalling** the app or clearing its storage deletes the phone key. A
  single-card wallet can't approve anything without it.
- **Updating with an APK built on another computer**: debug signing keys
  differ, Android refuses the update until you uninstall (see above). Build
  test APKs on one machine.

More (configuration, logs, internals): [docs/ANDROID.md](docs/ANDROID.md).

### Optional: a sponsor account

New wallets get their two accounts from the testnet faucet (10 NEAR each;
about 10 accounts per IP per 12 h, then HTTP 429). To keep working when it
refuses, build the APK with a funded testnet account as sponsor:

```properties
# android/local.properties
tapvault.sponsor.account=<a funded testnet account>
tapvault.sponsor.key=ed25519:<secret key of one of its full-access keys>
```

The app then creates `tv-<10 hex>.<sponsor>` (5.5 NEAR) and
`tv-<10 hex>-gas.<sponsor>` (0.5 NEAR) when the faucet refuses (each account
is decided on its own). "Start over" during setup then deletes the fee
account, and the wallet account if the contract step hasn't been reached,
sending their NEAR to the sponsor; without a sponsor it deletes nothing
on-chain. The key is compiled into the APK's `BuildConfig`: anyone with that
APK can spend the sponsor's testnet NEAR. Testnet only; leave both lines out
to build without it.

## Tests

Rust (the pinned 1.86.0 toolchain installs itself from `rust-toolchain.toml`):

```bash
cargo test     # core: 20 unit + 7 EMV vector tests; contract: 24 flow tests + 1 vectors test

# the compiled contract on a local NEAR node, gas per step (downloads a nearcore sandbox)
cargo test -p tapvault-contract --test sandbox -- --ignored --nocapture
```

Kotlin, with JDK 17:

```bash
cd android
./gradlew :core:test      # EmvTest (15), VectorsTest (3: Kotlin bytes == Rust bytes)
```

Live testnet (creates fresh accounts, deploys the shipped contract, enrols a
simulated card through its certificate chain, locks the account, pays
0.5 NEAR, checks that a 1.5 NEAR payment waits, cancels it, checks that a
replayed card signature is refused, freezes with a card tap):

```bash
cd android
TAPVAULT_TESTNET=1 TAPVAULT_CARD_PROFILE=VISA_FDDA ./gradlew :core:test --tests '*TestnetE2E*' --rerun
```

`TAPVAULT_CARD_PROFILE` is `DDA` (default), `VISA_FDDA` or `MASTERCARD_CDA`.
All variables and caveats: [AGENTS.md](AGENTS.md#live-testnet-tests). The
real-phone test plan: [docs/TESTING.md](docs/TESTING.md).

## Contract build

The app deploys `contract/res/tapvault_contract.wasm` to every new wallet:

| | |
|---|---|
| SHA-256 (base58, NEAR `code_hash`) | `5Db4uQQKpTkTtUspdQcx5iVcnvT7MSXCNMdKwhQibwcf` |
| SHA-256 (hex) | `3ea83cc88fecda3c727d624e5a935ed1fe20cc5623cafafa4e86969bf7cdc2ec` |
| Size | 334,258 bytes (3.34 NEAR of storage; with the account and the wallet's state, each wallet locks about 3.35 NEAR) |
| Built with | rustc 1.86.0, cargo-near 0.17.0, `cargo near build non-reproducible-wasm` |

Every wallet created with the current app should show this `code_hash` on
chain, for example
[`tv-e2e-cc807a553f.testnet`](https://testnet.nearblocks.io/address/tv-e2e-cc807a553f.testnet)
from the 12 Nov live run, which has no access keys. Wallets created before
12 Nov run the previous build (`GniW1i9n8vAH6ers3jAZTnpCox3u5VwWvsmN9cY9Foow`),
which rejects Visa online signatures; wallets can't be upgraded. Rebuilding
gives a different hash (non-reproducible build); how to ship a new build:
[contract/res/README.md](contract/res/README.md).

## Measured gas

Sandbox (nearcore 2.6.5), 12 Nov, the contract compiled from the current
source by the sandbox test; limit 300 Tgas per receipt:

| Step | Gas |
|---|---|
| deploy + `new` | 56.4 Tgas |
| `setup_add_signer`, 1024-bit card | 34.7 Tgas |
| `setup_add_signer`, 1984-bit card | 53.4 Tgas |
| `setup_finalize` | 35.1 Tgas |
| `propose` (phone) | 34.8 Tgas, plus 2.0 Tgas for `seal_challenge` |
| `approve_card`, 1024-bit card | 2.7 Tgas |
| `approve_card`, 1984-bit card, e = 65537 | 21.3 Tgas |
| `execute`, NEAR transfer | 2.7 Tgas, plus 1.6 Tgas callback |
| `freeze` by card | 2.4 Tgas |

The P-256 phone-signature check in wasm is about 32 Tgas of the `propose`
cost. The most expensive receipt is 56.4 Tgas.

## Status

| Part | State |
|---|---|
| Core crate | 20 unit tests + 7 EMV vector tests pass |
| NEAR contract | 24 flow tests (including a Visa online fDDA card) + vectors test pass; the compiled wasm passes the sandbox run |
| Security review | An earlier contract review (7 findings, all fixed; [DESIGN.md](docs/DESIGN.md) section 9), then the 12 Nov review of the EMV protocol, NFC, keystore, client, contract and build: blockers fixed, and the contract re-check found no fund-moving issue ([STATUS.md](docs/STATUS.md#the-12-nov-review)). Still open: the app takes the card challenge and the hash it signs for "Approve · phone" from the RPC without recomputing them ([DESIGN.md](docs/DESIGN.md), known gaps). Not a professional audit |
| Kotlin client | Vectors match the contract byte for byte; EMV tests pass for DDA, Visa-style fDDA and Mastercard-style CDA simulated cards; the live testnet run passes with all three |
| Android app | Emulator only (no NFC there). The screens and flows last ran there on 11 Nov, before the built-in demo card was removed; on 12 Nov a build from before the latest app fixes (`941865c`) was checked to launch and to refuse setup cleanly without NFC. Real cards are the only cards it supports |
| Real cards on a real phone | **Untested.** The next step and the main risk; RuPay's signing method is unknown |
| Merchant POS | Not started |

Live testnet wallets from 12 Nov (simulated cards, shipped contract, no access
keys left):
[Visa-style fDDA](https://testnet.nearblocks.io/address/tv-e2e-cc807a553f.testnet),
[Mastercard-style CDA](https://testnet.nearblocks.io/address/tv-e2e-104d487bcc.testnet),
[classic DDA](https://testnet.nearblocks.io/address/tv-e2e-0b6a860ed9.testnet).
Verified and unverified items, known issues and next steps:
[docs/STATUS.md](docs/STATUS.md).

## Hackathon

Built for NEAR HACKATHON (nearhackathon.com). Submission deadline
**14 Nov 2026, 23:59 UTC**. Tracks: **Real-World Money** (pay with the card
you already carry), **AI Security & Defense** (spending limits and a policy
engine an agent or a person can't bypass), **Open Innovation**.

The protocol design started as a Solana prototype on 9 Nov 2026 (not in this
repo). Everything here is the NEAR implementation, built during the
hackathon window. More: [docs/OVERVIEW.md](docs/OVERVIEW.md#hackathon-context).

## License

MIT
